package io.nuxie.sdk.flows

import android.Manifest
import android.app.Activity
import android.app.Fragment
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.nuxie.sdk.NuxieSDK
import io.nuxie.sdk.events.SystemEventNames
import io.nuxie.sdk.logging.NuxieLogger
import io.nuxie.sdk.purchases.NuxiePurchaseDelegate
import io.nuxie.sdk.purchases.PurchaseResult
import io.nuxie.sdk.purchases.RestoreResult
import io.nuxie.sdk.util.toJsonObject
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
import kotlin.math.max

internal interface NotificationPermissionHandler {
  fun areNotificationsEnabled(context: Context): Boolean
  fun isPostNotificationsPermissionGranted(context: Context): Boolean
  fun requestPostNotificationsPermission(
    activity: Activity,
    onResult: (Boolean) -> Unit,
  ): Boolean
}

internal interface NotificationPermissionEventReceiver {
  fun onNotificationPermissionEvent(
    eventName: String,
    properties: Map<String, Any?>,
  )
}

internal class DefaultNotificationPermissionHandler : NotificationPermissionHandler {
  override fun areNotificationsEnabled(context: Context): Boolean {
    return NotificationManagerCompat.from(context).areNotificationsEnabled()
  }

  override fun isPostNotificationsPermissionGranted(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
  }

  override fun requestPostNotificationsPermission(
    activity: Activity,
    onResult: (Boolean) -> Unit,
  ): Boolean {
    return if (activity is ComponentActivity) {
      requestWithActivityResultRegistry(activity, onResult)
    } else {
      LegacyNotificationPermissionFragment.request(activity, onResult)
    }
  }

  private fun requestWithActivityResultRegistry(
    activity: ComponentActivity,
    onResult: (Boolean) -> Unit,
  ): Boolean {
    return runCatching {
      val key = "nuxie.notifications.${System.nanoTime()}"
      lateinit var launcher: ActivityResultLauncher<String>
      launcher = activity.activityResultRegistry.register(
        key,
        ActivityResultContracts.RequestPermission(),
      ) { granted ->
        launcher.unregister()
        onResult(granted)
      }
      launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }.onFailure {
      NuxieLogger.warning("FlowView: Failed to request notification permission: ${it.message}", it)
    }.isSuccess
  }
}

@Suppress("DEPRECATION")
private class LegacyNotificationPermissionFragment : Fragment() {
  private var onResult: ((Boolean) -> Unit)? = null
  private var didRequestPermission: Boolean = false

  fun startRequest(onResult: (Boolean) -> Unit) {
    this.onResult = onResult
    if (didRequestPermission || !isAdded) return
    didRequestPermission = true
    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
  }

  override fun onRequestPermissionsResult(
    requestCode: Int,
    permissions: Array<out String>,
    grantResults: IntArray,
  ) {
    if (requestCode != REQUEST_CODE) {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults)
      return
    }

    val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
    onResult?.invoke(granted)
    onResult = null
    activity?.fragmentManager?.beginTransaction()?.remove(this)?.commitAllowingStateLoss()
  }

  companion object {
    private const val TAG = "io.nuxie.sdk.notifications.permission"
    private const val REQUEST_CODE = 41073

    fun request(
      activity: Activity,
      onResult: (Boolean) -> Unit,
    ): Boolean {
      if (activity.isFinishing) {
        return false
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) {
        return false
      }

      val manager = activity.fragmentManager
      if (manager.findFragmentByTag(TAG) != null) {
        return false
      }

      return runCatching {
        val fragment =
          LegacyNotificationPermissionFragment().also {
            manager.beginTransaction().add(it, TAG).commitAllowingStateLoss()
            manager.executePendingTransactions()
          }

        fragment.startRequest(onResult)
      }.onFailure {
        NuxieLogger.warning(
          "FlowView: Failed to request notification permission from Activity host: ${it.message}",
          it,
        )
      }.isSuccess
    }
  }
}

class FlowView(context: Context) : FrameLayout(context) {

  private enum class State {
    LOADING,
    LOADED,
    ERROR,
  }

  var runtimeDelegate: FlowRuntimeDelegate? = null
  var onClose: ((CloseReason) -> Unit)? = null
  var onDismissRequested: ((CloseReason) -> Unit)? = null
  var colorSchemeMode: FlowColorSchemeMode = FlowColorSchemeMode.LIGHT
    set(value) {
      field = value
      sendColorSchemeToRuntime()
    }

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
  internal var notificationPermissionHandler: NotificationPermissionHandler = DefaultNotificationPermissionHandler()
  internal var sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
  internal var notificationPermissionEventSink:
    (eventName: String, properties: Map<String, Any?>?, journeyId: String?) -> Unit =
    { eventName, properties, journeyId ->
      dispatchNotificationPermissionEvent(
        eventName = eventName,
        properties = properties,
        journeyId = journeyId,
      )
    }

  private data class SafeAreaInsets(
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
  )

  private var latestSafeAreaInsets = SafeAreaInsets(top = 0, bottom = 0, left = 0, right = 0)
  private var dispatchedSafeAreaInsets: SafeAreaInsets? = null

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    requestApplyInsets()
  }

  override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
    latestSafeAreaInsets = readSafeAreaInsets(insets)
    dispatchSafeAreaInsets()
    return super.onApplyWindowInsets(insets)
  }

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
    post { requestApplyInsets() }

    // Configure interception: cache-first bundle + fonts
    val interceptor = FlowResourceInterceptor(flow = flow, fontStore = fontStore)
    interceptor.setBundleDir(bundleStore.getCachedBundleDir(flow))
    webView.setResourceInterceptor(interceptor)
    webView.resetBridge()
    sendColorSchemeToRuntime()

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

  fun performPurchase(productId: String) {
    handlePurchase(productId)
  }

  fun performRestore() {
    handleRestore()
  }

  fun performRequestNotifications(journeyId: String? = null) {
    val action = { handleRequestNotifications(journeyId) }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      action()
    } else {
      post { action() }
    }
  }

  fun performOpenLink(urlString: String, target: String?) {
    openLinkInternal(urlString, target)
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
      "runtime/ready" -> {
        runtimeDelegate?.onRuntimeMessage(type, payload, id)
        // Runtime expressions require numeric inset values; resend on every runtime boot.
        dispatchSafeAreaInsets(force = true)
        sendColorSchemeToRuntime()
      }
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

      "action/request_notifications" -> {
        if (runtimeDelegate != null) {
          runtimeDelegate?.onRuntimeMessage(type, payload, id)
        } else {
          performRequestNotifications()
        }
      }

      "action/open_link" -> {
        if (runtimeDelegate != null) {
          runtimeDelegate?.onRuntimeMessage(type, payload, id)
        } else {
          val url = (payload["url"] as? JsonPrimitive)?.contentOrNull ?: return
          val target = (payload["target"] as? JsonPrimitive)?.contentOrNull
          openLinkInternal(url, target)
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

  private fun readSafeAreaInsets(insets: WindowInsets?): SafeAreaInsets {
    if (insets == null) {
      return SafeAreaInsets(top = 0, bottom = 0, left = 0, right = 0)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      val resolved = insets.getInsetsIgnoringVisibility(
        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
      )
      return SafeAreaInsets(
        top = resolved.top,
        bottom = resolved.bottom,
        left = resolved.left,
        right = resolved.right,
      )
    }

    @Suppress("DEPRECATION")
    var top = insets.systemWindowInsetTop
    @Suppress("DEPRECATION")
    var bottom = insets.systemWindowInsetBottom
    @Suppress("DEPRECATION")
    var left = insets.systemWindowInsetLeft
    @Suppress("DEPRECATION")
    var right = insets.systemWindowInsetRight

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      val cutout = insets.displayCutout
      if (cutout != null) {
        top = max(top, cutout.safeInsetTop)
        bottom = max(bottom, cutout.safeInsetBottom)
        left = max(left, cutout.safeInsetLeft)
        right = max(right, cutout.safeInsetRight)
      }
    }

    return SafeAreaInsets(top = top, bottom = bottom, left = left, right = right)
  }

  private fun readCurrentSafeAreaInsets(): SafeAreaInsets {
    val insets =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) rootWindowInsets else null
    return readSafeAreaInsets(insets)
  }

  private fun dispatchSafeAreaInsets(force: Boolean = false) {
    if (!::webView.isInitialized) return

    val currentInsets =
      if (latestSafeAreaInsets == SafeAreaInsets(0, 0, 0, 0)) {
        readCurrentSafeAreaInsets()
      } else {
        latestSafeAreaInsets
      }

    // Avoid queueing stale inset snapshots before runtime readiness; send latest once ready.
    if (!webView.isRuntimeReady()) {
      latestSafeAreaInsets = currentInsets
      return
    }

    if (!force && dispatchedSafeAreaInsets == currentInsets) return

    latestSafeAreaInsets = currentInsets
    dispatchedSafeAreaInsets = currentInsets

    webView.sendBridgeMessage(
      type = "system/safe_area_insets",
      payload = buildJsonObject {
        put("top", JsonPrimitive(currentInsets.top))
        put("bottom", JsonPrimitive(currentInsets.bottom))
        put("left", JsonPrimitive(currentInsets.left))
        put("right", JsonPrimitive(currentInsets.right))
      },
    )
  }

  private fun sendColorSchemeToRuntime() {
    if (!::webView.isInitialized) return
    val payload = buildJsonObject {
      put("mode", JsonPrimitive(colorSchemeMode.rawValue))
    }
    webView.sendBridgeMessage(type = "runtime/color_scheme", payload = payload)
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

  private fun handleRequestNotifications(journeyId: String?) {
    val properties = buildNotificationEventProperties(journeyId)
    val emitEnabled = {
      emitNotificationPermissionEvent(
        SystemEventNames.notificationsEnabled,
        properties,
        journeyId,
      )
    }
    val emitDenied = {
      emitNotificationPermissionEvent(
        SystemEventNames.notificationsDenied,
        properties,
        journeyId,
      )
    }

    if (sdkIntProvider() < Build.VERSION_CODES.TIRAMISU) {
      if (notificationPermissionHandler.areNotificationsEnabled(context)) {
        emitEnabled()
      } else {
        emitDenied()
      }
      return
    }

    val notificationsEnabled = notificationPermissionHandler.areNotificationsEnabled(context)
    val permissionGranted = notificationPermissionHandler.isPostNotificationsPermissionGranted(context)

    if (permissionGranted && notificationsEnabled) {
      emitEnabled()
      return
    }

    if (!permissionGranted) {
      val activity = findActivity(context)
      if (activity == null) {
        NuxieLogger.warning(
          "FlowView: Notification permission prompt requires an Activity host; emitting denied",
        )
        emitDenied()
        return
      }
      val launched = notificationPermissionHandler.requestPostNotificationsPermission(activity) { granted ->
        val enabledAfterResult = notificationPermissionHandler.areNotificationsEnabled(context)
        if (granted && enabledAfterResult) {
          emitEnabled()
        } else {
          emitDenied()
        }
      }
      if (!launched) {
        emitDenied()
      }
      return
    }

    emitDenied()
  }

  private fun buildNotificationEventProperties(journeyId: String?): Map<String, Any?>? {
    return if (journeyId.isNullOrBlank()) {
      null
    } else {
      mapOf("journey_id" to journeyId)
    }
  }

  private fun emitNotificationPermissionEvent(
    eventName: String,
    properties: Map<String, Any?>?,
    journeyId: String?,
  ) {
    notificationPermissionEventSink(eventName, properties, journeyId)
  }

  private fun dispatchNotificationPermissionEvent(
    eventName: String,
    properties: Map<String, Any?>?,
    journeyId: String?,
  ) {
    val scopedProperties = properties ?: emptyMap()
    val receiver = runtimeDelegate as? NotificationPermissionEventReceiver
    if (!journeyId.isNullOrBlank() && receiver != null) {
      receiver.onNotificationPermissionEvent(
        eventName = eventName,
        properties = scopedProperties,
      )
      return
    }

    sendNotificationPermissionEventToRuntime(
      eventName = eventName,
      properties = properties,
    )
    NuxieSDK.shared().trigger(eventName, properties = properties)
  }

  private fun sendNotificationPermissionEventToRuntime(
    eventName: String,
    properties: Map<String, Any?>?,
  ) {
    if (!::webView.isInitialized) return

    val payload = buildMap<String, Any?> {
      put("name", eventName)
      if (!properties.isNullOrEmpty()) {
        put("properties", properties)
      }
    }
    webView.sendBridgeMessage(
      type = "action/event",
      payload = toJsonObject(payload),
    )
  }

  private fun findActivity(context: Context): Activity? {
    var current: Context? = context
    while (current is ContextWrapper) {
      if (current is Activity) {
        return current
      }
      current = current.baseContext
    }
    return current as? Activity
  }

  private fun openLinkInternal(urlString: String, target: String?) {
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
