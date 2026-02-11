package io.nuxie.sdk.events.store

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory event history store for unit tests and non-Android runtimes.
 */
class InMemoryEventHistoryStore : EventHistoryStore {
  private val byId = ConcurrentHashMap<String, StoredEvent>()
  private val orderedIds = CopyOnWriteArrayList<String>()

  override suspend fun insert(event: StoredEvent) {
    byId[event.id] = event
    orderedIds.add(event.id)
  }

  override suspend fun getRecentEvents(limit: Int): List<StoredEvent> {
    return orderedIds.asReversed()
      .take(limit)
      .mapNotNull { byId[it] }
      .sortedByDescending { it.timestampEpochMillis }
  }

  override suspend fun getEventsForUser(distinctId: String, limit: Int): List<StoredEvent> {
    return byId.values
      .asSequence()
      .filter { it.distinctId == distinctId }
      .sortedByDescending { it.timestampEpochMillis }
      .take(limit)
      .toList()
  }

  override suspend fun count(): Int = byId.size

  override suspend fun deleteEventsOlderThan(cutoffEpochMillis: Long): Int {
    val toDelete = byId.values.filter { it.timestampEpochMillis < cutoffEpochMillis }.map { it.id }
    for (id in toDelete) {
      byId.remove(id)
      orderedIds.remove(id)
    }
    return toDelete.size
  }

  override suspend fun reassignDistinctId(fromDistinctId: String, toDistinctId: String): Int {
    var updated = 0
    for ((id, ev) in byId) {
      if (ev.distinctId == fromDistinctId) {
        byId[id] = ev.copy(distinctId = toDistinctId)
        updated += 1
      }
    }
    return updated
  }

  override suspend fun clear() {
    byId.clear()
    orderedIds.clear()
  }
}

