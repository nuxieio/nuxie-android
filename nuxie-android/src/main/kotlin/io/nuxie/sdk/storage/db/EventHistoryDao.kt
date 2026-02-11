package io.nuxie.sdk.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface EventHistoryDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(entity: EventHistoryEntity)

  @Query("SELECT * FROM nuxie_event_history ORDER BY timestampEpochMillis DESC, createdAtMs DESC LIMIT :limit")
  suspend fun recent(limit: Int): List<EventHistoryEntity>

  @Query("SELECT * FROM nuxie_event_history WHERE distinctId = :distinctId ORDER BY timestampEpochMillis DESC, createdAtMs DESC LIMIT :limit")
  suspend fun byUser(distinctId: String, limit: Int): List<EventHistoryEntity>

  @Query("SELECT COUNT(*) FROM nuxie_event_history")
  suspend fun count(): Int

  @Query("DELETE FROM nuxie_event_history WHERE timestampEpochMillis < :cutoffEpochMillis")
  suspend fun deleteOlderThan(cutoffEpochMillis: Long): Int

  @Query("UPDATE nuxie_event_history SET distinctId = :toDistinctId WHERE distinctId = :fromDistinctId")
  suspend fun reassignDistinctId(fromDistinctId: String, toDistinctId: String): Int

  @Query("DELETE FROM nuxie_event_history")
  suspend fun clear()
}

