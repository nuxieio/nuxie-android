package io.nuxie.sdk.flows

import io.nuxie.sdk.features.FeatureCheckResult
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.BatchRequest
import io.nuxie.sdk.network.models.BatchResponse
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.network.models.ProfileResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class FlowStoreTest {

  private class FakeApi(
    private val onFetchFlow: suspend (String) -> RemoteFlow,
  ) : NuxieApiProtocol {
    var fetchFlowCalls: Int = 0

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
      throw UnsupportedOperationException()
    }

    override suspend fun sendBatch(batch: BatchRequest): BatchResponse {
      throw UnsupportedOperationException()
    }

    override suspend fun fetchFlow(flowId: String): RemoteFlow {
      fetchFlowCalls += 1
      return onFetchFlow(flowId)
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

  private fun sampleRemoteFlow(id: String, contentHash: String = "sha256:abc"): RemoteFlow {
    val manifest = BuildManifest(
      totalFiles = 1,
      totalSize = 10,
      contentHash = contentHash,
      files = listOf(
        BuildManifestFile(path = "index.html", size = 10, contentType = "text/html"),
      ),
    )
    return RemoteFlow(
      id = id,
      bundle = FlowBundleRef(
        url = "https://example.com/flows/$id/",
        manifest = manifest,
      ),
      screens = listOf(RemoteFlowScreen(id = "screen_1")),
      interactions = emptyMap(),
      viewModels = emptyList(),
    )
  }

  @Test
  fun flow_returns_cached_on_second_call() = runTest {
    val api = FakeApi { id -> sampleRemoteFlow(id) }
    val store = FlowStore(api)

    val f1 = store.flow("flow_1")
    val f2 = store.flow("flow_1")

    assertEquals("flow_1", f1.id)
    assertEquals("flow_1", f2.id)
    assertEquals(1, api.fetchFlowCalls)
  }

  @Test
  fun flow_dedupes_concurrent_fetches() = runTest {
    val api = FakeApi { id ->
      delay(100)
      sampleRemoteFlow(id)
    }
    val store = FlowStore(api)

    val a = async { store.flow("flow_1") }
    val b = async { store.flow("flow_1") }

    assertEquals("flow_1", a.await().id)
    assertEquals("flow_1", b.await().id)
    assertEquals(1, api.fetchFlowCalls)
  }

  @Test
  fun preloadFlows_seeds_cache_without_network() = runTest {
    val api = FakeApi { throw AssertionError("network should not be called") }
    val store = FlowStore(api)

    store.preloadFlows(listOf(sampleRemoteFlow("flow_1")))

    assertEquals("flow_1", store.flow("flow_1").id)
    assertEquals(0, api.fetchFlowCalls)
  }
}

