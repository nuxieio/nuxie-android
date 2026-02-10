package io.nuxie.sdk.events.queue

import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.network.NuxieApi
import io.nuxie.sdk.network.NuxieNetworkError
import io.nuxie.sdk.network.models.BatchEventItem
import io.nuxie.sdk.network.models.BatchRequest
import io.nuxie.sdk.util.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.pow

/**
 * Event network delivery queue.
 *
 * This is intentionally modeled after iOS `NuxieNetworkQueue`, but backed by a persisted store.
 */
class NuxieNetworkQueue(
  private val store: EventQueueStore,
  private val api: NuxieApi,
  private val scope: CoroutineScope,
  private val clock: Clock = Clock.system(),
  private val flushAt: Int,
  private val flushIntervalSeconds: Long,
  private val maxQueueSize: Int,
  private val maxBatchSize: Int,
  private val maxRetries: Int,
  private val baseRetryDelaySeconds: Long,
) {
  @Volatile private var isPaused: Boolean = false
  @Volatile private var isCurrentlyFlushing: Boolean = false

  private var retryCount: Int = 0
  private var nextRetryAtEpochMillis: Long? = null

  private var flushJob: Job? = null

  fun start() {
    if (flushJob != null) return
    flushJob = scope.launch {
      while (true) {
        delay(flushIntervalSeconds * 1000L)
        try {
          flushIfNeeded(forceSend = false)
        } catch (e: Exception) {
          NuxieLogger.debug("Timer flush failed: ${e.message}", e)
        }
      }
    }
  }

  fun stop() {
    flushJob?.cancel()
    flushJob = null
  }

  suspend fun enqueue(event: QueuedEvent): Boolean {
    val current = store.size()
    if (current >= maxQueueSize) {
      NuxieLogger.warning("Event queue full ($current >= $maxQueueSize), dropping event: ${event.name}")
      return false
    }
    val ok = store.enqueue(event)
    if (ok) {
      flushIfOverThreshold()
    }
    return ok
  }

  suspend fun flushIfOverThreshold() {
    val size = store.size()
    if (size >= flushAt) {
      NuxieLogger.debug("Queue threshold reached ($size >= $flushAt), triggering flush")
      flushIfNeeded(forceSend = false)
    }
  }

  suspend fun flush(forceSend: Boolean = true): Boolean {
    return flushIfNeeded(forceSend = forceSend)
  }

  suspend fun pause() {
    isPaused = true
  }

  suspend fun resume() {
    isPaused = false
    flushIfOverThreshold()
  }

  private suspend fun flushIfNeeded(forceSend: Boolean): Boolean {
    if (!forceSend && isPaused) return false
    if (isCurrentlyFlushing) return false

    val size = store.size()
    if (size == 0) return false

    val now = clock.nowEpochMillis()
    val nextRetry = nextRetryAtEpochMillis
    if (nextRetry != null && now < nextRetry) {
      NuxieLogger.debug("Still in retry backoff, skipping flush")
      return false
    }

    isCurrentlyFlushing = true
    try {
      val batchSize = min(size, maxBatchSize)
      val batch = store.peek(batchSize)
      if (batch.isEmpty()) return false

      val items = batch.map { e ->
        BatchEventItem(
          event = e.name,
          distinctId = e.distinctId,
          anonDistinctId = e.anonDistinctId,
          timestamp = e.timestamp,
          properties = e.properties,
          uuid = e.id,
          value = e.value,
          entityId = e.entityId,
        )
      }

      val response = api.sendBatch(
        BatchRequest(
          historicalMigration = null,
          batch = items,
        )
      )

      // iOS removes the whole batch even on partial failure (no per-event status).
      val ids = batch.map { it.id }
      store.delete(ids)

      retryCount = 0
      nextRetryAtEpochMillis = null

      val remaining = store.size()
      if (response.failed == 0) {
        NuxieLogger.info("Successfully delivered ${batch.size} events (queue size: $remaining)")
      } else {
        NuxieLogger.warning("Partially delivered batch: ${response.processed} processed, ${response.failed} failed")
      }

      // Flush again if still above threshold.
      flushIfOverThreshold()

      return true
    } catch (e: Exception) {
      handleFlushFailure(e)
      return false
    } finally {
      isCurrentlyFlushing = false
    }
  }

  private suspend fun handleFlushFailure(error: Exception) {
    // Permanent failure: drop all queued events for 4xx.
    if (error is NuxieNetworkError.HttpError && error.statusCode in 400..499) {
      val pending = store.peek(maxBatchSize)
      store.delete(pending.map { it.id })
      NuxieLogger.warning("Permanent failure (${error.statusCode}), dropped ${pending.size} events: ${error.message}")
      return
    }

    retryCount += 1
    if (retryCount <= maxRetries) {
      val backoffSeconds = baseRetryDelaySeconds * 2.0.pow((retryCount - 1).toDouble())
      nextRetryAtEpochMillis = clock.nowEpochMillis() + (backoffSeconds * 1000.0).toLong()
      NuxieLogger.warning(
        "Batch delivery failed (attempt $retryCount/$maxRetries), retrying in ${backoffSeconds}s: ${error.message}"
      )
      return
    }

    // Max retries exceeded: drop the next batch worth of events.
    val pending = store.peek(maxBatchSize)
    store.delete(pending.map { it.id })
    retryCount = 0
    nextRetryAtEpochMillis = null
    NuxieLogger.error("Max retries exceeded, dropped ${pending.size} events: ${error.message}")
  }
}
