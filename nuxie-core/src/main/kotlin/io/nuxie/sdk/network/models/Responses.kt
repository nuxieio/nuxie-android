package io.nuxie.sdk.network.models

import io.nuxie.sdk.features.Feature
import io.nuxie.sdk.gating.GatePlan
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class BatchResponse(
  val status: String,
  val processed: Int,
  val failed: Int,
  val total: Int,
  val errors: List<BatchError>? = null,
)

@Serializable
data class BatchError(
  val index: Int,
  val event: String,
  val error: String,
)

@Serializable
data class ProfileResponse(
  val campaigns: List<JsonElement> = emptyList(),
  val segments: List<JsonElement> = emptyList(),
  val flows: List<JsonElement> = emptyList(),
  val userProperties: JsonObject? = null,
  val experiments: Map<String, ExperimentAssignment>? = null,
  val features: List<Feature>? = null,
  val journeys: List<ActiveJourney>? = null,
)

@Serializable
data class ActiveJourney(
  val sessionId: String,
  val campaignId: String,
  val currentNodeId: String,
  val context: JsonObject,
)

@Serializable
data class ExperimentAssignment(
  val experimentKey: String,
  val variantKey: String? = null,
  val status: String,
  val isHoldout: Boolean? = null,
)

@Serializable
data class EventResponse(
  val status: String,
  val payload: JsonObject? = null,
  val customer: Customer? = null,
  val event: EventInfo? = null,
  val message: String? = null,
  val featuresMatched: Int? = null,
  val usage: Usage? = null,
  val journey: JourneyInfo? = null,
  val execution: ExecutionResult? = null,
) {
  @Serializable
  data class Customer(
    val id: String,
    val properties: JsonObject? = null,
  )

  @Serializable
  data class EventInfo(
    val id: String,
    val processed: Boolean,
  )

  @Serializable
  data class Usage(
    val current: Double,
    val limit: Double? = null,
    val remaining: Double? = null,
  )

  @Serializable
  data class JourneyInfo(
    val sessionId: String? = null,
    val currentNodeId: String? = null,
    val status: String? = null,
  )

  @Serializable
  data class ExecutionResult(
    val success: Boolean,
    val statusCode: Int? = null,
    val error: ExecutionError? = null,
    val contextUpdates: JsonObject? = null,
  ) {
    @Serializable
    data class ExecutionError(
      val message: String,
      val retryable: Boolean,
      val retryAfter: Int? = null,
    )
  }

  /**
   * Extract a GatePlan from the response payload.
   *
   * Mirrors iOS `EventResponse.gatePlan()`.
   */
  fun gatePlan(json: Json): GatePlan? {
    val p = payload ?: return null
    val raw: JsonElement = p["gate"] ?: p
    val obj = try {
      raw.jsonObject
    } catch (_: Exception) {
      return null
    }
    return try {
      json.decodeFromJsonElement(GatePlan.serializer(), obj)
    } catch (_: Exception) {
      null
    }
  }
}

@Serializable
data class ApiErrorResponse(
  val message: String,
  val code: String? = null,
  val details: JsonObject? = null,
)
