package io.nuxie.sdk.features

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Feature models returned from `/profile` and purchase sync.
 *
 * Mirrors iOS `Feature`, `FeatureType`, and `EntityBalance`.
 */
@Serializable
enum class FeatureType {
  @SerialName("boolean")
  BOOLEAN,
  @SerialName("metered")
  METERED,
  @SerialName("creditSystem")
  CREDIT_SYSTEM,
}

@Serializable
data class EntityBalance(
  val balance: Int,
)

@Serializable
data class Feature(
  val id: String,
  val type: FeatureType,
  val balance: Int? = null,
  val unlimited: Boolean,
  val nextResetAt: Int? = null,
  val interval: String? = null,
  val entities: Map<String, EntityBalance>? = null,
)

/**
 * Response from the real-time `/entitled` endpoint.
 *
 * Mirrors iOS `FeatureCheckResult`.
 */
@Serializable
data class FeatureCheckResult(
  val customerId: String,
  val featureId: String,
  val requiredBalance: Int,
  val code: String,
  val allowed: Boolean,
  val unlimited: Boolean,
  val balance: Int? = null,
  val type: FeatureType,
  val preview: JsonElement? = null,
)

/**
 * Simplified view of feature access for SDK consumers.
 *
 * Mirrors iOS `FeatureAccess`.
 */
data class FeatureAccess(
  val allowed: Boolean,
  val unlimited: Boolean,
  val balance: Int?,
  val type: FeatureType,
) {
  val hasAccess: Boolean get() = allowed

  val hasBalance: Boolean get() = unlimited || (balance ?: 0) > 0

  companion object {
    val notFound: FeatureAccess = FeatureAccess(
      allowed = false,
      unlimited = false,
      balance = null,
      type = FeatureType.BOOLEAN,
    )

    fun withBalance(balance: Int, unlimited: Boolean, type: FeatureType): FeatureAccess {
      return FeatureAccess(
        allowed = unlimited || balance > 0,
        unlimited = unlimited,
        balance = balance,
        type = type,
      )
    }

    fun fromFeature(feature: Feature, requiredBalance: Int = 1): FeatureAccess {
      return when (feature.type) {
        FeatureType.BOOLEAN -> FeatureAccess(
          allowed = true,
          unlimited = false,
          balance = feature.balance,
          type = feature.type,
        )
        FeatureType.METERED, FeatureType.CREDIT_SYSTEM -> {
          val allowed = feature.unlimited || (feature.balance ?: 0) >= requiredBalance
          FeatureAccess(
            allowed = allowed,
            unlimited = feature.unlimited,
            balance = feature.balance,
            type = feature.type,
          )
        }
      }
    }

    fun fromCheckResult(result: FeatureCheckResult): FeatureAccess {
      return FeatureAccess(
        allowed = result.allowed,
        unlimited = result.unlimited,
        balance = result.balance,
        type = result.type,
      )
    }

    fun fromPurchaseFeature(purchase: PurchaseFeature): FeatureAccess {
      return FeatureAccess(
        allowed = purchase.allowed,
        unlimited = purchase.unlimited,
        balance = purchase.balance,
        type = purchase.type,
      )
    }
  }
}

/**
 * Response from `/purchase` after syncing a transaction.
 *
 * Mirrors iOS `PurchaseResponse`.
 */
@Serializable
data class PurchaseResponse(
  val success: Boolean,
  @SerialName("customer_id")
  val customerId: String? = null,
  val features: List<PurchaseFeature>? = null,
  val error: String? = null,
)

@Serializable
data class PurchaseFeature(
  val id: String,
  @SerialName("ext_id")
  val extId: String? = null,
  val type: FeatureType,
  val allowed: Boolean,
  val balance: Int? = null,
  val unlimited: Boolean,
) {
  val toFeatureAccess: FeatureAccess get() = FeatureAccess.fromPurchaseFeature(this)
}

/**
 * Result of a feature usage report.
 *
 * Mirrors iOS `FeatureUsageResult`.
 */
data class FeatureUsageResult(
  val success: Boolean,
  val featureId: String,
  val amountUsed: Double,
  val message: String? = null,
  val usage: UsageInfo? = null,
) {
  data class UsageInfo(
    val current: Double,
    val limit: Double? = null,
    val remaining: Double? = null,
  )
}
