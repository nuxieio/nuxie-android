package io.nuxie.sdk.profile

import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.identity.DefaultIdentityService
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.BatchRequest
import io.nuxie.sdk.network.models.BatchResponse
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.network.models.ProfileResponse
import io.nuxie.sdk.storage.InMemoryKeyValueStore
import io.nuxie.sdk.util.Clock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultProfileServiceTest {

  private class FakeClock(var now: Long) : Clock {
    override fun nowEpochMillis(): Long = now
  }

  private class FakeApi(
    private val profile: () -> ProfileResponse,
  ) : NuxieApiProtocol {
    var fetchProfileCalls: Int = 0

    override suspend fun fetchProfile(distinctId: String, locale: String?): ProfileResponse {
      fetchProfileCalls += 1
      return profile()
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

    override suspend fun fetchFlow(flowId: String): JsonObject {
      throw UnsupportedOperationException()
    }

    override suspend fun checkFeature(
      customerId: String,
      featureId: String,
      requiredBalance: Int?,
      entityId: String?,
    ) = throw UnsupportedOperationException()
  }

  @Test
  fun fetch_profile_returns_fresh_disk_cache_without_network() = runTest {
    val clock = FakeClock(now = 1_000_000L)
    val store = InMemoryCachedProfileStore()
    val distinctId = "user_1"

    val cachedResponse = ProfileResponse(
      campaigns = emptyList(),
      segments = emptyList(),
      flows = emptyList(),
    )
    store.store(
      CachedProfile(
        response = cachedResponse,
        distinctId = distinctId,
        cachedAtEpochMillis = clock.nowEpochMillis() - 60_000L,
      ),
      forKey = distinctId,
    )

    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId(distinctId) }
    val api = FakeApi { throw AssertionError("network should not be called") }
    val config = NuxieConfiguration(apiKey = "k")

    val service = DefaultProfileService(
      identityService = identity,
      api = api,
      configuration = config,
      store = store,
      scope = this,
      clock = clock,
    )

    val res = service.fetchProfile(distinctId)
    assertEquals(0, api.fetchProfileCalls)
    assertEquals(0, res.campaigns.size)
    service.shutdown()
  }

  @Test
  fun fetch_profile_returns_stale_cache_and_refreshes_in_background() = runTest {
    val clock = FakeClock(now = 1_000_000L)
    val store = InMemoryCachedProfileStore()
    val distinctId = "user_1"

    val staleResponse = ProfileResponse(
      campaigns = emptyList(),
      segments = emptyList(),
      flows = emptyList(),
      userProperties = buildJsonObject { put("k", JsonPrimitive("stale")) },
    )
    store.store(
      CachedProfile(
        response = staleResponse,
        distinctId = distinctId,
        cachedAtEpochMillis = clock.nowEpochMillis() - (10L * 60L * 1000L),
      ),
      forKey = distinctId,
    )

    val freshResponse = ProfileResponse(
      campaigns = emptyList(),
      segments = emptyList(),
      flows = emptyList(),
      userProperties = buildJsonObject { put("k", JsonPrimitive("fresh")) },
    )

    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId(distinctId) }
    val api = FakeApi { freshResponse }
    val config = NuxieConfiguration(apiKey = "k")

    val service = DefaultProfileService(
      identityService = identity,
      api = api,
      configuration = config,
      store = store,
      scope = this,
      clock = clock,
    )

    val immediate = service.fetchProfile(distinctId)
    assertEquals("stale", immediate.userProperties?.get("k")?.toString()?.trim('"'))

    // Allow background refresh (launched immediately) to run without advancing delayed timers.
    testScheduler.runCurrent()

    val cached = service.getCachedProfile(distinctId)
    assertEquals("fresh", cached?.userProperties?.get("k")?.toString()?.trim('"'))
    assertEquals(1, api.fetchProfileCalls)
    service.shutdown()
  }

  @Test
  fun fetch_profile_expired_cache_fetches_from_network() = runTest {
    val clock = FakeClock(now = 1_000_000L)
    val store = InMemoryCachedProfileStore()
    val distinctId = "user_1"

    val expiredResponse = ProfileResponse(campaigns = emptyList(), segments = emptyList(), flows = emptyList())
    store.store(
      CachedProfile(
        response = expiredResponse,
        distinctId = distinctId,
        cachedAtEpochMillis = clock.nowEpochMillis() - (25L * 60L * 60L * 1000L),
      ),
      forKey = distinctId,
    )

    val freshResponse = ProfileResponse(
      campaigns = emptyList(),
      segments = emptyList(),
      flows = emptyList(),
      userProperties = buildJsonObject { put("k", JsonPrimitive("net")) },
    )

    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId(distinctId) }
    val api = FakeApi { freshResponse }
    val config = NuxieConfiguration(apiKey = "k")

    val service = DefaultProfileService(
      identityService = identity,
      api = api,
      configuration = config,
      store = store,
      scope = this,
      clock = clock,
    )

    val res = service.fetchProfile(distinctId)
    assertEquals("net", res.userProperties?.get("k")?.toString()?.trim('"'))
    assertEquals(1, api.fetchProfileCalls)
    service.shutdown()
  }

  @Test
  fun refetch_profile_updates_identity_user_properties() = runTest {
    val clock = FakeClock(now = 1_000_000L)
    val store = InMemoryCachedProfileStore()
    val distinctId = "user_1"

    val identity = DefaultIdentityService(InMemoryKeyValueStore()).also { it.setDistinctId(distinctId) }
    val api = FakeApi {
      ProfileResponse(
        campaigns = emptyList(),
        segments = emptyList(),
        flows = emptyList(),
        userProperties = buildJsonObject { put("role", JsonPrimitive("admin")) },
      )
    }
    val config = NuxieConfiguration(apiKey = "k")

    val service = DefaultProfileService(
      identityService = identity,
      api = api,
      configuration = config,
      store = store,
      scope = this,
      clock = clock,
    )

    assertNull(identity.userProperty("role"))
    service.refetchProfile()
    assertEquals("admin", identity.userProperty("role")?.toString()?.trim('"'))
    service.shutdown()
  }
}
