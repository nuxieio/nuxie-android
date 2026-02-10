package io.nuxie.sdk.flows

import kotlinx.serialization.Serializable

/**
 * Mirrors `packages/models/src/schemas/build.ts` (BuildManifest).
 *
 * This manifest is used for flow bundle caching and offline loading.
 */
@Serializable
data class BuildManifest(
  val totalFiles: Int,
  val totalSize: Long,
  val contentHash: String,
  val files: List<BuildManifestFile>,
)

@Serializable
data class BuildManifestFile(
  val path: String,
  val size: Long,
  val contentType: String,
)

