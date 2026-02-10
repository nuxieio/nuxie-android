package io.nuxie.sdk.profile

import io.nuxie.sdk.network.models.ProfileResponse
import kotlinx.serialization.Serializable

/**
 * Wrapper stored in cache with metadata.
 *
 * Mirrors iOS `CachedProfile`.
 */
@Serializable
data class CachedProfile(
  val response: ProfileResponse,
  val distinctId: String,
  val cachedAtEpochMillis: Long,
)

