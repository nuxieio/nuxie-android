package io.nuxie.example

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.nuxie.sdk.NuxieDelegate
import io.nuxie.sdk.NuxieSDK
import io.nuxie.sdk.config.LogLevel
import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.features.FeatureAccess
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), NuxieDelegate {
  private val sdk = NuxieSDK.shared()

  private lateinit var apiKeyInput: EditText
  private lateinit var apiEndpointInput: EditText
  private lateinit var distinctIdInput: EditText
  private lateinit var eventNameInput: EditText
  private lateinit var flowIdInput: EditText
  private lateinit var logOutput: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    bindViews()
    configureDefaults()
    wireActions()
  }

  private fun bindViews() {
    apiKeyInput = findViewById(R.id.apiKeyInput)
    apiEndpointInput = findViewById(R.id.apiEndpointInput)
    distinctIdInput = findViewById(R.id.distinctIdInput)
    eventNameInput = findViewById(R.id.eventNameInput)
    flowIdInput = findViewById(R.id.flowIdInput)
    logOutput = findViewById(R.id.logOutput)
  }

  private fun configureDefaults() {
    apiKeyInput.setText(BuildConfig.DEFAULT_API_KEY)
    apiEndpointInput.setText(BuildConfig.DEFAULT_API_ENDPOINT)
    distinctIdInput.setText("demo_user_android")
    eventNameInput.setText("demo_trigger")
    flowIdInput.setText(BuildConfig.DEFAULT_FLOW_ID)
    appendLog("Example ready. Press Setup SDK to initialize.")
  }

  private fun wireActions() {
    findViewById<Button>(R.id.setupButton).setOnClickListener { setupSdk() }
    findViewById<Button>(R.id.identifyButton).setOnClickListener { identifyUser() }
    findViewById<Button>(R.id.triggerButton).setOnClickListener { triggerEvent() }
    findViewById<Button>(R.id.showFlowButton).setOnClickListener { showFlow() }
    findViewById<Button>(R.id.refreshProfileButton).setOnClickListener { refreshProfile() }
    findViewById<Button>(R.id.startSessionButton).setOnClickListener { startSession() }
    findViewById<Button>(R.id.endSessionButton).setOnClickListener { endSession() }
    findViewById<Button>(R.id.resetSessionButton).setOnClickListener { resetSession() }
  }

  private fun setupSdk() {
    if (sdk.isSetup) {
      appendLog("SDK already configured.")
      return
    }

    val apiKey = apiKeyInput.text.toString().trim()
    val endpoint = apiEndpointInput.text.toString().trim()

    if (apiKey.isBlank()) {
      appendLog("API key is required.")
      return
    }

    val configuration = NuxieConfiguration(apiKey).apply {
      logLevel = LogLevel.DEBUG
      enableConsoleLogging = true
      if (endpoint.isNotBlank()) {
        setApiEndpoint(endpoint)
      }
      addPlugin(ExampleAppLifecyclePlugin())
    }

    runCatching {
      sdk.delegate = this
      sdk.setup(applicationContext, configuration)
      appendLog("SDK setup complete. distinctId=${sdk.getDistinctId()}")
    }.onFailure {
      appendLog("Setup failed: ${it.message}")
    }
  }

  private fun identifyUser() {
    if (!sdk.isSetup) {
      appendLog("Setup SDK first.")
      return
    }

    val distinctId = distinctIdInput.text.toString().trim().ifBlank { "demo_user_android" }
    sdk.identify(
      distinctId = distinctId,
      userProperties = mapOf("platform" to "android", "source" to "example_app"),
    )
    appendLog("identify() sent for distinctId=$distinctId")
  }

  private fun triggerEvent() {
    if (!sdk.isSetup) {
      appendLog("Setup SDK first.")
      return
    }

    val eventName = eventNameInput.text.toString().trim().ifBlank { "demo_trigger" }
    sdk.trigger(
      event = eventName,
      properties = mapOf("source" to "example_app", "surface" to "manual_button"),
    ) { update ->
      appendLog("trigger update -> $update")
    }
    appendLog("trigger() dispatched for event=$eventName")
  }

  private fun showFlow() {
    if (!sdk.isSetup) {
      appendLog("Setup SDK first.")
      return
    }

    val flowId = flowIdInput.text.toString().trim()
    if (flowId.isBlank()) {
      appendLog("Flow ID is required for showFlow().")
      return
    }

    sdk.showFlow(flowId)
    appendLog("showFlow() called with flowId=$flowId")
  }

  private fun refreshProfile() {
    if (!sdk.isSetup) {
      appendLog("Setup SDK first.")
      return
    }

    lifecycleScope.launch {
      runCatching {
        val profile = sdk.refreshProfile()
        appendLog(
          "refreshProfile() ok campaigns=${profile.campaigns.size} " +
            "segments=${profile.segments.size} flows=${profile.flows.size}"
        )
      }.onFailure {
        appendLog("refreshProfile() failed: ${it.message}")
      }
    }
  }

  private fun startSession() {
    if (!sdk.isSetup) {
      appendLog("Setup SDK first.")
      return
    }

    sdk.startNewSession()
    appendLog("startNewSession() -> ${sdk.getCurrentSessionId()}")
  }

  private fun endSession() {
    if (!sdk.isSetup) {
      appendLog("Setup SDK first.")
      return
    }

    sdk.endSession()
    appendLog("endSession() -> ${sdk.getCurrentSessionId()}")
  }

  private fun resetSession() {
    if (!sdk.isSetup) {
      appendLog("Setup SDK first.")
      return
    }

    sdk.resetSession()
    appendLog("resetSession() -> ${sdk.getCurrentSessionId()}")
  }

  private fun appendLog(message: String) {
    runOnUiThread {
      val current = logOutput.text?.toString().orEmpty()
      val next = if (current.isBlank()) message else "$current\n$message"
      logOutput.text = next
    }
  }

  override fun featureAccessDidChange(featureId: String, from: FeatureAccess?, to: FeatureAccess) {
    appendLog("delegate featureAccessDidChange featureId=$featureId allowed=${to.allowed}")
  }

  override fun flowDelegateCalled(message: String, payload: Any?, journeyId: String, campaignId: String?) {
    appendLog("delegate flowDelegateCalled message=$message journeyId=$journeyId campaignId=$campaignId payload=$payload")
  }

  override fun flowPurchaseRequested(
    journeyId: String,
    campaignId: String?,
    screenId: String?,
    productId: String,
    placementIndex: Any?,
  ) {
    appendLog("delegate flowPurchaseRequested journeyId=$journeyId campaignId=$campaignId screenId=$screenId productId=$productId placementIndex=$placementIndex")
  }

  override fun flowRestoreRequested(journeyId: String, campaignId: String?, screenId: String?) {
    appendLog("delegate flowRestoreRequested journeyId=$journeyId campaignId=$campaignId screenId=$screenId")
  }

  override fun flowOpenLinkRequested(
    journeyId: String,
    campaignId: String?,
    screenId: String?,
    url: String,
    target: String?,
  ) {
    appendLog("delegate flowOpenLinkRequested journeyId=$journeyId campaignId=$campaignId screenId=$screenId url=$url target=$target")
  }

  override fun flowDismissed(
    journeyId: String,
    campaignId: String?,
    screenId: String?,
    reason: String,
    error: String?,
  ) {
    appendLog("delegate flowDismissed journeyId=$journeyId campaignId=$campaignId screenId=$screenId reason=$reason error=$error")
  }

  override fun flowBackRequested(journeyId: String, campaignId: String?, screenId: String?, steps: Int) {
    appendLog("delegate flowBackRequested journeyId=$journeyId campaignId=$campaignId screenId=$screenId steps=$steps")
  }
}
