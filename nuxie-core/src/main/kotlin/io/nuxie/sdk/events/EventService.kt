package io.nuxie.sdk.events

import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.events.queue.EventQueueStore
import io.nuxie.sdk.events.queue.NuxieNetworkQueue
import io.nuxie.sdk.events.queue.QueuedEvent
import io.nuxie.sdk.identity.IdentityService
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.session.SessionService
import io.nuxie.sdk.util.toJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

class EventService(
  private val identityService: IdentityService,
  private val sessionService: SessionService,
  private val configuration: NuxieConfiguration,
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
}
