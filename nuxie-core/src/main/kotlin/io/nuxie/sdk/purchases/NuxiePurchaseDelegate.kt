package io.nuxie.sdk.purchases

/**
 * Android purchase delegation.
 *
 * iOS uses StoreKit and passes StoreKit product objects through a delegate. On Android,
 * we start with a product-id-first contract so apps (or wrappers) can integrate their
 * preferred purchase stack (Play Billing, RevenueCat, etc) without the SDK forcing a
 * billing implementation before the backend supports Play purchase sync.
 */
interface NuxiePurchaseDelegate {
  suspend fun purchase(productId: String): PurchaseResult

  suspend fun purchaseOutcome(productId: String): PurchaseOutcome {
    val result = purchase(productId)
    return PurchaseOutcome(result = result, productId = productId)
  }

  suspend fun restore(): RestoreResult
}

sealed class PurchaseResult {
  data object Success : PurchaseResult()
  data object Cancelled : PurchaseResult()
  data object Pending : PurchaseResult()
  data class Failed(val message: String) : PurchaseResult()
}

data class PurchaseOutcome(
  val result: PurchaseResult,
  val productId: String? = null,
  // Reserved for future Play Billing parity:
  val purchaseToken: String? = null,
  val orderId: String? = null,
)

sealed class RestoreResult {
  data class Success(val restoredCount: Int) : RestoreResult()
  data object NoPurchases : RestoreResult()
  data class Failed(val message: String) : RestoreResult()
}

