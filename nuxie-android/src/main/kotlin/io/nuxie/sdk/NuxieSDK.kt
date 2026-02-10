package io.nuxie.sdk

import android.content.Context
import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.errors.NuxieError
import io.nuxie.sdk.identity.DefaultIdentityService
import io.nuxie.sdk.identity.IdentityService
import io.nuxie.sdk.logging.NuxieLogSink
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.session.DefaultSessionService
import io.nuxie.sdk.session.SessionService
import io.nuxie.sdk.storage.KeyValueStore
import io.nuxie.sdk.storage.SharedPreferencesKeyValueStore

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

    NuxieLogger.info("Setup completed with API key: ${NuxieLogger.logApiKey(configuration.apiKey)}")
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
