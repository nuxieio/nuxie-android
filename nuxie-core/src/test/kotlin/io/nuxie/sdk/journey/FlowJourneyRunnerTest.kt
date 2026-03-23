package io.nuxie.sdk.journey

import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.campaigns.CampaignReentry
import io.nuxie.sdk.campaigns.CampaignTrigger
import io.nuxie.sdk.campaigns.EventTriggerConfig
import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.events.EventService
import io.nuxie.sdk.events.NuxieEvent
import io.nuxie.sdk.events.queue.EventQueueStore
import io.nuxie.sdk.events.queue.InMemoryEventQueueStore
import io.nuxie.sdk.events.queue.NuxieNetworkQueue
import io.nuxie.sdk.events.queue.QueuedEvent
import io.nuxie.sdk.events.store.InMemoryEventHistoryStore
import io.nuxie.sdk.features.FeatureAccess
import io.nuxie.sdk.features.FeatureCheckResult
import io.nuxie.sdk.features.FeatureService
import io.nuxie.sdk.features.FeatureType
import io.nuxie.sdk.features.PurchaseFeature
import io.nuxie.sdk.flows.BuildManifest
import io.nuxie.sdk.flows.BuildManifestFile
import io.nuxie.sdk.flows.Flow
import io.nuxie.sdk.flows.FlowBundleRef
import io.nuxie.sdk.flows.Interaction
import io.nuxie.sdk.flows.InteractionAction
import io.nuxie.sdk.flows.InteractionTrigger
import io.nuxie.sdk.flows.RemoteFlow
import io.nuxie.sdk.flows.RemoteFlowScreen
import io.nuxie.sdk.flows.VmPathRef
import io.nuxie.sdk.flows.ViewModel
import io.nuxie.sdk.flows.ViewModelInstance
import io.nuxie.sdk.flows.ViewModelProperty
import io.nuxie.sdk.flows.ViewModelPropertyType
import io.nuxie.sdk.identity.DefaultIdentityService
import io.nuxie.sdk.ir.IREnvelope
import io.nuxie.sdk.ir.IRExpr
import io.nuxie.sdk.ir.IRRuntime
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.ActiveJourney
import io.nuxie.sdk.network.models.BatchRequest
import io.nuxie.sdk.network.models.BatchResponse
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.network.models.ExperimentAssignment
import io.nuxie.sdk.network.models.ProfileResponse
import io.nuxie.sdk.profile.ProfileService
import io.nuxie.sdk.segments.SegmentService
import io.nuxie.sdk.storage.InMemoryKeyValueStore
import io.nuxie.sdk.triggers.JourneyExitReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class FlowJourneyRunnerTest {

  private class FakeApi : NuxieApiProtocol {
    var trackResponder: suspend (event: String, properties: JsonObject?) -> EventResponse = { _, _ ->
      EventResponse(status = "ok")
    }
    val batchCalls = mutableListOf<List<String>>()
    val callLog = mutableListOf<String>()

    override suspend fun fetchProfile(distinctId: String, locale: String?): ProfileResponse = ProfileResponse()

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
      callLog += "track:$event"
      return trackResponder(event, properties)
    }

    override suspend fun sendBatch(batch: BatchRequest): BatchResponse {
      batchCalls += batch.batch.map { it.event }
      callLog += batch.batch.map { "batch:${it.event}" }
      return BatchResponse(status = "ok", processed = batch.batch.size, failed = 0, total = batch.batch.size)
    }

    override suspend fun fetchFlow(flowId: String): RemoteFlow {
      throw UnsupportedOperationException("not needed in unit tests")
    }

    override suspend fun checkFeature(
      customerId: String,
      featureId: String,
      requiredBalance: Int?,
      entityId: String?,
    ): FeatureCheckResult {
      return FeatureCheckResult(
        customerId = customerId,
        allowed = false,
        featureId = featureId,
        requiredBalance = requiredBalance ?: 1,
        code = "feature_denied",
        unlimited = false,
        balance = 0,
        type = FeatureType.BOOLEAN,
      )
    }
  }

  private class FakeFeatureService : FeatureService {
    override suspend fun getCached(featureId: String, entityId: String?): FeatureAccess? = null
    override suspend fun getAllCached(): Map<String, FeatureAccess> = emptyMap()
    override suspend fun check(featureId: String, requiredBalance: Int?, entityId: String?): FeatureCheckResult {
      return FeatureCheckResult(
        customerId = "user_1",
        allowed = false,
        featureId = featureId,
        requiredBalance = requiredBalance ?: 1,
        code = "feature_denied",
        unlimited = false,
        balance = 0,
        type = FeatureType.BOOLEAN,
      )
    }

    override suspend fun checkWithCache(
      featureId: String,
      requiredBalance: Int?,
      entityId: String?,
      forceRefresh: Boolean,
    ): FeatureAccess = FeatureAccess.notFound

    override suspend fun clearCache() {}
    override suspend fun handleUserChange(fromOldDistinctId: String, toNewDistinctId: String) {}
    override suspend fun syncFeatureInfo() {}
    override suspend fun updateFromPurchase(features: List<PurchaseFeature>) {}
  }

  private class DelayingEventQueueStore(
    private val delegate: EventQueueStore = InMemoryEventQueueStore(),
    private val delayedEventNames: Set<String>,
    private val delayMillis: Long,
  ) : EventQueueStore {
    override suspend fun enqueue(event: QueuedEvent): Boolean {
      if (event.name in delayedEventNames) {
        delay(delayMillis)
      }
      return delegate.enqueue(event)
    }

    override suspend fun size(): Int = delegate.size()

    override suspend fun peek(limit: Int): List<QueuedEvent> = delegate.peek(limit)

    override suspend fun delete(ids: List<String>) {
      delegate.delete(ids)
    }

    override suspend fun reassignDistinctId(fromDistinctId: String, toDistinctId: String): Int {
      return delegate.reassignDistinctId(fromDistinctId, toDistinctId)
    }

    override suspend fun clear() {
      delegate.clear()
    }
  }

  private class ThrowingEventQueueStore(
    private val delegate: EventQueueStore = InMemoryEventQueueStore(),
    private val throwingEventNames: Set<String>,
    private val failure: Throwable = IllegalStateException("queue_failed"),
  ) : EventQueueStore {
    override suspend fun enqueue(event: QueuedEvent): Boolean {
      if (event.name in throwingEventNames) {
        throw failure
      }
      return delegate.enqueue(event)
    }

    override suspend fun size(): Int = delegate.size()

    override suspend fun peek(limit: Int): List<QueuedEvent> = delegate.peek(limit)

    override suspend fun delete(ids: List<String>) {
      delegate.delete(ids)
    }

    override suspend fun reassignDistinctId(fromDistinctId: String, toDistinctId: String): Int {
      return delegate.reassignDistinctId(fromDistinctId, toDistinctId)
    }

    override suspend fun clear() {
      delegate.clear()
    }
  }

  private class FakeProfileService(
    private var profile: ProfileResponse = ProfileResponse(),
  ) : ProfileService {
    override suspend fun fetchProfile(distinctId: String): ProfileResponse = profile
    override suspend fun getCachedProfile(distinctId: String): ProfileResponse? = profile
    override suspend fun clearCache(distinctId: String) {}
    override suspend fun clearAllCache() {}
    override suspend fun cleanupExpired(): Int = 0
    override suspend fun getCacheStats(): Map<String, Any?> = emptyMap()
    override suspend fun refetchProfile(): ProfileResponse = profile
    override suspend fun handleUserChange(fromOldDistinctId: String, toNewDistinctId: String) {}
    override suspend fun onAppBecameActive() {}
    override fun shutdown() {}
  }

  private class FakeHost : FlowJourneyHost {
    data class Message(val type: String, val payload: JsonObject, val replyTo: String?)

    val runtimeMessages: MutableList<Message> = mutableListOf()
    val shownScreens: MutableList<String> = mutableListOf()
    val purchases: MutableList<Pair<String, Any?>> = mutableListOf()
    var restores: Int = 0
    val requestNotificationJourneyIds: MutableList<String?> = mutableListOf()
    val requestPermissionRequests: MutableList<Pair<String, String?>> = mutableListOf()
    val links: MutableList<Pair<String, String?>> = mutableListOf()
    var dismissed: Int = 0
    val backs: MutableList<Pair<Int?, JsonElement?>> = mutableListOf()
    val delegateCalls: MutableList<Pair<String, Any?>> = mutableListOf()

    override suspend fun sendRuntimeMessage(type: String, payload: JsonObject, replyTo: String?) {
      runtimeMessages += Message(type, payload, replyTo)
    }

    override suspend fun showScreen(screenId: String, transition: JsonElement?) {
      shownScreens += screenId
    }

    override suspend fun performPurchase(productId: String, placementIndex: Any?) {
      purchases += productId to placementIndex
    }

    override suspend fun performRestore() {
      restores += 1
    }

    override suspend fun performRequestNotifications(journeyId: String?) {
      requestNotificationJourneyIds += journeyId
    }

    override suspend fun performRequestPermission(permissionType: String, journeyId: String?) {
      requestPermissionRequests += permissionType to journeyId
    }

    override suspend fun performOpenLink(url: String, target: String?) {
      links += url to target
    }

    override suspend fun performDismiss() {
      dismissed += 1
    }

    override suspend fun performBack(steps: Int?, transition: JsonElement?) {
      backs += steps to transition
    }

    override suspend fun callDelegate(message: String, payload: Any?) {
      delegateCalls += message to payload
    }
  }

  private data class Harness(
    val scope: CoroutineScope,
    val api: FakeApi,
    val runner: FlowJourneyRunner,
    val host: FakeHost,
    val journey: Journey,
    val eventService: EventService,
  ) {
    fun close() {
      scope.cancel()
    }
  }

  @Test
  fun runtimeReadySendsInitAndFallbackNavigate() = runBlocking {
    val harness = newHarness(interactions = emptyMap())
    try {
      val outcome = harness.runner.handleRuntimeReady()
      assertNull(outcome)
      settle()

      assertTrue(harness.host.runtimeMessages.any { it.type == "runtime/view_model_init" })
      assertTrue(harness.host.runtimeMessages.any { it.type == "runtime/navigate" })
      assertEquals(listOf("screen_1"), harness.host.shownScreens)
      assertEquals("screen_1", harness.journey.flowState.currentScreenId)
    } finally {
      harness.close()
    }
  }

  @Test
  fun runtimeReadyExecutesGlobalStartInteraction() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "int_start",
          trigger = InteractionTrigger.Start(),
          actions = listOf(InteractionAction.Navigate(screenId = "screen_2")),
          enabled = true,
        )
      )
    )
    val harness = newHarness(interactions = interactions)
    try {
      val outcome = harness.runner.handleRuntimeReady()
      assertNull(outcome)
      settle()

      assertEquals(listOf("screen_2"), harness.host.shownScreens)
      assertEquals("screen_2", harness.journey.flowState.currentScreenId)
    } finally {
      harness.close()
    }
  }

  @Test
  fun pressTriggerNavigatesToSecondScreen() = runBlocking {
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_1",
          trigger = InteractionTrigger.Press,
          actions = listOf(InteractionAction.Navigate(screenId = "screen_2")),
          enabled = true,
        )
      )
    )
    val harness = newHarness(interactions = interactions)
    try {
      harness.journey.flowState.currentScreenId = "screen_1"
      val outcome = harness.runner.dispatchTrigger(
        trigger = InteractionTrigger.Press,
        screenId = "screen_1",
        componentId = null,
        instanceId = null,
        event = null,
      )
      assertNull(outcome)
      settle()

      assertEquals(listOf("screen_2"), harness.host.shownScreens)
      assertEquals("screen_2", harness.journey.flowState.currentScreenId)
      assertEquals(listOf("screen_1"), harness.journey.flowState.navigationStack)
    } finally {
      harness.close()
    }
  }

  @Test
  fun dispatchEventTriggerIncludesGlobalInteractions() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "global_event",
          trigger = InteractionTrigger.Event(eventName = "promo_ready"),
          actions = listOf(InteractionAction.Navigate(screenId = "screen_2")),
          enabled = true,
        )
      )
    )
    val harness = newHarness(interactions = interactions)
    try {
      harness.journey.flowState.currentScreenId = "screen_1"
      val outcome = harness.runner.dispatchEventTrigger(
        NuxieEvent(name = "promo_ready", distinctId = "user_1"),
      )
      assertNull(outcome)
      settle()

      assertEquals(listOf("screen_2"), harness.host.shownScreens)
      assertEquals("screen_2", harness.journey.flowState.currentScreenId)
    } finally {
      harness.close()
    }
  }

  @Test
  fun didSetTriggerIncludesGlobalInteractions() = runBlocking {
    val path = VmPathRef(pathIds = listOf(100, 2))
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "global_did_set",
          trigger = InteractionTrigger.DidSet(path = path),
          actions = listOf(InteractionAction.Navigate(screenId = "screen_2")),
          enabled = true,
        )
      )
    )
    val harness = newHarness(interactions = interactions)
    try {
      harness.journey.flowState.currentScreenId = "screen_1"
      val outcome = harness.runner.handleDidSet(
        path = path,
        value = JsonPrimitive(1),
        source = "runtime",
        screenId = "screen_1",
        instanceId = null,
      )
      assertNull(outcome)
      settle()

      assertEquals(listOf("screen_2"), harness.host.shownScreens)
      assertEquals("screen_2", harness.journey.flowState.currentScreenId)
    } finally {
      harness.close()
    }
  }

  @Test
  fun didSetDebounceIsolatedPerInteractionAcrossScreenAndGlobalScopes() = runBlocking {
    val path = VmPathRef(pathIds = listOf(100, 2))
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "screen_did_set",
          trigger = InteractionTrigger.DidSet(path = path, debounceMs = 5),
          actions = listOf(InteractionAction.CallDelegate(message = "screen_did_set")),
          enabled = true,
        )
      ),
      "__global__" to listOf(
        Interaction(
          id = "global_did_set",
          trigger = InteractionTrigger.DidSet(path = path, debounceMs = 5),
          actions = listOf(InteractionAction.CallDelegate(message = "global_did_set")),
          enabled = true,
        )
      )
    )
    val harness = newHarness(interactions = interactions)
    try {
      harness.journey.flowState.currentScreenId = "screen_1"
      val outcome = harness.runner.handleDidSet(
        path = path,
        value = JsonPrimitive(1),
        source = "runtime",
        screenId = "screen_1",
        instanceId = null,
      )
      assertNull(outcome)
      settle()

      val messages = harness.host.delegateCalls.map { it.first }
      assertEquals(2, messages.size)
      assertTrue(messages.contains("screen_did_set"))
      assertTrue(messages.contains("global_did_set"))
    } finally {
      harness.close()
    }
  }

  @Test
  fun requestNotificationsActionContinuesAndForwardsJourneyContext() = runBlocking {
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_notifications",
          trigger = InteractionTrigger.Press,
          actions = listOf(InteractionAction.RequestNotifications),
          enabled = true,
        )
      )
    )
    val harness = newHarness(interactions = interactions)
    try {
      harness.journey.flowState.currentScreenId = "screen_1"
      val outcome = harness.runner.dispatchTrigger(
        trigger = InteractionTrigger.Press,
        screenId = "screen_1",
        componentId = null,
        instanceId = null,
        event = null,
      )
      assertNull(outcome)
      settle()

      assertEquals(listOf(harness.journey.id), harness.host.requestNotificationJourneyIds)
    } finally {
      harness.close()
    }
  }

  @Test
  fun requestPermissionActionContinuesAndForwardsJourneyContext() = runBlocking {
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_permission",
          trigger = InteractionTrigger.Press,
          actions = listOf(
            InteractionAction.RequestPermission(permissionType = "camera"),
          ),
          enabled = true,
        ),
      ),
    )
    val harness = newHarness(interactions = interactions)
    try {
      harness.journey.flowState.currentScreenId = "screen_1"
      val outcome = harness.runner.dispatchTrigger(
        trigger = InteractionTrigger.Press,
        screenId = "screen_1",
        componentId = null,
        instanceId = null,
        event = null,
      )
      assertNull(outcome)
      settle()

      assertEquals(
        listOf("camera" to harness.journey.id),
        harness.host.requestPermissionRequests,
      )
    } finally {
      harness.close()
    }
  }

  @Test
  fun goalActionTracksStandardJourneyPropertyKeys() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "goal_start",
          trigger = InteractionTrigger.Start(),
          actions = listOf(
            InteractionAction.Goal(goalId = " signup_complete ", label = " Signed Up "),
          ),
          enabled = true,
        )
      )
    )
    val harness = newHarness(interactions = interactions)
    try {
      val outcome = harness.runner.handleRuntimeReady()
      assertNull(outcome)
      settle()

      val goalEvent = harness.eventService.getEventsForUser("user_1", limit = 20)
        .last { it.name == JourneyEvents.journeyGoalHit }
      assertEquals(harness.journey.id, goalEvent.properties["journey_id"])
      assertEquals("camp_1", goalEvent.properties["campaign_id"])
      assertEquals("signup_complete", goalEvent.properties["goal_id"])
      assertEquals("Signed Up", goalEvent.properties["goal_label"])
      assertEquals(null, goalEvent.properties["journeyId"])
      assertEquals(null, goalEvent.properties["campaignId"])
      assertEquals(null, goalEvent.properties["goalId"])
      assertEquals(null, goalEvent.properties["goalLabel"])
    } finally {
      harness.close()
    }
  }

  @Test
  fun goalActionStopsExecutingAfterJourneyCompletes() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "goal_start_stop",
          trigger = InteractionTrigger.Start(),
          actions = listOf(
            InteractionAction.Goal(goalId = "signup_complete", label = "Signed Up"),
            InteractionAction.SendEvent(eventName = "should_not_run"),
          ),
          enabled = true,
        )
      )
    )
    var harness: Harness? = null
    harness = newHarness(
      interactions = interactions,
      onGoalActionHit = { _ ->
        harness?.journey?.complete(JourneyExitReason.GOAL_MET)
        GoalActionResolution()
      },
    )
    try {
      val activeHarness = harness!!
      val outcome = activeHarness.runner.handleRuntimeReady()
      assertNull(outcome)
      settle()

      val trackedNames = activeHarness.eventService.getEventsForUser("user_1", limit = 20).map { it.name }
      assertTrue(trackedNames.none { it == "should_not_run" })
    } finally {
      harness?.close()
    }
  }

  @Test
  fun goalActionTracksGoalHitBeforeSynchronousFollowUpTriggerSends() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "goal_start_ordering",
          trigger = InteractionTrigger.Start(),
          actions = listOf(
            InteractionAction.Goal(goalId = "signup_complete", label = "Signed Up"),
          ),
          enabled = true,
        )
      )
    )
    var harness: Harness? = null
    val apiCalls = mutableListOf<String>()
    harness = newHarness(
      interactions = interactions,
      onGoalActionHit = { _ ->
        apiCalls += "callback:start"
        harness!!.eventService.trackForTrigger(
          event = "after_goal",
          properties = mapOf("journey_id" to harness!!.journey.id),
        )
        GoalActionResolution(shouldExit = true)
      },
    )
    harness!!.api.trackResponder = { event, _ ->
      apiCalls += "track:$event"
      EventResponse(status = "ok")
    }
    try {
      harness!!.runner.handleRuntimeReady()
      settle()
      harness!!.eventService.flushEvents()

      assertEquals(
        listOf(
          "track:${JourneyEvents.journeyGoalHit}",
          "callback:start",
          "track:after_goal",
        ),
        apiCalls,
      )
    } finally {
      harness?.close()
    }
  }

  @Test
  fun goalActionRequeuesGoalHitWhenDirectSendThrows() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "goal_start_requeue",
          trigger = InteractionTrigger.Start(),
          actions = listOf(
            InteractionAction.Goal(goalId = "signup_complete", label = "Signed Up"),
          ),
          enabled = true,
        )
      )
    )
    var harness: Harness? = null
    harness = newHarness(
      interactions = interactions,
      onGoalActionHit = { GoalActionResolution(shouldExit = true) },
    )
    harness!!.api.trackResponder = { event, _ ->
      if (event == JourneyEvents.journeyGoalHit) {
        throw IllegalStateException("offline")
      }
      EventResponse(status = "ok")
    }

    try {
      harness!!.runner.handleRuntimeReady()
      settle()
      harness!!.eventService.flushEvents()

      assertTrue(harness!!.api.batchCalls.flatten().contains(JourneyEvents.journeyGoalHit))
      assertTrue(
        harness!!.eventService.getEventsForUser("user_1", limit = 20)
          .any { it.name == JourneyEvents.journeyGoalHit },
      )
    } finally {
      harness?.close()
    }
  }

  @Test
  fun goalActionEvaluatesUsingPreparedGoalHitIdWhenServerRewritesUuid() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "goal_start_uuid",
          trigger = InteractionTrigger.Start(),
          actions = listOf(
            InteractionAction.Goal(goalId = "signup_complete", label = "Signed Up"),
          ),
          enabled = true,
        )
      )
    )
    var goalEventId: String? = null
    var harness: Harness? = null
    harness = newHarness(
      interactions = interactions,
      onGoalActionHit = { goalEvent ->
        goalEventId = goalEvent.id
        GoalActionResolution(shouldExit = true)
      },
    )
    harness!!.api.trackResponder = { event, _ ->
      if (event == JourneyEvents.journeyGoalHit) {
        EventResponse(
          status = "ok",
          event = EventResponse.EventInfo(id = "server_goal_event", processed = true),
        )
      } else {
        EventResponse(status = "ok")
      }
    }

    try {
      harness!!.runner.handleRuntimeReady()
      settle()

      val historyGoalEvent = harness!!.eventService.getEventsForUser("user_1", limit = 20)
        .firstOrNull { it.name == JourneyEvents.journeyGoalHit }
      assertNotNull(historyGoalEvent)
      assertEquals(historyGoalEvent!!.id, goalEventId)
      assertTrue(goalEventId != "server_goal_event")
    } finally {
      harness?.close()
    }
  }

  @Test
  fun goalActionStillEvaluatesWhenFallbackQueueingThrows() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "goal_start_queue_throw",
          trigger = InteractionTrigger.Start(),
          actions = listOf(
            InteractionAction.Goal(goalId = "signup_complete", label = "Signed Up"),
          ),
          enabled = true,
        )
      )
    )
    var harness: Harness? = null
    var didHandleGoal = false
    harness = newHarness(
      interactions = interactions,
      queueStore = ThrowingEventQueueStore(
        throwingEventNames = setOf(JourneyEvents.journeyGoalHit),
      ),
      onGoalActionHit = {
        didHandleGoal = true
        harness!!.journey.complete(JourneyExitReason.GOAL_MET)
        GoalActionResolution()
      },
    )
    harness!!.api.trackResponder = { event, _ ->
      if (event == JourneyEvents.journeyGoalHit) {
        throw IllegalStateException("offline")
      }
      EventResponse(status = "ok")
    }

    try {
      harness!!.runner.handleRuntimeReady()
      settle()

      assertTrue(didHandleGoal)
      assertTrue(!harness!!.journey.status.isLive)
    } finally {
      harness?.close()
    }
  }

  @Test
  fun goalActionFlushesQueuedFallbackBeforeFollowUpTriggerSends() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "goal_start_fallback_ordering",
          trigger = InteractionTrigger.Start(),
          actions = listOf(
            InteractionAction.Goal(goalId = "signup_complete", label = "Signed Up"),
          ),
          enabled = true,
        )
      )
    )
    var harness: Harness? = null
    harness = newHarness(
      interactions = interactions,
      queueStore = DelayingEventQueueStore(
        delayedEventNames = setOf(JourneyEvents.journeyGoalHit),
        delayMillis = 75,
      ),
      onGoalActionHit = { _ ->
        harness!!.api.callLog += "callback:start"
        harness!!.eventService.trackForTrigger(
          event = "after_goal",
          properties = mapOf("journey_id" to harness!!.journey.id),
        )
        GoalActionResolution(shouldExit = true)
      },
    )
    harness!!.api.trackResponder = { event, _ ->
      if (event == JourneyEvents.journeyGoalHit) {
        throw IllegalStateException("offline")
      }
      EventResponse(status = "ok")
    }

    try {
      harness!!.runner.handleRuntimeReady()
      settle()
      harness!!.eventService.flushEvents()

      val goalHitAttemptIndex = harness!!.api.callLog.indexOf("track:${JourneyEvents.journeyGoalHit}")
      val callbackIndex = harness!!.api.callLog.indexOf("callback:start")
      val goalHitBatchIndex = harness!!.api.callLog.indexOf("batch:${JourneyEvents.journeyGoalHit}")
      val followUpTrackIndex = harness!!.api.callLog.indexOf("track:after_goal")

      assertTrue(goalHitAttemptIndex >= 0)
      assertTrue(callbackIndex >= 0)
      assertTrue(goalHitBatchIndex >= 0)
      assertTrue(followUpTrackIndex >= 0)
      assertTrue(goalHitAttemptIndex < callbackIndex)
      assertTrue(callbackIndex < goalHitBatchIndex)
      assertTrue(goalHitBatchIndex < followUpTrackIndex)
    } finally {
      harness?.close()
    }
  }

  @Test
  fun delayActionPausesAndResumes() = runBlocking {
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_1",
          trigger = InteractionTrigger.Press,
          actions = listOf(
            InteractionAction.Delay(durationMs = 10),
            InteractionAction.Navigate(screenId = "screen_2"),
          ),
          enabled = true,
        )
      )
    )
    val harness = newHarness(interactions = interactions)
    try {
      harness.journey.flowState.currentScreenId = "screen_1"
      val paused = harness.runner.dispatchTrigger(
        trigger = InteractionTrigger.Press,
        screenId = "screen_1",
        componentId = null,
        instanceId = null,
        event = null,
      ) as? FlowRunOutcome.Paused
      assertNotNull(paused)
      assertEquals(FlowPendingActionKind.DELAY, paused?.pending?.kind)

      val resumed = harness.runner.resumePendingAction(ResumeReason.TIMER, event = null)
      assertNull(resumed)
      settle()

      assertEquals(listOf("screen_2"), harness.host.shownScreens)
      assertNull(harness.journey.flowState.pendingAction)
    } finally {
      harness.close()
    }
  }

  @Test
  fun timeWindowCurrentDeviceUsesRuntimeDeviceTimezone() = runBlocking {
    val zone = ZoneId.systemDefault()
    val now = ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, zone)
    val start = now.minusHours(1)
    val end = now.plusHours(1)

    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "int_start",
          trigger = InteractionTrigger.Start(),
          actions = listOf(
            InteractionAction.TimeWindow(
              startTime = "%02d:%02d".format(start.hour, start.minute),
              endTime = "%02d:%02d".format(end.hour, end.minute),
              timezone = "__current_device__",
              successActions = listOf(InteractionAction.Navigate(screenId = "screen_2")),
            )
          ),
          enabled = true,
        )
      )
    )

    val harness = newHarness(
      interactions = interactions,
      nowEpochMillis = { now.toInstant().toEpochMilli() },
    )
    try {
      val outcome = harness.runner.handleRuntimeReady()
      assertNull(outcome)
      settle()

      assertEquals(listOf("screen_2"), harness.host.shownScreens)
      assertEquals("screen_2", harness.journey.flowState.currentScreenId)
      assertNull(harness.journey.flowState.pendingAction)
    } finally {
      harness.close()
    }
  }

  @Test
  fun timeWindowNestedDelayResumesRemainingActions() = runBlocking {
    val interactions = mapOf(
      "__global__" to listOf(
        Interaction(
          id = "int_start",
          trigger = InteractionTrigger.Start(),
          actions = listOf(
            InteractionAction.TimeWindow(
              startTime = "09:00",
              endTime = "11:00",
              timezone = "UTC",
              successActions = listOf(
                InteractionAction.Delay(durationMs = 1_000),
                InteractionAction.Navigate(screenId = "screen_2"),
              ),
            )
          ),
          enabled = true,
        )
      )
    )

    val harness = newHarness(
      interactions = interactions,
      nowEpochMillis = {
        ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
      },
    )
    try {
      val paused = harness.runner.handleRuntimeReady() as? FlowRunOutcome.Paused
      assertNotNull(paused)
      assertEquals(FlowPendingActionKind.DELAY, paused?.pending?.kind)

      val resumed = harness.runner.resumePendingAction(ResumeReason.TIMER, event = null)
      assertNull(resumed)
      settle()

      assertEquals(listOf("screen_2"), harness.host.shownScreens)
      assertNull(harness.journey.flowState.pendingAction)
    } finally {
      harness.close()
    }
  }

  @Test
  fun timeWindowNestedDelayResumesOuterActions() = runBlocking {
    val path = VmPathRef(pathIds = listOf(100, 1))
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_1",
          trigger = InteractionTrigger.Press,
          actions = listOf(
            InteractionAction.TimeWindow(
              startTime = "09:00",
              endTime = "11:00",
              timezone = "UTC",
              successActions = listOf(
                InteractionAction.Delay(durationMs = 1_000),
                InteractionAction.SetViewModel(path = path, value = JsonPrimitive(5)),
              ),
            ),
            InteractionAction.SetViewModel(path = path, value = JsonPrimitive(9)),
          ),
          enabled = true,
        )
      )
    )

    val harness = newHarness(
      interactions = interactions,
      nowEpochMillis = {
        ZonedDateTime.of(2026, 1, 1, 10, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
      },
    )
    try {
      harness.journey.flowState.currentScreenId = "screen_1"
      val paused = harness.runner.dispatchTrigger(
        trigger = InteractionTrigger.Press,
        screenId = "screen_1",
        componentId = null,
        instanceId = null,
        event = null,
      ) as? FlowRunOutcome.Paused
      assertNotNull(paused)
      assertEquals(FlowPendingActionKind.DELAY, paused?.pending?.kind)

      val resumed = harness.runner.resumePendingAction(ResumeReason.TIMER, event = null)
      assertNull(resumed)
      settle()

      assertEquals(2, harness.host.runtimeMessages.count { it.type == "runtime/view_model_patch" })
      val ref = JsonObject(
        mapOf(
          "ref" to JsonObject(mapOf("pathIds" to JsonArray(listOf(JsonPrimitive(100), JsonPrimitive(1)))))
        )
      )
      val resolved = harness.runner.resolveRuntimeValue(ref, screenId = "screen_1", instanceId = null)
      assertEquals(9, resolved)
      assertNull(harness.journey.flowState.pendingAction)
    } finally {
      harness.close()
    }
  }

  @Test
  fun waitUntilResumesOnlyWhenConditionMatches() = runBlocking {
    val waitCondition = IREnvelope(
      irVersion = 1,
      expr = IRExpr.Event(op = "eq", key = "name", value = IRExpr.String("unlock")),
    )
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_1",
          trigger = InteractionTrigger.Press,
          actions = listOf(
            InteractionAction.WaitUntil(condition = waitCondition, maxTimeMs = null),
            InteractionAction.Navigate(screenId = "screen_2"),
          ),
          enabled = true,
        )
      )
    )
    val harness = newHarness(interactions = interactions)
    try {
      harness.journey.flowState.currentScreenId = "screen_1"
      val first = harness.runner.dispatchTrigger(
        trigger = InteractionTrigger.Press,
        screenId = "screen_1",
        componentId = null,
        instanceId = null,
        event = null,
      ) as? FlowRunOutcome.Paused
      assertNotNull(first)
      assertEquals(FlowPendingActionKind.WAIT_UNTIL, first?.pending?.kind)

      val stillPaused = harness.runner.resumePendingAction(
        reason = ResumeReason.EVENT,
        event = NuxieEvent(name = "not_unlock", distinctId = "user_1"),
      ) as? FlowRunOutcome.Paused
      assertNotNull(stillPaused)
      assertEquals(FlowPendingActionKind.WAIT_UNTIL, stillPaused?.pending?.kind)

      val resumed = harness.runner.resumePendingAction(
        reason = ResumeReason.EVENT,
        event = NuxieEvent(name = "unlock", distinctId = "user_1"),
      )
      assertNull(resumed)
      settle()

      assertEquals(listOf("screen_2"), harness.host.shownScreens)
    } finally {
      harness.close()
    }
  }

  @Test
  fun setViewModelActionSendsPatchAndUpdatesValue() = runBlocking {
    val path = VmPathRef(pathIds = listOf(100, 1))
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_1",
          trigger = InteractionTrigger.Press,
          actions = listOf(
            InteractionAction.SetViewModel(path = path, value = JsonPrimitive(5)),
          ),
          enabled = true,
        )
      )
    )
    val harness = newHarness(interactions = interactions)
    try {
      harness.journey.flowState.currentScreenId = "screen_1"
      harness.runner.dispatchTrigger(
        trigger = InteractionTrigger.Press,
        screenId = "screen_1",
        componentId = null,
        instanceId = null,
        event = null,
      )
      settle()

      assertTrue(harness.host.runtimeMessages.any { it.type == "runtime/view_model_patch" })

      val ref = JsonObject(
        mapOf(
          "ref" to JsonObject(mapOf("pathIds" to JsonArray(listOf(JsonPrimitive(100), JsonPrimitive(1)))))
        )
      )
      val resolved = harness.runner.resolveRuntimeValue(ref, screenId = "screen_1", instanceId = null)
      assertEquals(5, resolved)
    } finally {
      harness.close()
    }
  }

  @Test
  fun listInsertActionSendsRuntimeListMessage() = runBlocking {
    val path = VmPathRef(pathIds = listOf(100, 3))
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_1",
          trigger = InteractionTrigger.Press,
          actions = listOf(
            InteractionAction.ListInsert(path = path, index = null, value = JsonPrimitive("x")),
          ),
          enabled = true,
        )
      )
    )
    val harness = newHarness(interactions = interactions)
    try {
      harness.journey.flowState.currentScreenId = "screen_1"
      harness.runner.dispatchTrigger(
        trigger = InteractionTrigger.Press,
        screenId = "screen_1",
        componentId = null,
        instanceId = null,
        event = null,
      )
      settle()

      assertTrue(harness.host.runtimeMessages.any { it.type == "runtime/view_model_list_insert" })

      val ref = JsonObject(
        mapOf(
          "ref" to JsonObject(mapOf("pathIds" to JsonArray(listOf(JsonPrimitive(100), JsonPrimitive(3)))))
        )
      )
      val resolved = harness.runner.resolveRuntimeValue(ref, screenId = "screen_1", instanceId = null) as? List<*>
      assertEquals(listOf("x"), resolved)
    } finally {
      harness.close()
    }
  }

  private suspend fun settle() {
    delay(40)
  }

  private fun newHarness(
    interactions: Map<String, List<Interaction>>,
    queueStore: EventQueueStore = InMemoryEventQueueStore(),
    nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    onGoalActionHit: suspend (goalEvent: NuxieEvent) -> GoalActionResolution = { GoalActionResolution() },
  ): Harness {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val api = FakeApi()
    val identity = DefaultIdentityService(InMemoryKeyValueStore())
    identity.setDistinctId("user_1")

    val session = io.nuxie.sdk.session.DefaultSessionService()
    val config = NuxieConfiguration(apiKey = "test_key")
    val historyStore = InMemoryEventHistoryStore()
    val networkQueue = NuxieNetworkQueue(
      store = queueStore,
      api = api,
      scope = scope,
      flushAt = 20,
      flushIntervalSeconds = 30,
      maxQueueSize = 1000,
      maxBatchSize = 50,
      maxRetries = 2,
      baseRetryDelaySeconds = 1,
    )
    val eventService = EventService(
      identityService = identity,
      sessionService = session,
      configuration = config,
      api = api,
      store = queueStore,
      historyStore = historyStore,
      networkQueue = networkQueue,
      scope = scope,
    )

    val irRuntime = IRRuntime()
    val segmentService = SegmentService(
      identityService = identity,
      events = eventService,
      irRuntime = irRuntime,
      scope = scope,
      enableMonitoring = false,
    )
    val featureService = FakeFeatureService()
    val profileService = FakeProfileService(
      profile = ProfileResponse(
        experiments = mapOf(
          "exp_1" to ExperimentAssignment(
            experimentKey = "exp_1",
            variantKey = "var_a",
            status = "running",
            isHoldout = false,
          )
        ),
        journeys = listOf<ActiveJourney>(),
      )
    )

    val camp = Campaign(
      id = "camp_1",
      name = "Campaign",
      flowId = "flow_1",
      flowNumber = 1,
      flowName = null,
      reentry = CampaignReentry.EveryTime,
      publishedAt = "2026-01-01T00:00:00Z",
      trigger = CampaignTrigger.Event(EventTriggerConfig(eventName = "app_opened")),
      goal = null,
      exitPolicy = null,
      conversionAnchor = null,
      campaignType = null,
    )
    val journey = Journey(campaign = camp, distinctId = "user_1").apply { status = JourneyStatus.ACTIVE }
    val flow = Flow(remoteFlow = buildFlow(interactions))
    val host = FakeHost()

    val runner = FlowJourneyRunner(
      journey = journey,
      campaign = camp,
      flow = flow,
      host = host,
      eventService = eventService,
      identityService = identity,
      segmentService = segmentService,
      featureService = featureService,
      profileService = profileService,
      irRuntime = irRuntime,
      scope = scope,
      nowEpochMillis = nowEpochMillis,
      onGoalActionHit = onGoalActionHit,
    )

    return Harness(
      scope = scope,
      api = api,
      runner = runner,
      host = host,
      journey = journey,
      eventService = eventService,
    )
  }

  private fun buildFlow(interactions: Map<String, List<Interaction>>): RemoteFlow {
    val vm = ViewModel(
      id = "vm_main",
      name = "vm_main",
      viewModelPathId = 100,
      properties = mapOf(
        "count" to ViewModelProperty(
          type = ViewModelPropertyType.NUMBER,
          propertyId = 1,
          defaultValue = JsonPrimitive(0),
        ),
        "trigger" to ViewModelProperty(
          type = ViewModelPropertyType.TRIGGER,
          propertyId = 2,
          defaultValue = JsonPrimitive(0),
        ),
        "items" to ViewModelProperty(
          type = ViewModelPropertyType.LIST,
          propertyId = 3,
          defaultValue = JsonArray(emptyList()),
          itemType = ViewModelProperty(type = ViewModelPropertyType.STRING),
        ),
      ),
    )

    return RemoteFlow(
      id = "flow_1",
      bundle = FlowBundleRef(
        url = "https://example.com/flow_1",
        manifest = BuildManifest(
          totalFiles = 1,
          totalSize = 1L,
          contentHash = "sha256:abc",
          files = listOf(
            BuildManifestFile(
              path = "index.html",
              size = 1L,
              contentType = "text/html",
            )
          ),
        ),
      ),
      fontManifest = null,
      screens = listOf(
        RemoteFlowScreen(id = "screen_1", defaultViewModelId = "vm_main", defaultInstanceId = "vm_inst_1"),
        RemoteFlowScreen(id = "screen_2", defaultViewModelId = "vm_main", defaultInstanceId = "vm_inst_1"),
      ),
      interactions = interactions,
      viewModels = listOf(vm),
      viewModelInstances = listOf(
        ViewModelInstance(
          viewModelId = "vm_main",
          instanceId = "vm_inst_1",
          name = "default",
          values = mapOf(
            "count" to JsonPrimitive(0),
            "trigger" to JsonPrimitive(0),
            "items" to JsonArray(emptyList()),
          ),
        )
      ),
      converters = null,
    )
  }
}
