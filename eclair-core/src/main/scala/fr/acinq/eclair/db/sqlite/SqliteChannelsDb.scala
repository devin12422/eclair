/*
 * Copyright 2019 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair.db.sqlite

import java.sql.{Connection, Statement}

import fr.acinq.bitcoin.ByteVector32
import fr.acinq.eclair.CltvExpiry
import fr.acinq.eclair.channel.HasCommitments
import fr.acinq.eclair.db.ChannelsDb
import fr.acinq.eclair.db.Monitoring.Metrics.withMetrics
import fr.acinq.eclair.wire.ChannelCodecs.stateDataCodec
import grizzled.slf4j.Logging

import scala.collection.immutable.Queue

class SqliteChannelsDb(sqlite: Connection) extends ChannelsDb with Logging {

  import SqliteUtils.ExtendedResultSet._
  import SqliteUtils._

  val DB_NAME = "channels"
  val CURRENT_VERSION = 2

  // The SQLite documentation states that "It is not possible to enable or disable foreign key constraints in the middle
  // of a multi-statement transaction (when SQLite is not in autocommit mode).".
  // So we need to set foreign keys before we initialize tables / migrations (which is done inside a transaction).
  using(sqlite.createStatement()) { statement =>
    statement.execute("PRAGMA foreign_keys = ON")
  }

  using(sqlite.createStatement(), inTransaction = true) { statement =>

    def migration12(statement: Statement) = {
      statement.executeUpdate("ALTER TABLE local_channels ADD COLUMN is_closed BOOLEAN NOT NULL DEFAULT 0")
    }

    getVersion(statement, DB_NAME, CURRENT_VERSION) match {
      case 1 =>
        logger.warn(s"migrating db $DB_NAME, found version=1 current=$CURRENT_VERSION")
        migration12(statement)
        setVersion(statement, DB_NAME, CURRENT_VERSION)
      case CURRENT_VERSION =>
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS local_channels (channel_id BLOB NOT NULL PRIMARY KEY, data BLOB NOT NULL, is_closed BOOLEAN NOT NULL DEFAULT 0)")
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS htlc_infos (channel_id BLOB NOT NULL, commitment_number BLOB NOT NULL, payment_hash BLOB NOT NULL, cltv_expiry INTEGER NOT NULL, FOREIGN KEY(channel_id) REFERENCES local_channels(channel_id))")
        statement.executeUpdate("CREATE INDEX IF NOT EXISTS htlc_infos_idx ON htlc_infos(channel_id, commitment_number)")
      case unknownVersion => throw new RuntimeException(s"Unknown version of DB $DB_NAME found, version=$unknownVersion")
    }

  }

  override def addOrUpdateChannel(state: HasCommitments): Unit = withMetrics("channels/add-or-update-channel") {
    val data = stateDataCodec.encode(state).require.toByteArray
    using(sqlite.prepareStatement("UPDATE local_channels SET data=? WHERE channel_id=?")) { update =>
      update.setBytes(1, data)
      update.setBytes(2, state.channelId.toArray)
      if (update.executeUpdate() == 0) {
        using(sqlite.prepareStatement("INSERT INTO local_channels VALUES (?, ?, 0)")) { statement =>
          statement.setBytes(1, state.channelId.toArray)
          statement.setBytes(2, data)
          statement.executeUpdate()
        }
      }
    }
  }

  override def removeChannel(channelId: ByteVector32): Unit = withMetrics("channels/remove-channel") {
    using(sqlite.prepareStatement("DELETE FROM pending_relay WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.executeUpdate()
    }

    using(sqlite.prepareStatement("DELETE FROM htlc_infos WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.executeUpdate()
    }

    using(sqlite.prepareStatement("UPDATE local_channels SET is_closed=1 WHERE channel_id=?")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.executeUpdate()
    }
  }

  override def listLocalChannels(): Seq[HasCommitments] = withMetrics("channels/list-local-channels") {
    using(sqlite.createStatement) { statement =>
      val rs = statement.executeQuery("SELECT data FROM local_channels WHERE is_closed=0")
      codecSequence(rs, stateDataCodec)
    }
  }

  override def addHtlcInfo(channelId: ByteVector32, commitmentNumber: Long, paymentHash: ByteVector32, cltvExpiry: CltvExpiry): Unit = withMetrics("channels/add-htlc-info") {
    using(sqlite.prepareStatement("INSERT INTO htlc_infos VALUES (?, ?, ?, ?)")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.setLong(2, commitmentNumber)
      statement.setBytes(3, paymentHash.toArray)
      statement.setLong(4, cltvExpiry.toLong)
      statement.executeUpdate()
    }
  }

  override def listHtlcInfos(channelId: ByteVector32, commitmentNumber: Long): Seq[(ByteVector32, CltvExpiry)] = withMetrics("channels/list-htlc-infos") {
    using(sqlite.prepareStatement("SELECT payment_hash, cltv_expiry FROM htlc_infos WHERE channel_id=? AND commitment_number=?")) { statement =>
      statement.setBytes(1, channelId.toArray)
      statement.setLong(2, commitmentNumber)
      val rs = statement.executeQuery
      var q: Queue[(ByteVector32, CltvExpiry)] = Queue()
      while (rs.next()) {
        q = q :+ (ByteVector32(rs.getByteVector32("payment_hash")), CltvExpiry(rs.getLong("cltv_expiry")))
      }
      q
    }
  }

  // used by mobile apps
  override def close(): Unit = sqlite.close()
}
