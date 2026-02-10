package io.nuxie.sdk.flows

/**
 * Errors used by the Flow subsystem.
 *
 * Mirrors iOS `FlowError` at a coarse level (more Android-specific detail can be
 * added as we port the rest of the flow/journey runtime).
 */
sealed class FlowError(message: String, cause: Throwable? = null) : Exception(message, cause) {
  class FlowNotFound(flowId: String) : FlowError("Flow not found: $flowId")
  data object InvalidManifest : FlowError("Invalid manifest data")
  data object DownloadFailed : FlowError("Failed to download flow assets")
}

