package io.nuxie.sdk.gating

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GatePlan(
  val decision: Decision,
  val featureId: String? = null,
  val requiredBalance: Int? = null,
  val entityId: String? = null,
  val flowId: String? = null,
  val policy: Policy? = null,
  val timeoutMs: Int? = null,
) {
  @Serializable
  enum class Decision {
    @SerialName("allow")
    ALLOW,
    @SerialName("deny")
    DENY,
    @SerialName("show_flow")
    SHOW_FLOW,
    @SerialName("require_feature")
    REQUIRE_FEATURE,
  }

  @Serializable
  enum class Policy {
    @SerialName("hard")
    HARD,
    @SerialName("soft")
    SOFT,
    @SerialName("cache_only")
    CACHE_ONLY,
  }
}

