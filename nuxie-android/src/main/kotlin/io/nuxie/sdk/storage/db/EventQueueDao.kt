package io.nuxie.sdk.storage.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface EventQueueDao {
  @Insert(onConflict = OnConflictStrategy.ABORT)
  suspend fun insert(entity: EventQueueEntity)

  @Query("SELECT COUNT(*) FROM nuxie_event_queue")
  suspend fun count(): Int

  @Query("SELECT * FROM nuxie_event_queue ORDER BY createdAtMs ASC LIMIT :limit")
  suspend fun peek(limit: Int): List<EventQueueEntity>

  @Query("DELETE FROM nuxie_event_queue WHERE id IN (:ids)")
  suspend fun delete(ids: List<String>)

  @Query("UPDATE nuxie_event_queue SET distinctId = :toDistinctId WHERE distinctId = :fromDistinctId")
  suspend fun reassignDistinctId(fromDistinctId: String, toDistinctId: String): Int

  @Query("DELETE FROM nuxie_event_queue")
  suspend fun clear()
}
