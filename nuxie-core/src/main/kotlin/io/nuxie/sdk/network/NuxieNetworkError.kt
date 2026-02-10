package io.nuxie.sdk.network

/**
 * Network-layer errors. Mirrors iOS `NuxieNetworkError`.
 */
sealed class NuxieNetworkError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
  class InvalidUrl(url: String) : NuxieNetworkError("Invalid URL: $url")
  data object InvalidResponse : NuxieNetworkError("Invalid response received")
  class HttpError(val statusCode: Int, message: String) : NuxieNetworkError("HTTP $statusCode: $message")
  class DecodingError(cause: Throwable) : NuxieNetworkError("Failed to decode response: ${cause.message}", cause)
  class EncodingError(cause: Throwable) : NuxieNetworkError("Failed to encode request: ${cause.message}", cause)
  data object NetworkUnavailable : NuxieNetworkError("Network unavailable")
  data object Timeout : NuxieNetworkError("Request timeout")
  data object Cancelled : NuxieNetworkError("Request cancelled")
}

