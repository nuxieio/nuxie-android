package io.nuxie.sdk

import android.content.Context
import androidx.room.Room
import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.errors.NuxieError
import io.nuxie.sdk.events.EventService
import io.nuxie.sdk.events.queue.EventQueueStore
import io.nuxie.sdk.events.queue.NuxieNetworkQueue
import io.nuxie.sdk.identity.DefaultIdentityService
import io.nuxie.sdk.identity.IdentityService
import io.nuxie.sdk.logging.NuxieLogSink
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.network.NuxieApi
import io.nuxie.sdk.session.DefaultSessionService
import io.nuxie.sdk.session.SessionService
import io.nuxie.sdk.storage.KeyValueStore
import io.nuxie.sdk.storage.RoomEventQueueStore
import io.nuxie.sdk.storage.SharedPreferencesKeyValueStore
import io.nuxie.sdk.storage.db.NuxieDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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

  internal var identityService: IdentityService? = null
    private set

  internal var sessionService: SessionService? = null
    private set

  internal var api: NuxieApi? = null
    private set

  internal var eventQueueStore: EventQueueStore? = null
    private set

  internal var networkQueue: NuxieNetworkQueue? = null
    private set

  internal var eventService: EventService? = null
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

    this.api = api
    this.eventQueueStore = store
    this.networkQueue = queue
    this.eventService = events

    NuxieLogger.info("Setup completed with API key: ${NuxieLogger.logApiKey(configuration.apiKey)}")
  }

  fun identify(
    distinctId: String,
    userProperties: Map<String, Any?>? = null,
    userPropertiesSetOnce: Map<String, Any?>? = null,
  ) {
    if (!isSetup) return
    val identity = identityService ?: return
    val events = eventService ?: return

    val oldDistinctId = identity.getDistinctId()
    val wasIdentified = identity.isIdentified
    val hasDifferentDistinctId = distinctId != oldDistinctId

    identity.setDistinctId(distinctId)
    sessionService?.startSession()

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
    identityService?.reset(keepAnonymousId)
    sessionService?.resetSession()
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
    scope?.cancel()
    database?.close()
    database = null

    eventService = null
    networkQueue = null
    eventQueueStore = null
    api = null
    sessionService = null
    identityService = null
    configuration = null
  }

  val version: String
    get() = NuxieVersion.current
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
