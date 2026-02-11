package io.nuxie.sdk.flows

import android.app.Activity
import android.os.Bundle
import io.nuxie.sdk.NuxieSDK
import io.nuxie.sdk.logging.NuxieLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NuxieFlowActivity : Activity() {
  companion object {
    const val EXTRA_FLOW_ID: String = "io.nuxie.sdk.extra.FLOW_ID"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var flowView: FlowView? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    val flowId = intent?.getStringExtra(EXTRA_FLOW_ID)
    if (flowId.isNullOrBlank()) {
      finish()
      return
    }

    val sdk = NuxieSDK.shared()
    if (!sdk.isSetup) {
      NuxieLogger.warning("NuxieFlowActivity started before SDK setup")
      finish()
      return
    }

    scope.launch {
      val view = runCatching { sdk.getFlowView(this@NuxieFlowActivity, flowId) }.getOrElse {
        NuxieLogger.warning("Failed to create FlowView for $flowId: ${it.message}", it)
        FlowView(this@NuxieFlowActivity).apply {
          onDismissRequested = { finish() }
          performDismiss(CloseReason.Error(it))
        }
      }

      flowView = view
      view.onDismissRequested = { finish() }
      setContentView(view)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    scope.cancel()
  }

  @Suppress("DEPRECATION")
  override fun onBackPressed() {
    // Treat back as user dismissal.
    flowView?.performDismiss(CloseReason.UserDismissed)
    finish()
  }
}
