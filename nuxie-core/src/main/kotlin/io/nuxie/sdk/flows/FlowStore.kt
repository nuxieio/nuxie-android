package io.nuxie.sdk.flows

import io.nuxie.sdk.network.NuxieApiProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cache + fetch for Flow models.
 *
 * Mirrors iOS `FlowStore` behavior:
 * - in-memory cache of enriched `Flow` models
 * - concurrent fetch de-duping per flow id
 *
 * Note: Android product enrichment (Play Billing) is not implemented yet, so
 * this currently returns `Flow(remoteFlow, products=[])`.
 */
class FlowStore(
  private val api: NuxieApiProtocol,
) {
  private val mutex = Mutex()
  private val cachedById = mutableMapOf<String, Flow>()
  private val pendingById = mutableMapOf<String, CompletableDeferred<Flow>>()

  suspend fun flow(id: String): Flow {
    // Fast path: cached
    mutex.withLock {
      val cached = cachedById[id]
      if (cached != null) return cached
    }

    val deferred: CompletableDeferred<Flow>
    val shouldFetch: Boolean
    mutex.withLock {
      val cached = cachedById[id]
      if (cached != null) return cached

      val existing = pendingById[id]
      if (existing != null) {
        deferred = existing
        shouldFetch = false
      } else {
        deferred = CompletableDeferred()
        pendingById[id] = deferred
        shouldFetch = true
      }
    }

    if (shouldFetch) {
      try {
        val remote = api.fetchFlow(flowId = id)
        val flow = Flow(remoteFlow = remote)
        mutex.withLock { cachedById[id] = flow }
        deferred.complete(flow)
      } catch (t: Throwable) {
        deferred.completeExceptionally(t)
        throw t
      } finally {
        mutex.withLock { pendingById.remove(id) }
      }
    }

    return deferred.await()
  }

  /**
   * Seed the in-memory cache with profile-delivered flows.
   */
  suspend fun preloadFlows(remoteFlows: List<RemoteFlow>) {
    if (remoteFlows.isEmpty()) return
    mutex.withLock {
      for (rf in remoteFlows) {
        cachedById[rf.id] = Flow(remoteFlow = rf)
      }
    }
  }

  suspend fun removeFlow(id: String) {
    mutex.withLock {
      cachedById.remove(id)
      pendingById.remove(id)?.cancel(CancellationException("flow removed"))
    }
  }

  suspend fun clearCache() {
    mutex.withLock {
      cachedById.clear()
      for ((_, pending) in pendingById) {
        pending.cancel(CancellationException("cache cleared"))
      }
      pendingById.clear()
    }
  }
}

