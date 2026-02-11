package io.nuxie.sdk.flows

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.nuxie.sdk.NuxieVersion
import io.nuxie.sdk.flows.bridge.BridgeEnvelope
import io.nuxie.sdk.flows.bridge.FlowBridge
import io.nuxie.sdk.flows.bridge.FlowBridgeTransport
import io.nuxie.sdk.logging.NuxieLogger
import java.io.ByteArrayInputStream
import kotlinx.serialization.json.Json

class FlowWebView(
  context: Context,
  private val fontStore: FontStore,
) : WebView(context) {

  var onLoadingStarted: (() -> Unit)? = null
  var onLoadingFinished: (() -> Unit)? = null
  var onLoadingFailed: ((Throwable) -> Unit)? = null
  var onBridgeMessage: ((BridgeEnvelope) -> Unit)? = null

  private val mainHandler = Handler(Looper.getMainLooper())

  private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  }

  private var resourceInterceptor: FlowResourceInterceptor? = null

  private val bridge = FlowBridge(
    transport = FlowBridgeTransport { envelope -> sendEnvelopeToRuntime(envelope) },
    json = json,
  ).also { b ->
    b.onMessage = { env -> onBridgeMessage?.invoke(env) }
  }

  init {
    configure()
  }

  fun setResourceInterceptor(interceptor: FlowResourceInterceptor?) {
    resourceInterceptor = interceptor
  }

  fun resetBridge() {
    bridge.reset()
  }

  fun sendBridgeMessage(type: String, payload: kotlinx.serialization.json.JsonObject, replyTo: String? = null) {
    bridge.send(type = type, payload = payload, replyTo = replyTo)
  }

  private fun configure() {
    // Appearance
    setBackgroundColor(Color.TRANSPARENT)

    // WebView settings
    val s = settings
    s.javaScriptEnabled = true
    s.javaScriptCanOpenWindowsAutomatically = true
    s.domStorageEnabled = true
    s.mediaPlaybackRequiresUserGesture = false
    s.cacheMode = WebSettings.LOAD_DEFAULT
    s.setSupportZoom(false)

    // User-Agent: append Nuxie version (don’t clobber device UA).
    s.userAgentString = "${s.userAgentString} NuxieSDK/${NuxieVersion.current}"

    // JS -> native bridge transport for `@nuxie/bridge` (Android)
    addJavascriptInterface(AndroidBridge(), "AndroidBridge")

    webViewClient = object : WebViewClient() {
      override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        onLoadingStarted?.invoke()
      }

      override fun onPageFinished(view: WebView?, url: String?) {
        onLoadingFinished?.invoke()
      }

      override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        // Fonts need a fast path even if flow interceptor isn't set yet.
        if (uri.scheme?.lowercase() == "nuxie-font") {
          val fontId = uri.host?.takeIf { it.isNotBlank() } ?: uri.path?.trim('/')?.takeIf { it.isNotBlank() }
          val payload = fontId?.let { fontStore.fontPayloadBlocking(it) } ?: return null
          return WebResourceResponse(payload.mimeType, null, ByteArrayInputStream(payload.bytes))
        }
        val interceptor = resourceInterceptor ?: return null
        return interceptor.intercept(uri)
      }

      override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: android.webkit.WebResourceError?,
      ) {
        // Only surface top-level navigation failures.
        if (request?.isForMainFrame == true) {
          onLoadingFailed?.invoke(RuntimeException(error?.description?.toString() ?: "WebView error"))
        }
      }
    }
  }

  private fun sendEnvelopeToRuntime(envelope: BridgeEnvelope) {
    val jsonStr = json.encodeToString(BridgeEnvelope.serializer(), envelope)
    val script = """
      (function(){
        try {
          if (window.nuxie && typeof window.nuxie._handleHostMessage === 'function') {
            window.nuxie._handleHostMessage($jsonStr);
          }
        } catch (e) { /* ignore */ }
      })();
    """.trimIndent()

    // Evaluate on main thread (required by WebView).
    if (Looper.myLooper() == Looper.getMainLooper()) {
      evaluateJavascript(script, null)
    } else {
      mainHandler.post { evaluateJavascript(script, null) }
    }
  }

  private inner class AndroidBridge {
    @JavascriptInterface
    fun postMessage(message: String) {
      // Dispatch parsing + callbacks on main to mirror iOS and avoid UI thread violations.
      mainHandler.post {
        try {
          bridge.handleIncomingJson(message)
        } catch (t: Throwable) {
          NuxieLogger.warning("FlowWebView: Failed to handle bridge message: ${t.message}", t)
        }
      }
    }
  }
}
