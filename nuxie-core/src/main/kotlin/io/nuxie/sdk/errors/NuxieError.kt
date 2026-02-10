package io.nuxie.sdk.errors

/**
 * Errors surfaced by the SDK.
 *
 * Mirrors iOS `NuxieError` cases closely so that parity is easy to reason about.
 */
sealed class NuxieError(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
  data object NotConfigured : NuxieError("Nuxie SDK is not configured")
  class InvalidConfiguration(reason: String) : NuxieError("Invalid configuration: $reason")
  data object AlreadyConfigured : NuxieError("Nuxie SDK is already configured")

  class NetworkError(cause: Throwable) : NuxieError("Network error: ${cause.message}", cause)
  class PaywallNotFound(id: String) : NuxieError("Paywall not found: $id")
  class StorageError(cause: Throwable) : NuxieError("Storage error: ${cause.message}", cause)
  class InvalidEvent(reason: String) : NuxieError("Invalid event: $reason")
  class EventDropped(reason: String) : NuxieError("Event dropped: $reason")
  data object EventRoutingFailed : NuxieError("Event routing failed")

  // Flow-specific
  class FlowDownloadFailed(flowId: String, cause: Throwable) :
    NuxieError("Failed to download flow $flowId: ${cause.message}", cause)
  class FlowCacheFailed(flowId: String, cause: Throwable) :
    NuxieError("Failed to cache flow $flowId: ${cause.message}", cause)
  class FlowNotCached(flowId: String) : NuxieError("Flow $flowId is not cached")
  class WebArchiveCreationFailed(cause: Throwable) :
    NuxieError("Failed to create WebArchive: ${cause.message}", cause)
  data object FlowManagerNotInitialized : NuxieError("Flow manager is not initialized")
  class FlowError(message: String) : NuxieError("Flow error: $message")
  class ConfigurationError(message: String) : NuxieError("Configuration error: $message")

  // Feature-specific
  class FeatureNotFound(featureId: String) : NuxieError("Feature not found: $featureId")
  class FeatureCheckFailed(featureId: String, cause: Throwable) :
    NuxieError("Feature check failed for $featureId: ${cause.message}", cause)
}

