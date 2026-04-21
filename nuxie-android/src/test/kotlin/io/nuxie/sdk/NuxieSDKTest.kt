package io.nuxie.sdk

import android.os.Looper
import io.nuxie.sdk.campaigns.Campaign
import io.nuxie.sdk.campaigns.CampaignReentry
import io.nuxie.sdk.campaigns.CampaignTrigger
import io.nuxie.sdk.campaigns.EventTriggerConfig
import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.events.EventService
import io.nuxie.sdk.events.queue.InMemoryEventQueueStore
import io.nuxie.sdk.events.queue.NuxieNetworkQueue
import io.nuxie.sdk.events.store.InMemoryEventHistoryStore
import io.nuxie.sdk.features.FeatureAccess
import io.nuxie.sdk.features.FeatureCheckResult
import io.nuxie.sdk.features.FeatureInfo
import io.nuxie.sdk.features.FeatureService
import io.nuxie.sdk.features.FeatureType
import io.nuxie.sdk.features.PurchaseFeature
import io.nuxie.sdk.flows.BuildManifest
import io.nuxie.sdk.flows.BuildManifestFile
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
import io.nuxie.sdk.journey.FileJourneyStore
import io.nuxie.sdk.journey.JourneyService
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.ActiveJourney
import io.nuxie.sdk.network.models.BatchRequest
import io.nuxie.sdk.network.models.BatchResponse
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.network.models.ProfileResponse
import io.nuxie.sdk.profile.ProfileService
import io.nuxie.sdk.segments.SegmentService
import io.nuxie.sdk.session.DefaultSessionService
import io.nuxie.sdk.storage.InMemoryKeyValueStore
import io.nuxie.sdk.triggers.DefaultTriggerBroker
import io.nuxie.sdk.triggers.TriggerDecision
import io.nuxie.sdk.triggers.TriggerUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class NuxieSDKTest {
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
    ): EventResponse {
      val gatePayload = if (event == "paywall_trigger") {
        JsonObject(
          mapOf(
            "gate" to JsonObject(
              mapOf("decision" to JsonPrimitive("allow"))
            )
          )
        )
      } else {
        null
      }
      return EventResponse(
        status = "ok",
        payload = gatePayload,
        event = EventResponse.EventInfo(id = uuid, processed = true),
      )
    }

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

  private class FakeFeatureService : FeatureService {
    override suspend fun getCached(featureId: String, entityId: String?): FeatureAccess? = null
    override suspend fun getAllCached(): Map<String, FeatureAccess> = emptyMap()
    override suspend fun check(featureId: String, requiredBalance: Int?, entityId: String?): FeatureCheckResult {
      return FeatureCheckResult(
        customerId = "sdk_user",
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

  private data class Harness(
    val sdk: NuxieSDK,
    val scope: CoroutineScope,
  ) {
    suspend fun close() {
      sdk.shutdown()
      scope.cancel()
    }
  }

  @After
  fun tearDown() = runBlocking {
    NuxieSDK.shared().shutdown()
  }

  @Test
  fun version_matches_core() {
    assertEquals(NuxieVersion.current, NuxieSDK.shared().version)
  }

  @Test
  fun trigger_emitsGateDecisionAfterJourneyDecision() = runBlocking {
    val harness = newHarness()
    try {
      val updates = mutableListOf<TriggerUpdate>()

      harness.sdk.trigger("paywall_trigger") { update ->
        updates += update
      }

      fun sawJourneyAndGate(): Boolean {
        return updates.any { it.decisionOrNull() is TriggerDecision.JourneyStarted } &&
          updates.any { it.decisionOrNull() == TriggerDecision.AllowedImmediate }
      }

      var attempts = 0
      while (!sawJourneyAndGate() && attempts < 20) {
        shadowOf(Looper.getMainLooper()).idle()
        attempts += 1
        delay(50)
      }
      shadowOf(Looper.getMainLooper()).idle()

      assertTrue(updates.any { it.decisionOrNull() is TriggerDecision.JourneyStarted })
      assertTrue(updates.any { it.decisionOrNull() == TriggerDecision.AllowedImmediate })
    } finally {
      harness.close()
    }
  }

  private fun TriggerUpdate.decisionOrNull(): TriggerDecision? {
    return (this as? TriggerUpdate.Decision)?.decision
  }

  private suspend fun newHarness(): Harness {
    NuxieSDK.shared().shutdown()

    val sdk = NuxieSDK.shared()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val identity = DefaultIdentityService(InMemoryKeyValueStore())
    identity.setDistinctId("sdk_user")

    val config = NuxieConfiguration("test_key")
    val campaign = Campaign(
      id = "sdk_camp",
      name = "Campaign",
      flowId = "sdk_flow",
      flowNumber = 1,
      flowName = null,
      reentry = CampaignReentry.EveryTime,
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
      experiments = emptyMap(),
    )
    val api = FakeApi(profile = profile, remoteFlow = remoteFlow)
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
      sessionService = DefaultSessionService(),
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
    val cacheDir = Files.createTempDirectory("nuxie_sdk_trigger_test").toFile()
    val broker = DefaultTriggerBroker()
    val flowService = FlowService(
      api = api,
      configuration = config,
      scope = scope,
      cacheDirectory = cacheDir,
    )
    val journeyService = JourneyService(
      scope = scope,
      configuration = config,
      identityService = identity,
      eventService = eventService,
      profileService = FakeProfileService(profile),
      segmentService = segmentService,
      featureService = featureService,
      flowService = flowService,
      journeyStore = FileJourneyStore(File(cacheDir, "journeys")),
      triggerBroker = broker,
      irRuntime = irRuntime,
      presentFlow = { _, _ -> true },
    )

    sdk.setPrivateField("configuration", config)
    sdk.setPrivateField("eventService", eventService)
    sdk.setPrivateField("featureInfo", FeatureInfo())
    sdk.setPrivateField("featureService", featureService)
    sdk.setPrivateField("journeyService", journeyService)
    sdk.setPrivateField("triggerBroker", broker)
    sdk.setPrivateField("scope", scope)

    return Harness(sdk = sdk, scope = scope)
  }

  private fun NuxieSDK.setPrivateField(name: String, value: Any?) {
    val field = NuxieSDK::class.java.getDeclaredField(name)
    field.isAccessible = true
    field.set(this, value)
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
      id = "sdk_flow",
      bundle = FlowBundleRef(
        url = "https://example.com/sdk_flow",
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
