package io.nuxie.sdk.logging

import io.nuxie.sdk.config.LogLevel
import java.util.concurrent.atomic.AtomicReference

/**
 * Centralized logging system for the Nuxie SDK.
 *
 * Defaults to stdout, but Android sets a Logcat sink during `setup()`.
 */
object NuxieLogger {
  data class Config(
    val logLevel: LogLevel,
    val enableConsoleLogging: Boolean,
    val enableFileLogging: Boolean,
    val redactSensitiveData: Boolean,
  )

  @Volatile private var config: Config = Config(
    logLevel = LogLevel.DEBUG,
    enableConsoleLogging = true,
    enableFileLogging = false,
    redactSensitiveData = true,
  )

  private val sinkRef = AtomicReference<NuxieLogSink>(StdoutLogSink())

  fun configure(
    logLevel: LogLevel,
    enableConsoleLogging: Boolean,
    enableFileLogging: Boolean,
    redactSensitiveData: Boolean,
  ) {
    config = Config(
      logLevel = logLevel,
      enableConsoleLogging = enableConsoleLogging,
      enableFileLogging = enableFileLogging,
      redactSensitiveData = redactSensitiveData,
    )
  }

  fun setSink(sink: NuxieLogSink) {
    sinkRef.set(sink)
  }

  fun verbose(message: String, throwable: Throwable? = null) = log(LogLevel.VERBOSE, message, throwable)
  fun debug(message: String, throwable: Throwable? = null) = log(LogLevel.DEBUG, message, throwable)
  fun info(message: String, throwable: Throwable? = null) = log(LogLevel.INFO, message, throwable)
  fun warning(message: String, throwable: Throwable? = null) = log(LogLevel.WARNING, message, throwable)
  fun error(message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, message, throwable)

  fun log(level: LogLevel, message: String, throwable: Throwable? = null) {
    val cfg = config
    if (level.priority < cfg.logLevel.priority) return
    if (!cfg.enableConsoleLogging && !cfg.enableFileLogging) return

    val formatted = "[Nuxie] [${level.name}] $message"
    sinkRef.get().log(level, formatted, throwable)
  }

  fun logApiKey(apiKey: String): String {
    if (!config.redactSensitiveData) return apiKey
    return if (apiKey.length > 8) "${apiKey.take(4)}...${apiKey.takeLast(4)}" else "***"
  }

  fun logDistinctId(distinctId: String?): String {
    if (distinctId == null) return "nil"
    if (!config.redactSensitiveData) return distinctId
    return if (distinctId.length > 8) "${distinctId.take(3)}...${distinctId.takeLast(3)}" else "***"
  }
}

fun interface NuxieLogSink {
  fun log(level: LogLevel, message: String, throwable: Throwable?)
}

private class StdoutLogSink : NuxieLogSink {
  override fun log(level: LogLevel, message: String, throwable: Throwable?) {
    if (throwable != null) {
      println("$message\n${throwable.stackTraceToString()}")
    } else {
      println(message)
    }
  }
}
