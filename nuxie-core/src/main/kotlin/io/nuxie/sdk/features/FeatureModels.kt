package io.nuxie.sdk.features

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

