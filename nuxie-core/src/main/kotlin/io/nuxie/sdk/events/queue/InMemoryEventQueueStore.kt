package io.nuxie.sdk.events.queue

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryEventQueueStore : EventQueueStore {
  private val lock = Mutex()
  private val events: MutableList<QueuedEvent> = mutableListOf()

  override suspend fun enqueue(event: QueuedEvent): Boolean = lock.withLock {
    events.add(event)
    true
  }

  override suspend fun size(): Int = lock.withLock { events.size }

  override suspend fun peek(limit: Int): List<QueuedEvent> = lock.withLock {
    events.take(limit)
  }

  override suspend fun delete(ids: List<String>) {
    lock.withLock {
      val set = ids.toHashSet()
      events.removeAll { it.id in set }
    }
  }

  override suspend fun reassignDistinctId(fromDistinctId: String, toDistinctId: String): Int {
    return lock.withLock {
      var count = 0
      for (i in events.indices) {
        val e = events[i]
        if (e.distinctId == fromDistinctId) {
          events[i] = e.copy(distinctId = toDistinctId)
          count += 1
        }
      }
      count
    }
  }

  override suspend fun clear() = lock.withLock {
    events.clear()
  }
}
