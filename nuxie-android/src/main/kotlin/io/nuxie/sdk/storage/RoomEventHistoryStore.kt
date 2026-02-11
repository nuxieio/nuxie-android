package io.nuxie.sdk.storage

import io.nuxie.sdk.events.store.EventHistoryStore
import io.nuxie.sdk.events.store.StoredEvent
import io.nuxie.sdk.storage.db.EventHistoryDao
import io.nuxie.sdk.storage.db.EventHistoryEntity
import kotlinx.serialization.json.Json

internal class RoomEventHistoryStore(
  private val dao: EventHistoryDao,
  private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
  private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) : EventHistoryStore {
  override suspend fun insert(event: StoredEvent) {
    dao.insert(EventHistoryEntity.from(event, json, createdAtMs = nowEpochMillis()))
  }

  override suspend fun getRecentEvents(limit: Int): List<StoredEvent> {
    return dao.recent(limit).map { it.toStoredEvent(json) }
  }

  override suspend fun getEventsForUser(distinctId: String, limit: Int): List<StoredEvent> {
    return dao.byUser(distinctId, limit).map { it.toStoredEvent(json) }
  }

  override suspend fun count(): Int = dao.count()

  override suspend fun deleteEventsOlderThan(cutoffEpochMillis: Long): Int {
    return dao.deleteOlderThan(cutoffEpochMillis)
  }

  override suspend fun reassignDistinctId(fromDistinctId: String, toDistinctId: String): Int {
    return dao.reassignDistinctId(fromDistinctId = fromDistinctId, toDistinctId = toDistinctId)
  }

  override suspend fun clear() = dao.clear()
}

