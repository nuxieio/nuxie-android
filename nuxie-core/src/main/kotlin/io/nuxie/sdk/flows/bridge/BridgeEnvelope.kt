package io.nuxie.sdk.flows.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Envelope used by the `@nuxie/bridge` contract.
 *
 * JS -> Host uses:
 * - `type` (required)
 * - `payload` (optional)
 * - `id` (optional request id)
 *
 * Host -> JS uses:
 * - `type` (required)
 * - `payload` (optional)
 * - `replyTo` (optional request id to reply to)
 */
@Serializable
data class BridgeEnvelope(
  val type: String,
  val payload: JsonElement? = null,
  val id: String? = null,
  val replyTo: String? = null,
)

