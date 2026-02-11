package io.nuxie.sdk.flows.bridge

fun interface FlowBridgeTransport {
  fun sendToRuntime(envelope: BridgeEnvelope)
}

