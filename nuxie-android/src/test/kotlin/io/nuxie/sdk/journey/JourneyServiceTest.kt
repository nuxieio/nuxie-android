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

  private data class Harness(
    val scope: CoroutineScope,
    val service: JourneyService,
    val broker: DefaultTriggerBroker,
    val presented: MutableList<Pair<String, String>>,
  ) {
    fun close() {
      scope.cancel()
    }
  }

  private fun newHarness(reentry: CampaignReentry): Harness {
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

    val remoteFlow = buildFlow()
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
    )

    return Harness(scope = scope, service = service, broker = broker, presented = presented)
  }

  private fun buildFlow(): RemoteFlow {
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
      interactions = emptyMap(),
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

