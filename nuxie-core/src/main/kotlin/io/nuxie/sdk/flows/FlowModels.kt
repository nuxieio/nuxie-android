package io.nuxie.sdk.flows

/**
 * Client-side flow model that enriches a [RemoteFlow] with local state and (later)
 * Play Billing product metadata.
 *
 * Mirrors iOS `Flow`.
 */
data class Flow(
  val remoteFlow: RemoteFlow,
  val products: List<FlowProduct> = emptyList(),
) {
  val id: String get() = remoteFlow.id
  val manifest: BuildManifest get() = remoteFlow.bundle.manifest
  val url: String get() = remoteFlow.bundle.url
}

data class FlowProduct(
  val id: String,
  val name: String,
  val price: String,
  val period: ProductPeriod? = null,
)

enum class ProductPeriod {
  WEEK,
  MONTH,
  YEAR,
  LIFETIME,
}

sealed class CloseReason {
  data object UserDismissed : CloseReason()
  data object PurchaseCompleted : CloseReason()
  data object Timeout : CloseReason()
  data class Error(val throwable: Throwable) : CloseReason()
}

