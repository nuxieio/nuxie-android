package io.nuxie.sdk.flows

import android.os.Bundle
import androidx.activity.ComponentActivity
import io.nuxie.sdk.NuxieSDK
import io.nuxie.sdk.logging.NuxieLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NuxieFlowActivity : ComponentActivity() {
  companion object {
    const val EXTRA_FLOW_ID: String = "io.nuxie.sdk.extra.FLOW_ID"
    const val EXTRA_JOURNEY_ID: String = "io.nuxie.sdk.extra.JOURNEY_ID"
    const val EXTRA_COLOR_SCHEME_MODE: String = "io.nuxie.sdk.extra.COLOR_SCHEME_MODE"
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
    val journeyId = intent?.getStringExtra(EXTRA_JOURNEY_ID)
    val colorSchemeMode = FlowColorSchemeMode.fromRawValue(
      intent?.getStringExtra(EXTRA_COLOR_SCHEME_MODE),
    )

    val sdk = NuxieSDK.shared()
    if (!sdk.isSetup) {
      NuxieLogger.warning("NuxieFlowActivity started before SDK setup")
      finish()
      return
    }

    scope.launch {
      val view = runCatching {
        if (!journeyId.isNullOrBlank()) {
          sdk.getFlowViewForJourney(this@NuxieFlowActivity, flowId, journeyId)
        } else {
          sdk.getFlowView(
            this@NuxieFlowActivity,
            flowId,
            colorSchemeMode = colorSchemeMode,
          )
        }
      }.getOrElse {
        NuxieLogger.warning("Failed to create FlowView for $flowId: ${it.message}", it)
        FlowView(this@NuxieFlowActivity).apply {
          onDismissRequested = { finish() }
          performDismiss(CloseReason.Error(it))
        }
      }

      flowView = view
      view.id = io.nuxie.sdk.R.id.nuxie_flow_view
      view.onDismissRequested = { finish() }
      setContentView(view)
    }
  }

  override fun onDestroy() {
    super.onDestroy()
    scope.cancel()
  }

  @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
  override fun onBackPressed() {
    // Treat back as user dismissal.
    flowView?.performDismiss(CloseReason.UserDismissed)
    finish()
  }
}
