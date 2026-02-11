package io.nuxie.sdk.journey

import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.campaigns.CampaignReentry
import io.nuxie.sdk.campaigns.CampaignTrigger
import io.nuxie.sdk.campaigns.EventTriggerConfig
import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.events.EventService
import io.nuxie.sdk.events.NuxieEvent
import io.nuxie.sdk.events.queue.InMemoryEventQueueStore
import io.nuxie.sdk.events.queue.NuxieNetworkQueue
import io.nuxie.sdk.events.store.InMemoryEventHistoryStore
import io.nuxie.sdk.features.FeatureAccess
import io.nuxie.sdk.features.FeatureCheckResult
import io.nuxie.sdk.features.FeatureService
import io.nuxie.sdk.features.FeatureType
import io.nuxie.sdk.features.PurchaseFeature
import io.nuxie.sdk.flows.BuildManifest
import io.nuxie.sdk.flows.BuildManifestFile
import io.nuxie.sdk.flows.CloseReason
import io.nuxie.sdk.flows.FlowBundleRef
import io.nuxie.sdk.flows.FlowService
import io.nuxie.sdk.flows.Interaction
import io.nuxie.sdk.flows.InteractionAction
import io.nuxie.sdk.flows.InteractionTrigger
import io.nuxie.sdk.flows.RemoteFlow
import io.nuxie.sdk.flows.RemoteFlowScreen
import io.nuxie.sdk.flows.ViewModel
import io.nuxie.sdk.flows.ViewModelInstance
import io.nuxie.sdk.flows.ViewModelProperty
import io.nuxie.sdk.flows.ViewModelPropertyType
import io.nuxie.sdk.identity.DefaultIdentityService
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
import io.nuxie.sdk.triggers.DefaultTriggerBroker
import io.nuxie.sdk.triggers.SuppressReason
import io.nuxie.sdk.triggers.TriggerUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class JourneyServiceTest {

  private class FakeApi(
    private val profile: ProfileResponse,
    private val remoteFlow: RemoteFlow,
  ) : NuxieApiProtocol {
    override suspend fun fetchProfile(distinctId: String, locale: String?): ProfileResponse = profile

    override suspend fun trackEvent(
      event: String,
      distinctId: String,
      anonDistinctId: String?,
      properties: JsonObject?,
      uuid: String,
      value: Double?,
      entityId: String?,
      timestamp: String,
    ): EventResponse = EventResponse(status = "ok")

    override suspend fun sendBatch(batch: BatchRequest): BatchResponse {
      return BatchResponse(status = "ok", processed = batch.batch.size, failed = 0, total = batch.batch.size)
    }

    override suspend fun fetchFlow(flowId: String): RemoteFlow = remoteFlow

    override suspend fun checkFeature(
      customerId: String,
      featureId: String,
      requiredBalance: Int?,
      entityId: String?,
    ): FeatureCheckResult {
      return FeatureCheckResult(
        customerId = customerId,
        featureId = featureId,
        requiredBalance = requiredBalance ?: 1,
        code = "feature_denied",
        allowed = false,
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
        featureId = featureId,
        requiredBalance = requiredBalance ?: 1,
        code = "feature_denied",
        allowed = false,
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

  private class FakeProfileService(
    private val profile: ProfileResponse,
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

  @Test
  fun handleEventForTrigger_startsJourney() = runBlocking {
    val harness = newHarness(reentry = CampaignReentry.EveryTime)
    try {
      harness.service.initialize()
      val event = NuxieEvent(id = "evt_1", name = "paywall_trigger", distinctId = "user_1")
      val results = harness.service.handleEventForTrigger(event)

      val started = results.filterIsInstance<JourneyTriggerResult.Started>().firstOrNull()
      assertNotNull(started)
      assertEquals(1, harness.presented.size)
      assertEquals("flow_1", harness.presented.first().first)
      assertEquals(started!!.journey.id, harness.presented.first().second)
    } finally {
      harness.close()
    }
  }

  @Test
  fun completion_emitsJourneyUpdateToBroker() = runBlocking {
    val harness = newHarness(reentry = CampaignReentry.EveryTime)
    try {
      harness.service.initialize()
      val updates = mutableListOf<TriggerUpdate>()
      harness.broker.register("evt_origin") { updates += it }

      val event = NuxieEvent(id = "evt_origin", name = "paywall_trigger", distinctId = "user_1")
      val started = harness.service.handleEventForTrigger(event)
        .filterIsInstance<JourneyTriggerResult.Started>()
        .first()

      harness.service.handleRuntimeDismiss(started.journey.id, CloseReason.UserDismissed)
      delay(50)

      assertTrue(updates.any { it is TriggerUpdate.Journey })
      val journeyUpdate = updates.filterIsInstance<TriggerUpdate.Journey>().first().journey
      assertEquals(started.journey.id, journeyUpdate.journeyId)
      assertEquals(io.nuxie.sdk.triggers.JourneyExitReason.COMPLETED, journeyUpdate.exitReason)
    } finally {
      harness.close()
    }
  }

  @Test
  fun oneTimeReentry_suppressesSecondJourney() = runBlocking {
    val harness = newHarness(reentry = CampaignReentry.OneTime)
    try {
      harness.service.initialize()

      val firstEvent = NuxieEvent(id = "evt_1", name = "paywall_trigger", distinctId = "user_1")
      val firstStart = harness.service.handleEventForTrigger(firstEvent)
        .filterIsInstance<JourneyTriggerResult.Started>()
        .first()
      harness.service.handleRuntimeDismiss(firstStart.journey.id, CloseReason.UserDismissed)
      delay(50)

      val secondEvent = NuxieEvent(id = "evt_2", name = "paywall_trigger", distinctId = "user_1")
      val second = harness.service.handleEventForTrigger(secondEvent)
      val suppressed = second.filterIsInstance<JourneyTriggerResult.Suppressed>().firstOrNull()
      assertNotNull(suppressed)
      assertEquals(SuppressReason.ReentryLimited, suppressed?.reason)
    } finally {
      harness.close()
    }
  }

  @Test
  fun callDelegateAction_forwardsCallbackWithJourneyContext() = runBlocking {
    val delegateCalls = mutableListOf<DelegateCall>()
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_1",
          trigger = InteractionTrigger.Press,
          actions = listOf(
            InteractionAction.CallDelegate(
              message = "e2e_delegate",
              payload = JsonObject(mapOf("k" to JsonPrimitive("v"))),
            )
          ),
          enabled = true,
        )
      )
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      onCallDelegate = { journeyId, campaignId, message, payload ->
        delegateCalls += DelegateCall(
          journeyId = journeyId,
          campaignId = campaignId,
          message = message,
          payload = payload,
        )
      },
    )
    try {
      harness.service.initialize()

      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_1", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "action/press",
        payload = JsonObject(mapOf("screenId" to JsonPrimitive("screen_1"))),
        id = null,
      )
      delay(80)

      assertEquals(1, delegateCalls.size)
      val callback = delegateCalls.first()
      assertEquals(started.journey.id, callback.journeyId)
      assertEquals("camp_1", callback.campaignId)
      assertEquals("e2e_delegate", callback.message)

      val payload = callback.payload as? Map<*, *>
      assertNotNull(payload)
      assertEquals("v", payload?.get("k"))
    } finally {
      harness.close()
    }
  }

  @Test
  fun purchaseAction_forwardsCallbackWithJourneyContext() = runBlocking {
    val purchaseCalls = mutableListOf<PurchaseCall>()
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_purchase",
          trigger = InteractionTrigger.Press,
          actions = listOf(
            InteractionAction.Purchase(
              placementIndex = JsonPrimitive(2),
              productId = JsonPrimitive("prod_annual"),
            )
          ),
          enabled = true,
        )
      )
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      onPurchaseRequested = { journeyId, campaignId, screenId, productId, placementIndex ->
        purchaseCalls += PurchaseCall(
          journeyId = journeyId,
          campaignId = campaignId,
          screenId = screenId,
          productId = productId,
          placementIndex = placementIndex,
        )
      },
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_purchase", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "action/press",
        payload = JsonObject(mapOf("screenId" to JsonPrimitive("screen_1"))),
        id = null,
      )
      delay(80)

      assertEquals(1, purchaseCalls.size)
      val callback = purchaseCalls.first()
      assertEquals(started.journey.id, callback.journeyId)
      assertEquals("camp_1", callback.campaignId)
      assertEquals("screen_1", callback.screenId)
      assertEquals("prod_annual", callback.productId)
      assertEquals(2, callback.placementIndex)
    } finally {
      harness.close()
    }
  }

  @Test
  fun restoreAction_forwardsCallbackWithJourneyContext() = runBlocking {
    val restoreCalls = mutableListOf<RestoreCall>()
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_restore",
          trigger = InteractionTrigger.Press,
          actions = listOf(InteractionAction.Restore),
          enabled = true,
        )
      )
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      onRestoreRequested = { journeyId, campaignId, screenId ->
        restoreCalls += RestoreCall(
          journeyId = journeyId,
          campaignId = campaignId,
          screenId = screenId,
        )
      },
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_restore", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "action/press",
        payload = JsonObject(mapOf("screenId" to JsonPrimitive("screen_1"))),
        id = null,
      )
      delay(80)

      assertEquals(1, restoreCalls.size)
      val callback = restoreCalls.first()
      assertEquals(started.journey.id, callback.journeyId)
      assertEquals("camp_1", callback.campaignId)
      assertEquals("screen_1", callback.screenId)
    } finally {
      harness.close()
    }
  }

  @Test
  fun openLinkAction_forwardsCallbackWithJourneyContext() = runBlocking {
    val openLinkCalls = mutableListOf<OpenLinkCall>()
    val interactions = mapOf(
      "screen_1" to listOf(
        Interaction(
          id = "tap_link",
          trigger = InteractionTrigger.Press,
          actions = listOf(
            InteractionAction.OpenLink(
              url = JsonPrimitive("https://nuxie.io/pricing"),
              target = "_blank",
            )
          ),
          enabled = true,
        )
      )
    )
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      interactions = interactions,
      onOpenLinkRequested = { journeyId, campaignId, screenId, url, target ->
        openLinkCalls += OpenLinkCall(
          journeyId = journeyId,
          campaignId = campaignId,
          screenId = screenId,
          url = url,
          target = target,
        )
      },
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_link", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "runtime/ready",
        payload = JsonObject(emptyMap()),
        id = null,
      )
      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "action/press",
        payload = JsonObject(mapOf("screenId" to JsonPrimitive("screen_1"))),
        id = null,
      )
      delay(80)

      assertEquals(1, openLinkCalls.size)
      val callback = openLinkCalls.first()
      assertEquals(started.journey.id, callback.journeyId)
      assertEquals("camp_1", callback.campaignId)
      assertEquals("screen_1", callback.screenId)
      assertEquals("https://nuxie.io/pricing", callback.url)
      assertEquals("_blank", callback.target)
    } finally {
      harness.close()
    }
  }

  @Test
  fun backAction_forwardsCallbackWithJourneyContext() = runBlocking {
    val backCalls = mutableListOf<BackCall>()
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      onBackRequested = { journeyId, campaignId, screenId, steps ->
        backCalls += BackCall(
          journeyId = journeyId,
          campaignId = campaignId,
          screenId = screenId,
          steps = steps,
        )
      },
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_back", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      started.journey.flowState.currentScreenId = "screen_1"
      started.journey.flowState.navigationStack = mutableListOf("screen_prev")

      harness.service.handleRuntimeMessage(
        journeyId = started.journey.id,
        type = "action/back",
        payload = JsonObject(mapOf("steps" to JsonPrimitive(1))),
        id = null,
      )
      delay(80)

      assertEquals(1, backCalls.size)
      val callback = backCalls.first()
      assertEquals(started.journey.id, callback.journeyId)
      assertEquals("camp_1", callback.campaignId)
      assertEquals("screen_prev", callback.screenId)
      assertEquals(1, callback.steps)
    } finally {
      harness.close()
    }
  }

  @Test
  fun runtimeDismiss_forwardsDismissedCallbackWithReasonAndError() = runBlocking {
    val dismissCalls = mutableListOf<DismissCall>()
    val harness = newHarness(
      reentry = CampaignReentry.EveryTime,
      onDismissed = { journeyId, campaignId, screenId, reason, error ->
        dismissCalls += DismissCall(
          journeyId = journeyId,
          campaignId = campaignId,
          screenId = screenId,
          reason = reason,
          error = error,
        )
      },
    )

    try {
      harness.service.initialize()
      val started = harness.service.handleEventForTrigger(
        NuxieEvent(id = "evt_dismiss", name = "paywall_trigger", distinctId = "user_1")
      ).filterIsInstance<JourneyTriggerResult.Started>().first()

      started.journey.flowState.currentScreenId = "screen_1"
      harness.service.handleRuntimeDismiss(
        started.journey.id,
        CloseReason.Error(IllegalStateException("dismiss_failed")),
      )
      delay(80)

      assertEquals(1, dismissCalls.size)
      val callback = dismissCalls.first()
      assertEquals(started.journey.id, callback.journeyId)
      assertEquals("camp_1", callback.campaignId)
      assertEquals("screen_1", callback.screenId)
      assertEquals("error", callback.reason)
      assertEquals("dismiss_failed", callback.error)
    } finally {
      harness.close()
    }
  }

  @Test
  fun resumeFromServerState_restoresPausedJourney() = runBlocking {
    val harness = newHarness(reentry = CampaignReentry.EveryTime)
    try {
      harness.service.initialize()

      harness.service.resumeFromServerState(
        journeys = listOf(
          ActiveJourney(
            sessionId = "srv_journey_1",
            campaignId = "camp_1",
            currentNodeId = "screen_server",
            context = JsonObject(mapOf("server" to JsonPrimitive("state"))),
          )
        ),
        campaigns = harness.campaigns,
      )

      val active = harness.service.getActiveJourneys("user_1")
      assertEquals(1, active.size)
      assertEquals("srv_journey_1", active.first().id)
      assertEquals(JourneyStatus.PAUSED, active.first().status)
      assertEquals("screen_server", active.first().flowState.currentScreenId)
      assertEquals("state", active.first().getContext("server")?.jsonPrimitive?.contentOrNull)
    } finally {
      harness.close()
    }
  }

  private data class DelegateCall(
    val journeyId: String,
    val campaignId: String?,
    val message: String,
    val payload: Any?,
  )

  private data class PurchaseCall(
    val journeyId: String,
    val campaignId: String?,
    val screenId: String?,
    val productId: String,
    val placementIndex: Any?,
  )

  private data class RestoreCall(
    val journeyId: String,
    val campaignId: String?,
    val screenId: String?,
  )

  private data class OpenLinkCall(
    val journeyId: String,
    val campaignId: String?,
    val screenId: String?,
    val url: String,
    val target: String?,
  )

  private data class DismissCall(
    val journeyId: String,
    val campaignId: String?,
    val screenId: String?,
    val reason: String,
    val error: String?,
  )

  private data class BackCall(
    val journeyId: String,
    val campaignId: String?,
    val screenId: String?,
    val steps: Int,
  )

  private data class Harness(
    val scope: CoroutineScope,
    val service: JourneyService,
    val broker: DefaultTriggerBroker,
    val presented: MutableList<Pair<String, String>>,
    val campaigns: List<Campaign>,
  ) {
    fun close() {
      scope.cancel()
    }
  }

  private fun newHarness(
    reentry: CampaignReentry,
    interactions: Map<String, List<Interaction>> = emptyMap(),
    onCallDelegate: suspend (journeyId: String, campaignId: String?, message: String, payload: Any?) -> Unit = { _, _, _, _ -> },
    onPurchaseRequested: suspend (journeyId: String, campaignId: String?, screenId: String?, productId: String, placementIndex: Any?) -> Unit = { _, _, _, _, _ -> },
    onRestoreRequested: suspend (journeyId: String, campaignId: String?, screenId: String?) -> Unit = { _, _, _ -> },
    onOpenLinkRequested: suspend (journeyId: String, campaignId: String?, screenId: String?, url: String, target: String?) -> Unit = { _, _, _, _, _ -> },
    onDismissed: suspend (journeyId: String, campaignId: String?, screenId: String?, reason: String, error: String?) -> Unit = { _, _, _, _, _ -> },
    onBackRequested: suspend (journeyId: String, campaignId: String?, screenId: String?, steps: Int) -> Unit = { _, _, _, _ -> },
  ): Harness {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val identity = DefaultIdentityService(InMemoryKeyValueStore())
    identity.setDistinctId("user_1")

    val campaign = Campaign(
      id = "camp_1",
      name = "Campaign",
      flowId = "flow_1",
      flowNumber = 1,
      flowName = null,
      reentry = reentry,
      publishedAt = "2026-01-01T00:00:00Z",
      trigger = CampaignTrigger.Event(EventTriggerConfig(eventName = "paywall_trigger")),
      goal = null,
      exitPolicy = null,
      conversionAnchor = null,
      campaignType = null,
    )

    val remoteFlow = buildFlow(interactions)
    val profile = ProfileResponse(
      campaigns = listOf(campaign),
      segments = emptyList(),
      flows = listOf(remoteFlow),
      journeys = listOf<ActiveJourney>(),
      experiments = mapOf(
        "exp_1" to ExperimentAssignment(
          experimentKey = "exp_1",
          variantKey = "var_a",
          status = "running",
          isHoldout = false,
        )
      )
    )

    val api = FakeApi(profile = profile, remoteFlow = remoteFlow)
    val config = NuxieConfiguration("test_key")
    val queueStore = InMemoryEventQueueStore()
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
      sessionService = io.nuxie.sdk.session.DefaultSessionService(),
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
    val profileService = FakeProfileService(profile)
    val featureService = FakeFeatureService()
    val cacheDir = Files.createTempDirectory("nuxie_journey_flow_cache").toFile()
    val flowService = FlowService(
      api = api,
      configuration = config,
      scope = scope,
      cacheDirectory = cacheDir,
    )
    val journeyStore = FileJourneyStore(File(cacheDir, "journey_store"))
    val broker = DefaultTriggerBroker()
    val presented = mutableListOf<Pair<String, String>>()

    val service = JourneyService(
      scope = scope,
      configuration = config,
      identityService = identity,
      eventService = eventService,
      profileService = profileService,
      segmentService = segmentService,
      featureService = featureService,
      flowService = flowService,
      journeyStore = journeyStore,
      triggerBroker = broker,
      irRuntime = irRuntime,
      presentFlow = { flowId, journeyId ->
        presented += flowId to journeyId
        true
      },
      onCallDelegate = onCallDelegate,
      onPurchaseRequested = onPurchaseRequested,
      onRestoreRequested = onRestoreRequested,
      onOpenLinkRequested = onOpenLinkRequested,
      onDismissed = onDismissed,
      onBackRequested = onBackRequested,
    )

    return Harness(
      scope = scope,
      service = service,
      broker = broker,
      presented = presented,
      campaigns = listOf(campaign),
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
        )
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
      ),
      interactions = interactions,
      viewModels = listOf(vm),
      viewModelInstances = listOf(
        ViewModelInstance(
          viewModelId = "vm_main",
          instanceId = "vm_inst_1",
          name = "default",
          values = mapOf("count" to JsonPrimitive(0)),
        )
      ),
      converters = null,
    )
  }
}
