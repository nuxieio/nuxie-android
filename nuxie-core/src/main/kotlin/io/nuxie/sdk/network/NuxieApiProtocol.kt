package io.nuxie.sdk.network

import io.nuxie.sdk.features.FeatureCheckResult
import io.nuxie.sdk.network.models.BatchRequest
import io.nuxie.sdk.network.models.BatchResponse
import io.nuxie.sdk.network.models.EventResponse
import io.nuxie.sdk.network.models.ProfileResponse
import kotlinx.serialization.json.JsonObject

/**
 * Lightweight interface for the Nuxie HTTP client.
 *
 * Mirrors iOS `NuxieApiProtocol` so core services can be unit tested with fakes.
 */
interface NuxieApiProtocol {
  suspend fun fetchProfile(distinctId: String, locale: String? = null): ProfileResponse

  suspend fun trackEvent(
    event: String,
    distinctId: String,
    anonDistinctId: String? = null,
    properties: JsonObject? = null,
    uuid: String,
    value: Double? = null,
    entityId: String? = null,
    timestamp: String,
  ): EventResponse

  suspend fun sendBatch(batch: BatchRequest): BatchResponse

  suspend fun fetchFlow(flowId: String): JsonObject

  suspend fun checkFeature(
    customerId: String,
    featureId: String,
    requiredBalance: Int? = null,
    entityId: String? = null,
  ): FeatureCheckResult
}

