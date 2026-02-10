package io.nuxie.sdk.events.queue

interface EventQueueStore {
  suspend fun enqueue(event: QueuedEvent): Boolean
  suspend fun size(): Int
  suspend fun peek(limit: Int): List<QueuedEvent>
  suspend fun delete(ids: List<String>)
  suspend fun clear()
}

