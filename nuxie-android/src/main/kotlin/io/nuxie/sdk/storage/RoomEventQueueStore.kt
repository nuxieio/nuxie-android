package io.nuxie.sdk.storage

import io.nuxie.sdk.events.queue.EventQueueStore
import io.nuxie.sdk.events.queue.QueuedEvent
import io.nuxie.sdk.storage.db.EventQueueDao
import io.nuxie.sdk.storage.db.EventQueueEntity
import kotlinx.serialization.json.Json

internal class RoomEventQueueStore(
  private val dao: EventQueueDao,
  private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
  private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) : EventQueueStore {
  override suspend fun enqueue(event: QueuedEvent): Boolean {
    dao.insert(EventQueueEntity.from(event, json, nowEpochMillis()))
    return true
  }

  override suspend fun size(): Int = dao.count()

  override suspend fun peek(limit: Int): List<QueuedEvent> =
    dao.peek(limit).map { it.toQueuedEvent(json) }

  override suspend fun delete(ids: List<String>) = dao.delete(ids)

  override suspend fun clear() = dao.clear()
}

