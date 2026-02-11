package io.nuxie.sdk.storage.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
  entities = [
    EventQueueEntity::class,
    EventHistoryEntity::class,
  ],
  version = 2,
  exportSchema = false,
)
internal abstract class NuxieDatabase : RoomDatabase() {
  abstract fun eventQueueDao(): EventQueueDao
  abstract fun eventHistoryDao(): EventHistoryDao
}
