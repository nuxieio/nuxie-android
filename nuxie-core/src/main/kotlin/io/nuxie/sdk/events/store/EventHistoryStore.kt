package io.nuxie.sdk.events.store

/**
 * Persistent store for event history (local analytics log).
 *
 * This exists for local IR evaluation (segments, journeys) and mirrors iOS `EventStore`.
 */
interface EventHistoryStore {
  suspend fun insert(event: StoredEvent)
  suspend fun getRecentEvents(limit: Int): List<StoredEvent>
  suspend fun getEventsForUser(distinctId: String, limit: Int): List<StoredEvent>
  suspend fun count(): Int
  suspend fun deleteEventsOlderThan(cutoffEpochMillis: Long): Int
  suspend fun reassignDistinctId(fromDistinctId: String, toDistinctId: String): Int
  suspend fun clear()
}

