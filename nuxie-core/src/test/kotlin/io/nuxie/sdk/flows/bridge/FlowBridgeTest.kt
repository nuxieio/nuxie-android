package io.nuxie.sdk.flows.bridge

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowBridgeTest {

  private class CapturingTransport : FlowBridgeTransport {
    val sent = mutableListOf<BridgeEnvelope>()
    override fun sendToRuntime(envelope: BridgeEnvelope) {
      sent.add(envelope)
    }
  }

  @Test
  fun send_buffers_until_runtime_ready_then_flushes_in_order() {
    val transport = CapturingTransport()
    val bridge = FlowBridge(transport)

    bridge.send("runtime/navigate", buildJsonObject { put("screenId", JsonPrimitive("s1")) })
    bridge.send("runtime/navigate", buildJsonObject { put("screenId", JsonPrimitive("s2")) })
    assertEquals(0, transport.sent.size)

    bridge.handleIncomingJson("""{"type":"runtime/ready","payload":{}}""")

    assertEquals(2, transport.sent.size)
    assertEquals("runtime/navigate", transport.sent[0].type)
    assertEquals("runtime/navigate", transport.sent[1].type)
  }

  @Test
  fun ping_replies_immediately_even_before_ready() {
    val transport = CapturingTransport()
    val bridge = FlowBridge(transport)

    bridge.handleIncomingJson("""{"type":"ping","id":"req_1","payload":{}}""")

    assertEquals(1, transport.sent.size)
    val reply = transport.sent.first()
    assertEquals("response", reply.type)
    assertEquals("req_1", reply.replyTo)
    val payload = reply.payload as JsonObject
    assertTrue(payload["result"]?.toString()?.contains("pong") == true)
  }
}

