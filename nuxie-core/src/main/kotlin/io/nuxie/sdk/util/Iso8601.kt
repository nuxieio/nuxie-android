package io.nuxie.sdk.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Simple ISO8601 helpers.
 *
 * We intentionally avoid java.time to keep Android API 21 compatibility without requiring
 * core library desugaring configuration in the host app.
 */
object Iso8601 {
  private val formatter: ThreadLocal<SimpleDateFormat> = ThreadLocal.withInitial {
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
      timeZone = TimeZone.getTimeZone("UTC")
    }
  }

  fun now(): String = formatEpochMillis(System.currentTimeMillis())

  fun formatEpochMillis(epochMillis: Long): String = formatter.get().format(Date(epochMillis))
}

