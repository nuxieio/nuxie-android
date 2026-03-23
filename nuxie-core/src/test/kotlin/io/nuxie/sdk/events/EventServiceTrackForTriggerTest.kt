package io.nuxie.sdk.events

import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.events.queue.InMemoryEventQueueStore
import io.nuxie.sdk.events.queue.NuxieNetworkQueue
import io.nuxie.sdk.events.queue.QueuedEvent
import io.nuxie.sdk.events.store.InMemoryEventHistoryStore
import io.nuxie.sdk.features.FeatureCheckResult
import io.nuxie.sdk.flows.RemoteFlow
import io.nuxie.sdk.identity.DefaultIdentityService
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.BatchRequest
import io.nuxie.sdk.network.models.BatchResponse
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.network.models.ProfileResponse
import io.nuxie.sdk.session.DefaultSessionService
import io.nuxie.sdk.storage.InMemoryKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventServiceTrackForTriggerTest {

  private class FakeApi(
    private val onTrack: suspend (uuid: String, properties: JsonObject?) -> EventResponse,
  ) : NuxieApiProtocol {
    override suspend fun fetchProfile(distinctId: String, locale: String?): ProfileResponse {
      throw UnsupportedOperationException()
    }

    override suspend fun trackEvent(
      event: String,
      distinctId: String,
      anonDistinctId: String?,
      properties: JsonObject?,
      uuid: String,
      value: Double?,
      entityId: String?,
      timestamp: String,
    ): EventResponse {
      return onTrack(uuid, properties)
    }

    override suspend fun sendBatch(batch: BatchRequest): BatchResponse {
      throw UnsupportedOperationException()
    }

    override suspend fun fetchFlow(flowId: String): RemoteFlow {
      throw UnsupportedOperationException()
    }

    override suspend fun checkFeature(
      customerId: String,
      featureId: String,
      requiredBalance: Int?,
      entityId: String?,
    ): FeatureCheckResult {
      throw UnsupportedOperationException()
    }
  }

  @Test
  fun trackForTrigger_returns_server_event_id_and_adds_session_id() = runTest {
    var capturedUuid: String? = null
    var capturedProps: JsonObject? = null

    val api = FakeApi { uuid, props ->
      capturedUuid = uuid
      capturedProps = props
      EventResponse(
        status = "ok",
        event = EventResponse.EventInfo(id = "server_event_1", processed = true),
      )
    }

    val config = NuxieConfiguration(apiKey = "k").apply { beforeSend = null }
    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("user_1") }
    val session = DefaultSessionService()
    val store = InMemoryEventQueueStore()
    val queue = NuxieNetworkQueue(
      store = store,
      api = api,
      scope = this,
      flushAt = 999,
      flushIntervalSeconds = 999,
      maxQueueSize = 1000,
      maxBatchSize = 50,
      maxRetries = 0,
      baseRetryDelaySeconds = 1,
    )

    val service = EventService(
      identityService = identity,
      sessionService = session,
      configuration = config,
      api = api,
      store = store,
      historyStore = InMemoryEventHistoryStore(),
      networkQueue = queue,
      scope = this,
    )

    val (event, _) = service.trackForTrigger(
      event = "test_event",
      properties = mapOf("a" to 1),
      userProperties = mapOf("k" to "v"),
      userPropertiesSetOnce = mapOf("x" to 1),
    )

    assertEquals("server_event_1", event.id)
    assertEquals("test_event", event.name)
    assertEquals("user_1", event.distinctId)
    assertNotNull(event.properties["\$session_id"])

    assertTrue(!capturedUuid.isNullOrBlank())
    assertNotNull(capturedProps)
    assertNotNull(capturedProps!!["\$session_id"])
    assertNotNull(capturedProps!!["\$set"])
    assertNotNull(capturedProps!!["\$set_once"])
  }

  @Test
  fun trackForTrigger_can_skip_local_history_storage() = runTest {
    val api = FakeApi { _, _ ->
      EventResponse(
        status = "ok",
        event = EventResponse.EventInfo(id = "server_event_2", processed = true),
      )
    }

    val historyStore = InMemoryEventHistoryStore()
    val service = EventService(
      identityService = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("user_1") },
      sessionService = DefaultSessionService(),
      configuration = NuxieConfiguration(apiKey = "k"),
      api = api,
      store = InMemoryEventQueueStore(),
      historyStore = historyStore,
      networkQueue = NuxieNetworkQueue(
        store = InMemoryEventQueueStore(),
        api = api,
        scope = this,
        flushAt = 999,
        flushIntervalSeconds = 999,
        maxQueueSize = 1000,
        maxBatchSize = 50,
        maxRetries = 0,
        baseRetryDelaySeconds = 1,
      ),
      scope = this,
    )

    service.trackForTrigger(
      event = "scoped_notification",
      properties = mapOf("journey_id" to "journey_1"),
      persistToHistory = false,
    )

    assertTrue(historyStore.getEventsForUser("user_1", limit = 10).isEmpty())
  }

  @Test
  fun trackPreparedForTrigger_flushes_queued_events_before_direct_send() = runTest {
    val calls = mutableListOf<String>()
    val api = object : NuxieApiProtocol {
      override suspend fun fetchProfile(distinctId: String, locale: String?): ProfileResponse {
        throw UnsupportedOperationException()
      }

      override suspend fun trackEvent(
        event: String,
        distinctId: String,
        anonDistinctId: String?,
        properties: JsonObject?,
        uuid: String,
        value: Double?,
        entityId: String?,
        timestamp: String,
      ): EventResponse {
        calls += "track:$event"
        return EventResponse(status = "ok")
      }

      override suspend fun sendBatch(batch: BatchRequest): BatchResponse {
        calls += "batch:${batch.batch.map { it.event }.joinToString(",")}"
        return BatchResponse(status = "ok", processed = batch.batch.size, failed = 0, total = batch.batch.size)
      }

      override suspend fun fetchFlow(flowId: String): RemoteFlow {
        throw UnsupportedOperationException()
      }

      override suspend fun checkFeature(
        customerId: String,
        featureId: String,
        requiredBalance: Int?,
        entityId: String?,
      ): FeatureCheckResult {
        throw UnsupportedOperationException()
      }
    }

    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId("user_1") }
    val session = DefaultSessionService()
    val store = InMemoryEventQueueStore()
    val queue = NuxieNetworkQueue(
      store = store,
      api = api,
      scope = this,
      flushAt = 999,
      flushIntervalSeconds = 999,
      maxQueueSize = 1000,
      maxBatchSize = 50,
      maxRetries = 0,
      baseRetryDelaySeconds = 1,
    )
    val service = EventService(
      identityService = identity,
      sessionService = session,
      configuration = NuxieConfiguration(apiKey = "k"),
      api = api,
      store = store,
      historyStore = InMemoryEventHistoryStore(),
      networkQueue = queue,
      scope = this,
    )

    store.enqueue(
      QueuedEvent(
        id = "queued_1",
        name = "queued_before_goal",
        distinctId = "user_1",
        timestamp = "2026-01-01T00:00:00Z",
        properties = buildJsonObject {
          put("source", "queue")
        },
      )
    )

    val prepared = service.prepareTriggerEvent(
      event = "goal_hit",
      properties = mapOf("journey_id" to "journey_1"),
    )
    service.trackPreparedForTrigger(prepared, persistToHistory = true)

    assertEquals(listOf("batch:queued_before_goal", "track:goal_hit"), calls)
  }
}
