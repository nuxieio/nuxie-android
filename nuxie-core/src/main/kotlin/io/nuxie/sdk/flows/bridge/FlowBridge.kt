package io.nuxie.sdk.flows.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Cache-first, readiness-aware host bridge for the Flow Web runtime.
 *
 * Mirrors iOS behavior:
 * - buffers host->runtime messages until `runtime/ready`
 * - replies immediately to `ping` requests with `pong`
 */
class FlowBridge(
  private val transport: FlowBridgeTransport,
  private val json: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  },
) {
  var onMessage: ((BridgeEnvelope) -> Unit)? = null

  private var runtimeReady: Boolean = false
  private val pendingOutgoing = mutableListOf<BridgeEnvelope>()

  fun reset() {
    runtimeReady = false
    pendingOutgoing.clear()
  }

  fun send(type: String, payload: JsonObject = JsonObject(emptyMap()), replyTo: String? = null) {
    val envelope = BridgeEnvelope(type = type, payload = payload, replyTo = replyTo)
    if (!runtimeReady) {
      pendingOutgoing.add(envelope)
      return
    }
    transport.sendToRuntime(envelope)
  }

  /**
   * Send a response envelope immediately (not buffered by runtime readiness).
   */
  fun sendResponse(replyTo: String, result: Any? = null, error: String? = null) {
    val payload = buildJsonObject {
      if (error != null) {
        put("error", JsonPrimitive(error))
      } else if (result != null) {
        put("result", JsonPrimitive(result.toString()))
      } else {
        put("result", JsonPrimitive(""))
      }
    }
    transport.sendToRuntime(BridgeEnvelope(type = "response", payload = payload, replyTo = replyTo))
  }

  fun handleIncomingJson(raw: String) {
    val env = json.decodeFromString(BridgeEnvelope.serializer(), raw)

    // Reply to ping immediately.
    if (env.type == "ping" && env.id != null) {
      val payload = buildJsonObject { put("result", JsonPrimitive("pong")) }
      transport.sendToRuntime(BridgeEnvelope(type = "response", payload = payload, replyTo = env.id))
      return
    }

    if (env.type == "runtime/ready") {
      runtimeReady = true
      onMessage?.invoke(env)
      flushPending()
      return
    }

    onMessage?.invoke(env)
  }

  private fun flushPending() {
    if (!runtimeReady) return
    if (pendingOutgoing.isEmpty()) return
    val queued = pendingOutgoing.toList()
    pendingOutgoing.clear()
    for (env in queued) {
      transport.sendToRuntime(env)
    }
  }
}
