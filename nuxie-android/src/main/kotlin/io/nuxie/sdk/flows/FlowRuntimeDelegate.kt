package io.nuxie.sdk.flows

import kotlinx.serialization.json.JsonObject

/**
 * Delegate for runtime bridge messages from the Flow WebView.
 *
 * Mirrors iOS `FlowRuntimeDelegate`.
 */
interface FlowRuntimeDelegate {
  fun onRuntimeMessage(type: String, payload: JsonObject, id: String?)
  fun onDismissRequested(reason: CloseReason)
}

