package io.nuxie.sdk

import android.content.Context
import androidx.room.Room
import io.nuxie.sdk.config.EventLinkingPolicy
import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.errors.NuxieError
import io.nuxie.sdk.events.EventService
import io.nuxie.sdk.events.queue.EventQueueStore
import io.nuxie.sdk.events.queue.NuxieNetworkQueue
import io.nuxie.sdk.features.DefaultFeatureService
import io.nuxie.sdk.features.FeatureAccess
import io.nuxie.sdk.features.FeatureCheckResult
import io.nuxie.sdk.features.FeatureInfo
import io.nuxie.sdk.features.FeatureService
import io.nuxie.sdk.features.FeatureUsageResult
import io.nuxie.sdk.identity.DefaultIdentityService
import io.nuxie.sdk.identity.IdentityService
import io.nuxie.sdk.logging.NuxieLogSink
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.network.NuxieApi
import io.nuxie.sdk.network.NuxieApiProtocol
import io.nuxie.sdk.profile.DefaultProfileService
import io.nuxie.sdk.profile.FileCachedProfileStore
import io.nuxie.sdk.profile.ProfileService
import io.nuxie.sdk.session.DefaultSessionService
import io.nuxie.sdk.session.SessionService
import io.nuxie.sdk.storage.KeyValueStore
import io.nuxie.sdk.storage.RoomEventQueueStore
import io.nuxie.sdk.storage.SharedPreferencesKeyValueStore
import io.nuxie.sdk.storage.db.NuxieDatabase
import io.nuxie.sdk.util.Iso8601
import io.nuxie.sdk.util.UuidV7
import io.nuxie.sdk.util.toJsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Android entrypoint.
 *
 * This will evolve to match the iOS SDK surface area.
 */
class NuxieSDK private constructor() {
  companion object {
    @Volatile private var instance: NuxieSDK? = null

    @JvmStatic
    fun shared(): NuxieSDK = instance ?: synchronized(this) {
      instance ?: NuxieSDK().also { instance = it }
    }
  }

  @Volatile
  var configuration: NuxieConfiguration? = null
    private set

  @Volatile
  var delegate: NuxieDelegate? = null

  internal var identityService: IdentityService? = null
    private set

  internal var sessionService: SessionService? = null
    private set

  internal var api: NuxieApiProtocol? = null
    private set

  internal var eventQueueStore: EventQueueStore? = null
    private set

  internal var networkQueue: NuxieNetworkQueue? = null
    private set

  internal var eventService: EventService? = null
    private set

  internal var profileService: ProfileService? = null
    private set

  internal var featureInfo: FeatureInfo? = null
    private set

  internal var featureService: FeatureService? = null
    private set

  private var scope: CoroutineScope? = null
  private var database: NuxieDatabase? = null

  val isSetup: Boolean
    get() {
      if (configuration == null) {
        NuxieLogger.warning("SDK not configured. Call setup() first.")
      }
      return configuration != null
    }

  fun setup(context: Context, configuration: NuxieConfiguration) {
    if (configuration.apiKey.isBlank()) {
      throw NuxieError.InvalidConfiguration("API key cannot be empty")
    }

    if (this.configuration != null) {
      NuxieLogger.warning("SDK already configured. Skipping setup.")
      return
    }

    this.configuration = configuration

    // Configure logging early.
    NuxieLogger.configure(
      logLevel = configuration.logLevel,
      enableConsoleLogging = configuration.enableConsoleLogging,
      enableFileLogging = configuration.enableFileLogging,
      redactSensitiveData = configuration.redactSensitiveData,
    )
    NuxieLogger.setSink(AndroidLogcatSink())

    val appContext = context.applicationContext
    val kv: KeyValueStore = SharedPreferencesKeyValueStore(appContext)
    identityService = DefaultIdentityService(kv)
    sessionService = DefaultSessionService()

    val sdkScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    scope = sdkScope

    val db = Room.databaseBuilder(appContext, NuxieDatabase::class.java, "nuxie-sdk.db")
      .fallbackToDestructiveMigration()
      .build()
    database = db

    val api = NuxieApi(
      apiKey = configuration.apiKey,
      baseUrl = configuration.apiEndpoint,
      useGzipCompression = configuration.enableCompression,
    )

    val store = RoomEventQueueStore(db.eventQueueDao())
    val queue = NuxieNetworkQueue(
      store = store,
      api = api,
      scope = sdkScope,
      flushAt = configuration.flushAt,
      flushIntervalSeconds = configuration.flushIntervalSeconds,
      maxQueueSize = configuration.maxQueueSize,
      maxBatchSize = configuration.eventBatchSize,
      maxRetries = configuration.retryCount,
      baseRetryDelaySeconds = configuration.retryDelaySeconds,
    )
    queue.start()

    val events = EventService(
      identityService = requireNotNull(identityService),
      sessionService = requireNotNull(sessionService),
      configuration = configuration,
      store = store,
      networkQueue = queue,
      scope = sdkScope,
    )

    val profileBaseDir = configuration.customStoragePath?.let { File(it) } ?: File(appContext.cacheDir, "nuxie")
    val profileStore = FileCachedProfileStore(
      directory = File(profileBaseDir, "profiles"),
      ttlMillis = 24L * 60L * 60L * 1000L,
    )
    val profile = DefaultProfileService(
      identityService = requireNotNull(identityService),
      api = api,
      configuration = configuration,
      store = profileStore,
      scope = sdkScope,
    )

    val info = FeatureInfo()
    val features = DefaultFeatureService(
      api = api,
      identityService = requireNotNull(identityService),
      profileService = profile,
      configuration = configuration,
      featureInfo = info,
    )

    // Forward FeatureInfo changes to delegate on the main thread (parity with iOS @MainActor).
    info.onFeatureChange = { featureId, oldValue, newValue ->
      sdkScope.launch(Dispatchers.Main) {
        delegate?.featureAccessDidChange(featureId, from = oldValue, to = newValue)
      }
    }

    // Prefetch initial profile and sync features (best-effort).
    sdkScope.launch {
      runCatching { profile.refetchProfile() }
      runCatching { features.syncFeatureInfo() }
    }

    this.api = api
    this.eventQueueStore = store
    this.networkQueue = queue
    this.eventService = events
    this.profileService = profile
    this.featureInfo = info
    this.featureService = features

    NuxieLogger.info("Setup completed with API key: ${NuxieLogger.logApiKey(configuration.apiKey)}")
  }

  fun identify(
    distinctId: String,
    userProperties: Map<String, Any?>? = null,
    userPropertiesSetOnce: Map<String, Any?>? = null,
  ) {
    if (!isSetup) return
    val config = configuration ?: return
    val identity = identityService ?: return
    val events = eventService ?: return

    val oldDistinctId = identity.getDistinctId()
    val wasIdentified = identity.isIdentified
    val hasDifferentDistinctId = distinctId != oldDistinctId

    identity.setDistinctId(distinctId)
    sessionService?.startSession()

    val currentDistinctId = identity.getDistinctId()

    if (hasDifferentDistinctId) {
      scope?.launch {
        profileService?.handleUserChange(fromOldDistinctId = oldDistinctId, toNewDistinctId = currentDistinctId)
        featureService?.handleUserChange(fromOldDistinctId = oldDistinctId, toNewDistinctId = currentDistinctId)
      }
    }

    if (
      !wasIdentified &&
      hasDifferentDistinctId &&
      config.eventLinkingPolicy == EventLinkingPolicy.MIGRATE_ON_IDENTIFY
    ) {
      scope?.launch {
        val reassigned = runCatching { events.reassignEvents(oldDistinctId, currentDistinctId) }.getOrDefault(0)
        if (reassigned > 0) {
          NuxieLogger.info("Migrated $reassigned anonymous events to identified user: ${NuxieLogger.logDistinctId(currentDistinctId)}")
        }
      }
    }

    val props = buildMap<String, Any?> {
      put("distinct_id", distinctId)
      if (!wasIdentified && hasDifferentDistinctId) {
        put("\$anon_distinct_id", oldDistinctId)
      }
    }

    events.track(
      "\$identify",
      properties = props,
      userProperties = userProperties,
      userPropertiesSetOnce = userPropertiesSetOnce,
    )
  }

  fun reset(keepAnonymousId: Boolean = true) {
    if (!isSetup) return
    val identity = identityService ?: return
    val prevDistinctId = identity.getDistinctId()

    identity.reset(keepAnonymousId)
    sessionService?.resetSession()

    val newDistinctId = identity.getDistinctId()
    scope?.launch {
      profileService?.clearCache(prevDistinctId)
      profileService?.handleUserChange(fromOldDistinctId = prevDistinctId, toNewDistinctId = newDistinctId)
      featureService?.handleUserChange(fromOldDistinctId = prevDistinctId, toNewDistinctId = newDistinctId)
    }
  }

  fun getDistinctId(): String = identityService?.getDistinctId().orEmpty()

  fun getAnonymousId(): String = identityService?.getAnonymousId().orEmpty()

  val isIdentified: Boolean
    get() = identityService?.isIdentified == true

  suspend fun flushEvents(): Boolean = networkQueue?.flush(forceSend = true) ?: false

  suspend fun getQueuedEventCount(): Int = eventQueueStore?.size() ?: 0

  suspend fun pauseEventQueue() {
    networkQueue?.pause()
  }

  suspend fun resumeEventQueue() {
    networkQueue?.resume()
  }

  suspend fun shutdown() {
    // Best-effort cleanup.
    networkQueue?.stop()
    profileService?.shutdown()
    scope?.cancel()
    database?.close()
    database = null

    eventService = null
    networkQueue = null
    eventQueueStore = null
    api = null
    sessionService = null
    identityService = null
    featureService = null
    featureInfo = null
    profileService = null
    delegate = null
    configuration = null
  }

  val version: String
    get() = NuxieVersion.current

  val features: FeatureInfo
    get() = featureInfo ?: throw NuxieError.NotConfigured

  suspend fun refreshProfile(): io.nuxie.sdk.network.models.ProfileResponse {
    if (!isSetup) throw NuxieError.NotConfigured
    val profile = profileService ?: throw NuxieError.NotConfigured
    val features = featureService ?: throw NuxieError.NotConfigured
    val res = profile.refetchProfile()
    // Profile may contain updated features.
    features.syncFeatureInfo()
    return res
  }

  suspend fun hasFeature(featureId: String): FeatureAccess {
    if (!isSetup) throw NuxieError.NotConfigured
    val svc = featureService ?: throw NuxieError.NotConfigured
    return svc.checkWithCache(featureId = featureId, requiredBalance = null, entityId = null, forceRefresh = false)
  }

  suspend fun hasFeature(featureId: String, requiredBalance: Int, entityId: String? = null): FeatureAccess {
    if (!isSetup) throw NuxieError.NotConfigured
    val svc = featureService ?: throw NuxieError.NotConfigured
    return svc.checkWithCache(featureId = featureId, requiredBalance = requiredBalance, entityId = entityId, forceRefresh = false)
  }

  suspend fun getCachedFeature(featureId: String, entityId: String? = null): FeatureAccess? {
    if (!isSetup) return null
    return featureService?.getCached(featureId = featureId, entityId = entityId)
  }

  suspend fun checkFeature(featureId: String, requiredBalance: Int? = null, entityId: String? = null): FeatureCheckResult {
    if (!isSetup) throw NuxieError.NotConfigured
    val svc = featureService ?: throw NuxieError.NotConfigured
    return runCatching { svc.check(featureId = featureId, requiredBalance = requiredBalance, entityId = entityId) }
      .getOrElse { throw NuxieError.FeatureCheckFailed(featureId, it) }
  }

  suspend fun refreshFeature(featureId: String, requiredBalance: Int? = null, entityId: String? = null): FeatureCheckResult {
    return checkFeature(featureId = featureId, requiredBalance = requiredBalance, entityId = entityId)
  }

  fun useFeature(
    featureId: String,
    amount: Double = 1.0,
    entityId: String? = null,
    metadata: Map<String, Any?>? = null,
  ) {
    if (!isSetup) {
      NuxieLogger.warning("useFeature called before SDK setup")
      return
    }
    val events = eventService ?: return

    val props = buildMap<String, Any?> {
      put("feature_extId", featureId)
      put("amount", amount)
      put("value", amount)
      if (metadata != null) put("metadata", metadata)
      if (entityId != null) put("entityId", entityId)
    }

    events.track("\$feature_used", properties = props)

    // Optimistic local balance decrement for instant UI feedback.
    scope?.launch(Dispatchers.Main) {
      featureInfo?.decrementBalance(featureId, amount.toInt())
    }
  }

  suspend fun useFeatureAndWait(
    featureId: String,
    amount: Double = 1.0,
    entityId: String? = null,
    setUsage: Boolean = false,
    metadata: Map<String, Any?>? = null,
  ): FeatureUsageResult {
    if (!isSetup) throw NuxieError.NotConfigured
    val identity = identityService ?: throw NuxieError.NotConfigured
    val api = api ?: throw NuxieError.NotConfigured

    val distinctId = identity.getDistinctId()
    val props = buildMap<String, Any?> {
      put("feature_extId", featureId)
      if (setUsage) put("setUsage", true)
      if (metadata != null) put("metadata", metadata)
    }

    val response = api.trackEvent(
      event = "\$feature_used",
      distinctId = distinctId,
      anonDistinctId = null,
      properties = toJsonObject(props),
      uuid = UuidV7.generateString(),
      value = amount,
      entityId = entityId,
      timestamp = Iso8601.now(),
    )

    response.usage?.remaining?.let { remaining ->
      scope?.launch(Dispatchers.Main) {
        featureInfo?.setBalance(featureId, balance = remaining.toInt())
      }
    }

    return FeatureUsageResult(
      success = response.status == "ok" || response.status == "success",
      featureId = featureId,
      amountUsed = amount,
      message = response.message,
      usage = response.usage?.let { usage ->
        FeatureUsageResult.UsageInfo(
          current = usage.current,
          limit = usage.limit,
          remaining = usage.remaining,
        )
      },
    )
  }
}

private class AndroidLogcatSink : NuxieLogSink {
  override fun log(level: io.nuxie.sdk.config.LogLevel, message: String, throwable: Throwable?) {
    // Avoid depending on Android's Log in unit tests; this class only runs on Android.
    val tag = "NuxieSDK"
    val msg = if (throwable != null) "$message\n${throwable.stackTraceToString()}" else message
    when (level) {
      io.nuxie.sdk.config.LogLevel.VERBOSE -> android.util.Log.v(tag, msg)
      io.nuxie.sdk.config.LogLevel.DEBUG -> android.util.Log.d(tag, msg)
      io.nuxie.sdk.config.LogLevel.INFO -> android.util.Log.i(tag, msg)
      io.nuxie.sdk.config.LogLevel.WARNING -> android.util.Log.w(tag, msg)
      io.nuxie.sdk.config.LogLevel.ERROR -> android.util.Log.e(tag, msg)
      io.nuxie.sdk.config.LogLevel.NONE -> Unit
    }
  }
}
