package io.nuxie.sdk.events

import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.events.queue.EventQueueStore
import io.nuxie.sdk.events.queue.NuxieNetworkQueue
import io.nuxie.sdk.events.queue.QueuedEvent
import io.nuxie.sdk.identity.IdentityService
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.session.SessionService
import io.nuxie.sdk.util.toJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

class EventService(
  private val identityService: IdentityService,
  private val sessionService: SessionService,
  private val configuration: NuxieConfiguration,
  private val api: NuxieApiProtocol,
  private val store: EventQueueStore,
  private val networkQueue: NuxieNetworkQueue,
  private val scope: CoroutineScope,
) {
  fun track(
    event: String,
    properties: Map<String, Any?>? = null,
    userProperties: Map<String, Any?>? = null,
    userPropertiesSetOnce: Map<String, Any?>? = null,
    value: Double? = null,
    entityId: String? = null,
  ) {
    if (event.isBlank()) {
      NuxieLogger.warning("Event name cannot be empty")
      return
    }

    // Snapshot id NOW to preserve pre/post identify semantics (parity with iOS).
    val distinctIdSnapshot = identityService.getDistinctId()

    val mergedProps = buildMap<String, Any?> {
      putAll(properties ?: emptyMap())
      if (userProperties != null) put("\$set", userProperties)
      if (userPropertiesSetOnce != null) put("\$set_once", userPropertiesSetOnce)
      if (value != null) put("value", value)
      if (entityId != null) put("entityId", entityId)

      if (!containsKey("\$session_id")) {
        val sessionId = sessionService.getSessionId(readOnly = false)
        if (sessionId != null) {
          put("\$session_id", sessionId)
          sessionService.touchSession()
        }
      }
    }.let { props ->
      configuration.propertiesSanitizer?.sanitize(props) ?: props
    }

    val nuxieEvent = NuxieEvent(
      name = event,
      distinctId = distinctIdSnapshot,
      properties = mergedProps,
    )

    val finalEvent = if (configuration.beforeSend != null) {
      configuration.beforeSend?.invoke(nuxieEvent) ?: return
    } else {
      nuxieEvent
    }

    val propsJson: JsonObject = toJsonObject(finalEvent.properties)
    val anonDistinctId = (finalEvent.properties["\$anon_distinct_id"] as? String)

    val queued = QueuedEvent(
      id = finalEvent.id,
      name = finalEvent.name,
      distinctId = finalEvent.distinctId,
      anonDistinctId = anonDistinctId,
      timestamp = finalEvent.timestamp,
      properties = propsJson,
      value = (finalEvent.properties["value"] as? Number)?.toDouble(),
      entityId = finalEvent.properties["entityId"] as? String,
    )

    scope.launch {
      val ok = networkQueue.enqueue(queued)
      if (!ok) {
        NuxieLogger.warning("Dropped event due to queue limits: ${queued.name}")
      }
    }
  }

  suspend fun flushEvents(): Boolean = networkQueue.flush(forceSend = true)

  suspend fun getQueuedEventCount(): Int = store.size()

  suspend fun pauseEventQueue() = networkQueue.pause()

  suspend fun resumeEventQueue() = networkQueue.resume()

  /**
   * Reassign queued events from one distinctId to another.
   *
   * This is used for anonymous -> identified linking when `eventLinkingPolicy` is `migrateOnIdentify`.
   */
  suspend fun reassignEvents(fromDistinctId: String, toDistinctId: String): Int {
    return store.reassignDistinctId(fromDistinctId = fromDistinctId, toDistinctId = toDistinctId)
  }

  /**
   * Track an event synchronously and return the enriched event plus server response.
   *
   * Mirrors iOS `EventService.trackForTrigger(...)`:
   * - flushes pending batch queue first (best-effort)
   * - calls `POST /event` directly
   *
   * Note: Android does not yet persist a separate event history store, so this currently
   * does not record a local history copy.
   */
  suspend fun trackForTrigger(
    event: String,
    properties: Map<String, Any?>? = null,
    userProperties: Map<String, Any?>? = null,
    userPropertiesSetOnce: Map<String, Any?>? = null,
  ): Pair<NuxieEvent, EventResponse> {
    if (event.isBlank()) {
      throw IllegalArgumentException("Event name cannot be empty")
    }

    // Ensure any queued events are delivered first so the trigger call observes a consistent order.
    runCatching { networkQueue.flush(forceSend = true) }

    val distinctId = identityService.getDistinctId()

    val mergedProps = buildMap<String, Any?> {
      putAll(properties ?: emptyMap())
      if (userProperties != null) put("\$set", userProperties)
      if (userPropertiesSetOnce != null) put("\$set_once", userPropertiesSetOnce)

      if (!containsKey("\$session_id")) {
        val sessionId = sessionService.getSessionId(readOnly = false)
        if (sessionId != null) {
          put("\$session_id", sessionId)
          sessionService.touchSession()
        }
      }
    }.let { props ->
      configuration.propertiesSanitizer?.sanitize(props) ?: props
    }

    val nuxieEvent = NuxieEvent(
      name = event,
      distinctId = distinctId,
      properties = mergedProps,
    )

    val finalEvent = if (configuration.beforeSend != null) {
      configuration.beforeSend?.invoke(nuxieEvent) ?: throw IllegalStateException("Event dropped by beforeSend")
    } else {
      nuxieEvent
    }

    val propsJson: JsonObject = toJsonObject(finalEvent.properties)
    val anonDistinctId = (finalEvent.properties["\$anon_distinct_id"] as? String)

    val response = api.trackEvent(
      event = finalEvent.name,
      distinctId = finalEvent.distinctId,
      anonDistinctId = anonDistinctId,
      properties = propsJson,
      uuid = finalEvent.id,
      value = finalEvent.properties["value"] as? Double,
      entityId = finalEvent.properties["entityId"] as? String,
      timestamp = finalEvent.timestamp,
    )

    val eventId = response.event?.id ?: finalEvent.id
    val enriched = NuxieEvent(
      id = eventId,
      name = finalEvent.name,
      distinctId = finalEvent.distinctId,
      properties = finalEvent.properties,
      timestamp = finalEvent.timestamp,
    )

    return enriched to response
  }
}
