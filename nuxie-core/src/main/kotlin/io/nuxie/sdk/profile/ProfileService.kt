package io.nuxie.sdk.profile

import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.identity.IdentityService
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.network.models.ProfileResponse
import io.nuxie.sdk.util.Clock
import io.nuxie.sdk.util.fromJsonElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

interface ProfileService {
  suspend fun fetchProfile(distinctId: String): ProfileResponse
  suspend fun getCachedProfile(distinctId: String): ProfileResponse?
  suspend fun clearCache(distinctId: String)
  suspend fun clearAllCache()
  suspend fun cleanupExpired(): Int
  suspend fun getCacheStats(): Map<String, Any?>
  suspend fun refetchProfile(): ProfileResponse
  suspend fun handleUserChange(fromOldDistinctId: String, toNewDistinctId: String)
  suspend fun onAppBecameActive()
  fun shutdown()
}

/**
 * Cache-first profile fetcher with disk persistence and background refresh.
 *
 * Mirrors the iOS `ProfileService` caching semantics:
 * - fresh: return immediately (<= 5m)
 * - stale: return cached and refresh in background (<= 24h)
 * - expired: fetch from network
 */
class DefaultProfileService(
  private val identityService: IdentityService,
  private val api: NuxieApiProtocol,
  private val configuration: NuxieConfiguration,
  private val store: CachedProfileStore,
  private val scope: CoroutineScope,
  private val clock: Clock = Clock.system(),
) : ProfileService {
  private val mutex = Mutex()

  private var cachedProfile: CachedProfile? = null
  private var refreshJob: Job? = null

  private val freshCacheAgeMillis: Long = 5L * 60L * 1000L
  private val staleCacheAgeMillis: Long = 24L * 60L * 60L * 1000L
  private val refreshIntervalMillis: Long = 30L * 60L * 1000L

  init {
    // Best-effort: warm in-memory cache from disk for current identity.
    scope.launch {
      val distinctId = identityService.getDistinctId()
      val cached = store.retrieve(forKey = distinctId, allowStale = true)
      if (cached != null) {
        mutex.withLock { cachedProfile = cached }
        NuxieLogger.debug("Loaded profile from disk (distinctId=${NuxieLogger.logDistinctId(distinctId)})")
        val age = clock.nowEpochMillis() - cached.cachedAtEpochMillis
        if (age > freshCacheAgeMillis) {
          startRefreshTimer()
        }
      }
    }
  }

  override suspend fun fetchProfile(distinctId: String): ProfileResponse {
    val now = clock.nowEpochMillis()

    mutex.withLock {
      val cached = cachedProfile
      if (cached != null && cached.distinctId == distinctId) {
        val age = now - cached.cachedAtEpochMillis
        if (age < freshCacheAgeMillis) {
          NuxieLogger.debug("Returning fresh profile from memory (age=${age}ms)")
          return cached.response
        }
        if (age < staleCacheAgeMillis) {
          NuxieLogger.debug("Returning stale profile from memory (age=${age}ms), refreshing in background")
          scope.launch { refreshInBackground(distinctId) }
          return cached.response
        }
      }
    }

    // Try disk cache (allow stale for immediate return).
    val disk = store.retrieve(forKey = distinctId, allowStale = true)
    if (disk != null) {
      val age = now - disk.cachedAtEpochMillis
      mutex.withLock { cachedProfile = disk }
      if (age < freshCacheAgeMillis) {
        NuxieLogger.debug("Returning fresh profile from disk (age=${age}ms)")
        return disk.response
      }
      if (age < staleCacheAgeMillis) {
        NuxieLogger.debug("Returning stale profile from disk (age=${age}ms), refreshing in background")
        scope.launch { refreshInBackground(distinctId) }
        startRefreshTimer()
        return disk.response
      }
    }

    NuxieLogger.info("No valid cached profile, fetching from network")
    return refreshProfile(distinctId)
  }

  override suspend fun getCachedProfile(distinctId: String): ProfileResponse? {
    mutex.withLock {
      val cached = cachedProfile
      if (cached != null && cached.distinctId == distinctId) {
        return cached.response
      }
    }

    val disk = store.retrieve(forKey = distinctId, allowStale = true) ?: return null
    mutex.withLock { cachedProfile = disk }
    return disk.response
  }

  override suspend fun clearCache(distinctId: String) {
    mutex.withLock {
      if (cachedProfile?.distinctId == distinctId) {
        cachedProfile = null
      }
    }
    store.remove(forKey = distinctId)
  }

  override suspend fun clearAllCache() {
    mutex.withLock { cachedProfile = null }
    store.clearAll()
  }

  override suspend fun cleanupExpired(): Int {
    val removed = store.cleanupExpired(nowEpochMillis = clock.nowEpochMillis())
    if (removed > 0) {
      NuxieLogger.info("Cleaned up $removed expired profile cache entries")
    }
    return removed
  }

  override suspend fun getCacheStats(): Map<String, Any?> {
    val keys = store.getAllKeys()
    return mapOf(
      "profiles_cached" to keys.size,
      "profile_keys" to keys,
    )
  }

  override suspend fun refetchProfile(): ProfileResponse {
    val distinctId = identityService.getDistinctId()
    return refreshProfile(distinctId)
  }

  override suspend fun handleUserChange(fromOldDistinctId: String, toNewDistinctId: String) {
    // Clear in-memory if it belongs to the old user.
    mutex.withLock {
      if (cachedProfile?.distinctId == fromOldDistinctId) {
        cachedProfile = null
      }
    }

    // Attempt to load new user's profile.
    val cached = store.retrieve(forKey = toNewDistinctId, allowStale = true)
    if (cached != null) {
      mutex.withLock { cachedProfile = cached }
      val age = clock.nowEpochMillis() - cached.cachedAtEpochMillis
      if (age > freshCacheAgeMillis) {
        refreshInBackground(toNewDistinctId)
        startRefreshTimer()
      }
    } else {
      refreshInBackground(toNewDistinctId)
    }
  }

  override suspend fun onAppBecameActive() {
    // Cache-first behavior already triggers background refresh when stale.
    val distinctId = identityService.getDistinctId()
    runCatching { fetchProfile(distinctId) }
  }

  override fun shutdown() {
    refreshJob?.cancel()
    refreshJob = null
  }

  private fun effectiveLocale(): String {
    return configuration.localeIdentifier ?: Locale.getDefault().toString()
  }

  private suspend fun refreshProfile(distinctId: String): ProfileResponse {
    val locale = effectiveLocale()
    val previous = mutex.withLock { cachedProfile?.response }
    val fresh = api.fetchProfile(distinctId = distinctId, locale = locale)
    NuxieLogger.info("Profile fetch succeeded; updating cache (locale=$locale)")
    updateCache(fresh, distinctId)
    handleProfileUpdate(fresh, previous)
    startRefreshTimer()
    return fresh
  }

  private suspend fun refreshInBackground(distinctId: String) {
    runCatching {
      val locale = effectiveLocale()
      val previous = mutex.withLock { cachedProfile?.response }
      val fresh = api.fetchProfile(distinctId = distinctId, locale = locale)
      NuxieLogger.info("Background profile refresh succeeded; updating cache (locale=$locale)")
      updateCache(fresh, distinctId)
      handleProfileUpdate(fresh, previous)
      startRefreshTimer()
    }.onFailure {
      NuxieLogger.debug("Background profile refresh failed: ${it.message}", it)
    }
  }

  private suspend fun updateCache(profile: ProfileResponse, distinctId: String) {
    val cached = CachedProfile(
      response = profile,
      distinctId = distinctId,
      cachedAtEpochMillis = clock.nowEpochMillis(),
    )
    mutex.withLock { cachedProfile = cached }
    store.store(cached, forKey = distinctId)
  }

  private suspend fun handleProfileUpdate(profile: ProfileResponse, previousProfile: ProfileResponse?) {
    // Update user properties from server if present.
    val userProps = profile.userProperties
    if (userProps != null) {
      val props = userProps.mapValues { (_, v) -> fromJsonElement(v) }
      identityService.setUserProperties(props)
      NuxieLogger.info("Updated ${props.size} user properties from server")
    }

    // TODO: segmentService.updateSegments(profile.segments, distinctId)
    // TODO: journeyService.resumeFromServerState(profile.journeys, profile.campaigns)
    // TODO: flowService.prefetchFlows(profile.flows) + remove changed/removed flows

    // Suppress unused parameter warning until the above is implemented.
    previousProfile?.let { _ -> }
  }

  private fun startRefreshTimer() {
    if (refreshJob != null) return
    refreshJob = scope.launch {
      while (true) {
        delay(refreshIntervalMillis)
        val distinctId = identityService.getDistinctId()
        refreshInBackground(distinctId)
      }
    }
  }
}

