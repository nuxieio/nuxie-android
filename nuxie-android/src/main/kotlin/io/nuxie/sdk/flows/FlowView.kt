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
import android.os.Parcel
import android.os.Parcelable
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
import io.nuxie.sdk.R
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
import java.util.UUID
import java.util.WeakHashMap

internal interface NotificationPermissionHandler {
  fun areNotificationsEnabled(context: Context): Boolean
  fun isPostNotificationsPermissionGranted(context: Context): Boolean
  fun requestPostNotificationsPermission(
    activity: Activity,
    requestId: String,
    launchIfNeeded: Boolean,
    onResult: (Boolean) -> Unit,
  ): Boolean
}

internal interface NotificationPermissionEventReceiver {
  fun onNotificationPermissionEvent(
    eventName: String,
    properties: Map<String, Any?>,
  )
}

internal object NotificationPermissionRequestRegistry {
  private val callbacks: MutableMap<String, (Boolean) -> Unit> = mutableMapOf()
  private val pendingResults: MutableMap<String, Boolean> = mutableMapOf()
  private val inFlightRequestIds: MutableSet<String> = mutableSetOf()
  private val lock = Any()

  fun bind(requestId: String, onResult: (Boolean) -> Unit) {
    val pendingResult =
      synchronized(lock) {
        callbacks[requestId] = onResult
        pendingResults.remove(requestId)
      }

    if (pendingResult != null) {
      synchronized(lock) {
        callbacks.remove(requestId)
      }
      onResult(pendingResult)
    }
  }

  fun markLaunched(requestId: String): Boolean {
    synchronized(lock) {
      if (inFlightRequestIds.contains(requestId)) return false
      inFlightRequestIds += requestId
      return true
    }
  }

  fun complete(requestId: String, granted: Boolean) {
    val callback =
      synchronized(lock) {
        inFlightRequestIds.remove(requestId)
        callbacks.remove(requestId).also { existing ->
          if (existing == null) {
            pendingResults[requestId] = granted
          }
        }
      }
    callback?.invoke(granted)
  }

  fun hasPendingWork(requestId: String): Boolean {
    synchronized(lock) {
      return inFlightRequestIds.contains(requestId) || pendingResults.containsKey(requestId)
    }
  }

  fun clear(requestId: String) {
    synchronized(lock) {
      callbacks.remove(requestId)
      pendingResults.remove(requestId)
      inFlightRequestIds.remove(requestId)
    }
  }

  internal fun resetForTest() {
    synchronized(lock) {
      callbacks.clear()
      pendingResults.clear()
      inFlightRequestIds.clear()
    }
  }
}

@Suppress("DEPRECATION")
internal object FlowViewHostStateRegistry {
  private const val TAG = "io.nuxie.sdk.flow.host.state"

  fun acquireStableViewId(activity: Activity): Int {
    return retainedFragment(activity).acquireStableViewId(activity)
  }

  private fun retainedFragment(activity: Activity): FlowViewHostStateFragment {
    val manager = activity.fragmentManager
    val existing = manager.findFragmentByTag(TAG) as? FlowViewHostStateFragment
    if (existing != null) {
      return existing
    }

    val fragment = FlowViewHostStateFragment()
    manager.beginTransaction().add(fragment, TAG).commitAllowingStateLoss()
    manager.executePendingTransactions()
    return fragment
  }
}

@Suppress("DEPRECATION")
internal class FlowViewHostStateFragment : Fragment() {
  private var hostIdentityToken: Int? = null
  private var nextAllocationIndex: Int = 0
  private val stableViewIds: MutableList<Int> = mutableListOf()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    retainInstance = true
  }

  fun acquireStableViewId(activity: Activity): Int {
    val currentToken = System.identityHashCode(activity)
    if (hostIdentityToken != currentToken) {
      hostIdentityToken = currentToken
      nextAllocationIndex = 0
    }

    while (stableViewIds.size <= nextAllocationIndex) {
      stableViewIds += View.generateViewId()
    }

    return stableViewIds[nextAllocationIndex++]
  }
}

internal class DefaultNotificationPermissionHandler : NotificationPermissionHandler {
  private val activityResultLaunchers:
    MutableMap<ComponentActivity, MutableMap<String, ActivityResultLauncher<String>>> = WeakHashMap()

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
    requestId: String,
    launchIfNeeded: Boolean,
    onResult: (Boolean) -> Unit,
  ): Boolean {
    return if (activity is ComponentActivity) {
      requestWithActivityResultRegistry(
        activity = activity,
        requestId = requestId,
        launchIfNeeded = launchIfNeeded,
        onResult = onResult,
      )
    } else {
      LegacyNotificationPermissionFragment.request(
        activity = activity,
        requestId = requestId,
        launchIfNeeded = launchIfNeeded,
        onResult = onResult,
      )
    }
  }

  private fun requestWithActivityResultRegistry(
    activity: ComponentActivity,
    requestId: String,
    launchIfNeeded: Boolean,
    onResult: (Boolean) -> Unit,
  ): Boolean {
    val shouldLaunch =
      if (launchIfNeeded) {
        NotificationPermissionRequestRegistry.markLaunched(requestId)
      } else {
        false
      }
    NotificationPermissionRequestRegistry.bind(requestId, onResult)

    return runCatching {
      val launcher = notificationPermissionLauncher(activity, requestId)
      if (shouldLaunch) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
      }
    }.onFailure {
      NotificationPermissionRequestRegistry.clear(requestId)
      NuxieLogger.warning("FlowView: Failed to request notification permission: ${it.message}", it)
    }.isSuccess
  }

  private fun notificationPermissionLauncher(
    activity: ComponentActivity,
    requestId: String,
  ): ActivityResultLauncher<String> {
    synchronized(activityResultLaunchers) {
      val launchersForActivity = activityResultLaunchers.getOrPut(activity) { mutableMapOf() }
      launchersForActivity[requestId]?.let { return it }

      val launcher =
        activity.activityResultRegistry.register(
          notificationPermissionActivityResultKey(requestId),
          ActivityResultContracts.RequestPermission(),
        ) { granted ->
          NotificationPermissionRequestRegistry.complete(requestId, granted)
          unregisterNotificationPermissionLauncher(activity, requestId)
        }

      launchersForActivity[requestId] = launcher
      if (!NotificationPermissionRequestRegistry.hasPendingWork(requestId)) {
        unregisterNotificationPermissionLauncher(activity, requestId)
      }
      return launcher
    }
  }

  private fun unregisterNotificationPermissionLauncher(
    activity: ComponentActivity,
    requestId: String,
  ) {
    synchronized(activityResultLaunchers) {
      val launchersForActivity = activityResultLaunchers[activity] ?: return
      val launcher = launchersForActivity.remove(requestId) ?: return
      launcher.unregister()
      if (launchersForActivity.isEmpty()) {
        activityResultLaunchers.remove(activity)
      }
    }
  }

  companion object {
    internal fun notificationPermissionActivityResultKey(requestId: String): String {
      return "io.nuxie.sdk.notifications.permission.$requestId"
    }
  }
}

@Suppress("DEPRECATION")
private class LegacyNotificationPermissionFragment : Fragment() {
  private var didRequestPermission: Boolean = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    didRequestPermission = savedInstanceState?.getBoolean(STATE_DID_REQUEST_PERMISSION) ?: false
  }

  override fun onResume() {
    super.onResume()
    requestPermissionIfNeeded()
  }

  override fun onSaveInstanceState(outState: Bundle) {
    outState.putBoolean(STATE_DID_REQUEST_PERMISSION, didRequestPermission)
    super.onSaveInstanceState(outState)
  }

  private fun requestPermissionIfNeeded() {
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
    NotificationPermissionRequestRegistry.complete(requestId(), granted)
    activity?.fragmentManager?.beginTransaction()?.remove(this)?.commitAllowingStateLoss()
  }

  private fun requestId(): String {
    return arguments?.getString(ARG_REQUEST_ID).orEmpty()
  }

  companion object {
    private const val TAG_PREFIX = "io.nuxie.sdk.notifications.permission"
    private const val REQUEST_CODE = 41073
    private const val ARG_REQUEST_ID = "request_id"
    private const val STATE_DID_REQUEST_PERMISSION = "did_request_permission"

    fun request(
      activity: Activity,
      requestId: String,
      launchIfNeeded: Boolean,
      onResult: (Boolean) -> Unit,
    ): Boolean {
      if (activity.isFinishing) {
        return false
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) {
        return false
      }

      NotificationPermissionRequestRegistry.bind(requestId, onResult)

      val manager = activity.fragmentManager
      val tag = fragmentTag(requestId)
      if (manager.findFragmentByTag(tag) != null) {
        return true
      }

      if (!launchIfNeeded) {
        return NotificationPermissionRequestRegistry.hasPendingWork(requestId)
      }

      if (!NotificationPermissionRequestRegistry.markLaunched(requestId)) {
        return true
      }

      return runCatching {
        val fragment =
          LegacyNotificationPermissionFragment().also {
            it.arguments = Bundle().apply {
              putString(ARG_REQUEST_ID, requestId)
            }
            manager.beginTransaction().add(it, tag).commitAllowingStateLoss()
            manager.executePendingTransactions()
          }
      }.onFailure {
        NotificationPermissionRequestRegistry.clear(requestId)
        NuxieLogger.warning(
          "FlowView: Failed to request notification permission from Activity host: ${it.message}",
          it,
        )
      }.isSuccess
    }

    private fun fragmentTag(requestId: String): String = "$TAG_PREFIX.$requestId"
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
  private var pendingNotificationPermissionRequestId: String? = null
  private var pendingNotificationPermissionJourneyId: String? = null
  internal var notificationPermissionHandler: NotificationPermissionHandler = DefaultNotificationPermissionHandler()
  internal var sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
  internal var notificationPermissionRuntimeEventSink:
    (eventName: String, properties: Map<String, Any?>?) -> Unit =
    { eventName, properties ->
      sendNotificationPermissionEventToRuntime(
        eventName = eventName,
        properties = properties,
      )
    }
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

  init {
    if (id == View.NO_ID) {
      id = View.generateViewId()
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    requestApplyInsets()
    rebindPendingNotificationPermissionRequestIfNeeded()
  }

  override fun onSaveInstanceState(): Parcelable? {
    val state = SavedState(super.onSaveInstanceState())
    state.pendingNotificationPermissionRequestId = pendingNotificationPermissionRequestId
    state.pendingNotificationPermissionJourneyId = pendingNotificationPermissionJourneyId
    return state
  }

  override fun onRestoreInstanceState(state: Parcelable?) {
    if (state !is SavedState) {
      super.onRestoreInstanceState(state)
      return
    }

    super.onRestoreInstanceState(state.superState)
    pendingNotificationPermissionRequestId = state.pendingNotificationPermissionRequestId
    pendingNotificationPermissionJourneyId = state.pendingNotificationPermissionJourneyId
    rebindPendingNotificationPermissionRequestIfNeeded()
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
    rebindPendingNotificationPermissionRequestIfNeeded()
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
      val requestId = pendingNotificationPermissionRequestId ?: UUID.randomUUID().toString()
      pendingNotificationPermissionRequestId = requestId
      pendingNotificationPermissionJourneyId = journeyId
      val launched =
        notificationPermissionHandler.requestPostNotificationsPermission(
          activity = activity,
          requestId = requestId,
          launchIfNeeded = true,
          onResult = notificationPermissionResultCallback(journeyId),
        )
      if (!launched) {
        clearPendingNotificationPermissionRequest()
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

    notificationPermissionRuntimeEventSink(eventName, properties)
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

  private fun rebindPendingNotificationPermissionRequestIfNeeded() {
    val requestId = pendingNotificationPermissionRequestId ?: return
    val activity = findActivity(context) ?: return
    notificationPermissionHandler.requestPostNotificationsPermission(
      activity = activity,
      requestId = requestId,
      launchIfNeeded = false,
      onResult = notificationPermissionResultCallback(pendingNotificationPermissionJourneyId),
    )
  }

  private fun notificationPermissionResultCallback(journeyId: String?): (Boolean) -> Unit {
    return { granted ->
      clearPendingNotificationPermissionRequest()
      val properties = buildNotificationEventProperties(journeyId)
      val enabledAfterResult = notificationPermissionHandler.areNotificationsEnabled(context)
      if (granted && enabledAfterResult) {
        emitNotificationPermissionEvent(
          eventName = SystemEventNames.notificationsEnabled,
          properties = properties,
          journeyId = journeyId,
        )
      } else {
        emitNotificationPermissionEvent(
          eventName = SystemEventNames.notificationsDenied,
          properties = properties,
          journeyId = journeyId,
        )
      }
    }
  }

  private fun clearPendingNotificationPermissionRequest() {
    val requestId = pendingNotificationPermissionRequestId
    pendingNotificationPermissionRequestId = null
    pendingNotificationPermissionJourneyId = null
    if (requestId != null) {
      NotificationPermissionRequestRegistry.clear(requestId)
    }
  }

  private class SavedState : View.BaseSavedState {
    var pendingNotificationPermissionRequestId: String? = null
    var pendingNotificationPermissionJourneyId: String? = null

    constructor(superState: Parcelable?) : super(superState)

    private constructor(source: Parcel) : super(source) {
      pendingNotificationPermissionRequestId = source.readString()
      pendingNotificationPermissionJourneyId = source.readString()
    }

    override fun writeToParcel(out: Parcel, flags: Int) {
      super.writeToParcel(out, flags)
      out.writeString(pendingNotificationPermissionRequestId)
      out.writeString(pendingNotificationPermissionJourneyId)
    }

    companion object {
      @JvmField
      val CREATOR: Parcelable.Creator<SavedState> =
        object : Parcelable.Creator<SavedState> {
          override fun createFromParcel(source: Parcel): SavedState = SavedState(source)

          override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
        }
    }
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
