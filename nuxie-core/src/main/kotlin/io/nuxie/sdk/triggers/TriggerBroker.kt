package io.nuxie.sdk.triggers

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-process broker to fan out progressive [TriggerUpdate]s by event id.
 *
 * Mirrors iOS `TriggerBroker`.
 */
interface TriggerBroker {
  suspend fun register(eventId: String, handler: suspend (TriggerUpdate) -> Unit)
  suspend fun emit(eventId: String, update: TriggerUpdate)
  suspend fun complete(eventId: String)
  suspend fun reset()
}

class DefaultTriggerBroker : TriggerBroker {
  private val mutex = Mutex()
  private val handlers: MutableMap<String, suspend (TriggerUpdate) -> Unit> = mutableMapOf()

  override suspend fun register(eventId: String, handler: suspend (TriggerUpdate) -> Unit) {
    mutex.withLock { handlers[eventId] = handler }
  }

  override suspend fun emit(eventId: String, update: TriggerUpdate) {
    val handler = mutex.withLock { handlers[eventId] } ?: return
    handler(update)
  }

  override suspend fun complete(eventId: String) {
    mutex.withLock { handlers.remove(eventId) }
  }

  override suspend fun reset() {
    mutex.withLock { handlers.clear() }
  }
}

