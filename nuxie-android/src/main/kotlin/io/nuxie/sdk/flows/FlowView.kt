package io.nuxie.sdk.flows

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.purchases.NuxiePurchaseDelegate
import io.nuxie.sdk.purchases.PurchaseResult
import io.nuxie.sdk.purchases.RestoreResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class FlowView(context: Context) : FrameLayout(context) {

  private enum class State {
    LOADING,
    LOADED,
    ERROR,
  }

  var runtimeDelegate: FlowRuntimeDelegate? = null
  var onClose: ((CloseReason) -> Unit)? = null
  var onDismissRequested: ((CloseReason) -> Unit)? = null

  private var didInvokeClose: Boolean = false
  private var state: State = State.LOADING

  private lateinit var webView: FlowWebView
  private lateinit var loadingView: View
  private lateinit var errorView: View

  private var loadTimeoutJob: Job? = null

  private var purchaseDelegate: NuxiePurchaseDelegate? = null
  private var scope: CoroutineScope? = null
  private var flow: Flow? = null
  private var bundleStore: FlowBundleStore? = null

  fun load(
    flow: Flow,
    bundleStore: FlowBundleStore,
    fontStore: FontStore,
    purchaseDelegate: NuxiePurchaseDelegate?,
    scope: CoroutineScope,
  ) {
    this.flow = flow
    this.bundleStore = bundleStore
    this.purchaseDelegate = purchaseDelegate
    this.scope = scope

    removeAllViews()
    didInvokeClose = false

    webView = FlowWebView(context = context, fontStore = fontStore).also { wv ->
      wv.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
      wv.onLoadingStarted = {
        setState(State.LOADING)
        startLoadTimeout()
      }
      wv.onLoadingFinished = {
        // Page finished; runtime may still be booting but UI can appear.
        setState(State.LOADED)
        cancelLoadTimeout()
      }
      wv.onLoadingFailed = { err ->
        NuxieLogger.warning("FlowView load failed: ${err.message}", err)
        setState(State.ERROR)
        cancelLoadTimeout()
      }
      wv.onBridgeMessage = { env ->
        val payload = (env.payload as? JsonObject) ?: JsonObject(emptyMap())
        handleBridgeMessage(type = env.type, payload = payload, id = env.id)
      }
    }

    loadingView = buildLoadingView()
    errorView = buildErrorView()

    addView(webView)
    addView(loadingView)
    addView(errorView)

    // Configure interception: cache-first bundle + fonts
    val interceptor = FlowResourceInterceptor(flow = flow, fontStore = fontStore)
    interceptor.setBundleDir(bundleStore.getCachedBundleDir(flow))
    webView.setResourceInterceptor(interceptor)
    webView.resetBridge()

    // Prefetch fonts and bundle in the background (best-effort).
    scope.launch(Dispatchers.IO) {
      runCatching { fontStore.registerManifest(flow.remoteFlow.fontManifest) }
      val fonts = flow.remoteFlow.fontManifest?.fonts.orEmpty()
      if (fonts.isNotEmpty()) {
        runCatching { fontStore.prefetchFonts(fonts) }
      }
      runCatching {
        val dir = bundleStore.preloadBundle(flow)
        interceptor.setBundleDir(dir)
      }
    }

    // Begin load (cache-first via interception).
    val entryFile = bundleStore.resolveMainFile(flow.manifest)
    val base = flow.url.toHttpUrlOrNull()
    if (entryFile == null || base == null) {
      setState(State.ERROR)
      return
    }

    val entryUrl = base.newBuilder().addPathSegments(entryFile.path.trimStart('/')).build().toString()
    setState(State.LOADING)
    startLoadTimeout()
    webView.loadUrl(entryUrl)
  }

  fun sendRuntimeMessage(type: String, payload: JsonObject = JsonObject(emptyMap()), replyTo: String? = null) {
    webView.sendBridgeMessage(type = type, payload = payload, replyTo = replyTo)
  }

  fun performDismiss(reason: CloseReason = CloseReason.UserDismissed) {
    runtimeDelegate?.onDismissRequested(reason)
    invokeOnCloseOnce(reason)
    onDismissRequested?.invoke(reason)
  }

  private fun invokeOnCloseOnce(reason: CloseReason) {
    if (didInvokeClose) return
    didInvokeClose = true
    onClose?.invoke(reason)
  }

  private fun handleBridgeMessage(type: String, payload: JsonObject, id: String?) {
    when (type) {
      "runtime/ready",
      "runtime/screen_changed",
      "action/did_set",
      "action/event",
      -> runtimeDelegate?.onRuntimeMessage(type, payload, id)

      "action/purchase" -> {
        if (runtimeDelegate != null) {
          runtimeDelegate?.onRuntimeMessage(type, payload, id)
        } else {
          val productId = (payload["productId"] as? JsonPrimitive)?.contentOrNull
          if (productId.isNullOrBlank()) return
          handlePurchase(productId)
        }
      }

      "action/restore" -> {
        if (runtimeDelegate != null) {
          runtimeDelegate?.onRuntimeMessage(type, payload, id)
        } else {
          handleRestore()
        }
      }

      "action/open_link" -> {
        if (runtimeDelegate != null) {
          runtimeDelegate?.onRuntimeMessage(type, payload, id)
        } else {
          val url = (payload["url"] as? JsonPrimitive)?.contentOrNull ?: return
          val target = (payload["target"] as? JsonPrimitive)?.contentOrNull
          performOpenLink(url, target)
        }
      }

      "action/back" -> {
        if (runtimeDelegate != null) {
          runtimeDelegate?.onRuntimeMessage(type, payload, id)
        } else {
          NuxieLogger.debug("FlowView: Unhandled runtime back action")
        }
      }

      "action/dismiss", "dismiss", "closeFlow" -> {
        performDismiss(CloseReason.UserDismissed)
      }

      else -> {
        if (type.startsWith("action/")) {
          runtimeDelegate?.onRuntimeMessage(type, payload, id)
        } else {
          NuxieLogger.debug("FlowView: Unhandled bridge message: $type")
        }
      }
    }
  }

  private fun handlePurchase(productId: String) {
    val delegate = purchaseDelegate
    if (delegate == null) {
      webView.sendBridgeMessage(
        type = "purchase_error",
        payload = buildJsonObject { put("error", JsonPrimitive("purchase_delegate_not_configured")) },
      )
      return
    }

    val s = scope ?: return
    s.launch(Dispatchers.Main) {
      val outcome = runCatching { delegate.purchaseOutcome(productId) }.getOrElse {
        webView.sendBridgeMessage(
          type = "purchase_error",
          payload = buildJsonObject { put("error", JsonPrimitive(it.message ?: "purchase_failed")) },
        )
        return@launch
      }

      when (val res = outcome.result) {
        PurchaseResult.Success -> {
          webView.sendBridgeMessage(
            type = "purchase_ui_success",
            payload = buildJsonObject { put("productId", JsonPrimitive(productId)) },
          )
          webView.sendBridgeMessage(
            type = "purchase_confirmed",
            payload = buildJsonObject { put("productId", JsonPrimitive(productId)) },
          )
        }
        PurchaseResult.Cancelled -> {
          webView.sendBridgeMessage(type = "purchase_cancelled", payload = JsonObject(emptyMap()))
        }
        PurchaseResult.Pending -> {
          webView.sendBridgeMessage(
            type = "purchase_error",
            payload = buildJsonObject { put("error", JsonPrimitive("purchase_pending")) },
          )
        }
        is PurchaseResult.Failed -> {
          webView.sendBridgeMessage(
            type = "purchase_error",
            payload = buildJsonObject { put("error", JsonPrimitive(res.message)) },
          )
        }
      }
    }
  }

  private fun handleRestore() {
    val delegate = purchaseDelegate
    if (delegate == null) {
      webView.sendBridgeMessage(
        type = "restore_error",
        payload = buildJsonObject { put("error", JsonPrimitive("purchase_delegate_not_configured")) },
      )
      return
    }
    val s = scope ?: return
    s.launch(Dispatchers.Main) {
      val result = runCatching { delegate.restore() }.getOrElse {
        webView.sendBridgeMessage(
          type = "restore_error",
          payload = buildJsonObject { put("error", JsonPrimitive(it.message ?: "restore_failed")) },
        )
        return@launch
      }

      when (result) {
        is RestoreResult.Success,
        RestoreResult.NoPurchases,
        -> webView.sendBridgeMessage(type = "restore_success", payload = JsonObject(emptyMap()))

        is RestoreResult.Failed -> webView.sendBridgeMessage(
          type = "restore_error",
          payload = buildJsonObject { put("error", JsonPrimitive(result.message)) },
        )
      }
    }
  }

  private fun performOpenLink(urlString: String, target: String?) {
    val uri = runCatching { Uri.parse(urlString) }.getOrNull() ?: return
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
      .onFailure { NuxieLogger.debug("FlowView: Failed to open link: ${it.message}", it) }
  }

  private fun startLoadTimeout() {
    cancelLoadTimeout()
    val s = scope ?: return
    loadTimeoutJob = s.launch(Dispatchers.Main) {
      delay(15_000)
      if (state == State.LOADING) {
        setState(State.ERROR)
      }
    }
  }

  private fun cancelLoadTimeout() {
    loadTimeoutJob?.cancel()
    loadTimeoutJob = null
  }

  private fun setState(next: State) {
    state = next
    when (next) {
      State.LOADING -> {
        webView.visibility = View.INVISIBLE
        loadingView.visibility = View.VISIBLE
        errorView.visibility = View.GONE
      }
      State.LOADED -> {
        webView.visibility = View.VISIBLE
        loadingView.visibility = View.GONE
        errorView.visibility = View.GONE
      }
      State.ERROR -> {
        webView.visibility = View.INVISIBLE
        loadingView.visibility = View.GONE
        errorView.visibility = View.VISIBLE
      }
    }
  }

  private fun buildLoadingView(): View {
    val container = FrameLayout(context).apply {
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }
    val pb = ProgressBar(context).apply {
      layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
        gravity = Gravity.CENTER
      }
      isIndeterminate = true
    }
    container.addView(pb)
    return container
  }

  private fun buildErrorView(): View {
    val container = FrameLayout(context).apply {
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }
    val stack = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    val title = TextView(context).apply {
      text = "Something went wrong"
      textSize = 16f
      gravity = Gravity.CENTER
    }
    val retry = Button(context).apply {
      text = "Retry"
      setOnClickListener {
        val f = flow ?: return@setOnClickListener
        val bs = bundleStore ?: return@setOnClickListener
        // Reload using the existing setup (bundle store will serve cache if present).
        setState(State.LOADING)
        startLoadTimeout()
        val entryFile = bs.resolveMainFile(f.manifest) ?: run {
          setState(State.ERROR)
          return@setOnClickListener
        }
        val base = f.url.toHttpUrlOrNull() ?: run {
          setState(State.ERROR)
          return@setOnClickListener
        }
        val entryUrl = base.newBuilder().addPathSegments(entryFile.path.trimStart('/')).build().toString()
        webView.loadUrl(entryUrl)
      }
    }
    val close = Button(context).apply {
      text = "Close"
      setOnClickListener { performDismiss(CloseReason.UserDismissed) }
    }

    stack.addView(title)
    stack.addView(retry)
    stack.addView(close)
    container.addView(stack)
    return container
  }
}
