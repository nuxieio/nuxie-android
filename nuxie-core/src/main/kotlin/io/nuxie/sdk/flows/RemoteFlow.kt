package io.nuxie.sdk.flows

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Server-delivered flow definition.
 *
 * Mirrors iOS `RemoteFlow` (Swift) but keeps complex nested structures (view models,
 * interactions) as raw JSON for now. The Android runtime can progressively type
 * these as the Journey/IR subsystems are ported.
 */
@Serializable
data class RemoteFlow(
  val id: String,
  val bundle: FlowBundleRef,
  val fontManifest: FontManifest? = null,
  val screens: List<RemoteFlowScreen>,
  val interactions: Map<String, List<JsonObject>>,
  val viewModels: List<JsonObject>,
  val viewModelInstances: List<JsonObject>? = null,
  val converters: Map<String, Map<String, JsonElement>>? = null,
)

@Serializable
data class FlowBundleRef(
  val url: String,
  val manifest: BuildManifest,
)

@Serializable
data class FontManifest(
  val version: Int,
  val fonts: List<FontManifestEntry>,
)

@Serializable
data class FontManifestEntry(
  val id: String,
  val family: String,
  val style: String,
  val weight: String,
  val format: String,
  val contentHash: String,
  val assetUrl: String,
)

@Serializable
data class RemoteFlowScreen(
  val id: String,
  @SerialName("defaultViewModelId")
  val defaultViewModelId: String? = null,
  @SerialName("defaultInstanceId")
  val defaultInstanceId: String? = null,
)

