package io.nuxie.sdk.flows

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import io.nuxie.sdk.R
import io.nuxie.sdk.events.SystemEventNames
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.WeakHashMap

@RunWith(RobolectricTestRunner::class)
class FlowViewNotificationPermissionTest {
  @Before
  fun resetNotificationPermissionRequestRegistry() {
    NotificationPermissionRequestRegistry.resetForTest()
    RuntimePermissionRequestRegistry.resetForTest()
  }

  @Test
  fun requestNotifications_emitsEnabledWhenPermissionAlreadyGranted() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeNotificationPermissionHandler(
      notificationsEnabled = true,
      permissionGranted = true,
    )
    val flowView = FlowView(activity).apply {
      notificationPermissionHandler = handler
      sdkIntProvider = { Build.VERSION_CODES.TIRAMISU }
    }

    val triggered = mutableListOf<Pair<String, Map<String, Any?>?>>()
    flowView.notificationPermissionEventSink = { event, properties, _ ->
      triggered += event to properties
    }

    flowView.performRequestNotifications("journey_1")
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(0, handler.requestInvocations)
    assertEquals(1, triggered.size)
    assertEquals(SystemEventNames.notificationsEnabled, triggered.first().first)
    assertEquals("journey_1", triggered.first().second?.get("journey_id"))
  }

  @Test
  fun requestNotifications_requestsPermissionOnComponentActivityAndEmitsEnabled() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeNotificationPermissionHandler(
      notificationsEnabled = false,
      permissionGranted = false,
      notificationsEnabledAfterRequest = true,
    )
    val flowView = FlowView(activity).apply {
      notificationPermissionHandler = handler
      sdkIntProvider = { Build.VERSION_CODES.TIRAMISU }
    }

    val triggered = mutableListOf<String>()
    flowView.notificationPermissionEventSink = { event, _, _ -> triggered += event }

    flowView.performRequestNotifications("journey_1")
    val requestId = handler.requests.single().requestId
    handler.resolve(requestId, granted = true)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(1, handler.requestInvocations)
    assertEquals(listOf(SystemEventNames.notificationsEnabled), triggered)
  }

  @Test
  fun requestNotifications_emitsDeniedBelowApi33WhenNotificationsAreDisabled() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val handler = FakeNotificationPermissionHandler(
      notificationsEnabled = false,
      permissionGranted = false,
    )
    val flowView = FlowView(context).apply {
      notificationPermissionHandler = handler
      sdkIntProvider = { Build.VERSION_CODES.S_V2 }
    }

    val triggered = mutableListOf<Pair<String, Map<String, Any?>?>>()
    flowView.notificationPermissionEventSink = { event, properties, _ ->
      triggered += event to properties
    }

    flowView.performRequestNotifications()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(1, triggered.size)
    assertEquals(SystemEventNames.notificationsDenied, triggered.first().first)
    assertNull(triggered.first().second)
  }

  @Test
  fun requestNotifications_standaloneFlowRoutesResultToRuntimeOnly() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeNotificationPermissionHandler(
      notificationsEnabled = true,
      permissionGranted = true,
    )
    val flowView = FlowView(activity).apply {
      notificationPermissionHandler = handler
      sdkIntProvider = { Build.VERSION_CODES.TIRAMISU }
    }

    val runtimeEvents = mutableListOf<Pair<String, Map<String, Any?>?>>()
    flowView.notificationPermissionRuntimeEventSink = { event, properties ->
      runtimeEvents += event to properties
    }

    flowView.performRequestNotifications()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(
      listOf(SystemEventNames.notificationsEnabled to null),
      runtimeEvents,
    )
  }

  @Test
  fun requestNotifications_routesJourneyScopedResultsToDelegateReceiver() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeNotificationPermissionHandler(
      notificationsEnabled = true,
      permissionGranted = true,
    )
    val receiver = FakeNotificationPermissionEventReceiver()
    val flowView = FlowView(activity).apply {
      notificationPermissionHandler = handler
      sdkIntProvider = { Build.VERSION_CODES.TIRAMISU }
      runtimeDelegate = receiver
    }

    flowView.performRequestNotifications("journey_1")
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(
      listOf(
        SystemEventNames.notificationsEnabled to mapOf("journey_id" to "journey_1"),
      ),
      receiver.events,
    )
    assertTrue(receiver.runtimeMessages.isEmpty())
  }

  @Test
  fun requestNotifications_rebindsPendingRequestAfterHierarchyStateRestore() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeNotificationPermissionHandler(
      notificationsEnabled = false,
      permissionGranted = false,
      notificationsEnabledAfterRequest = true,
    )
    val stableViewId = R.id.nuxie_flow_view
    val flowView = FlowView(activity).apply {
      id = stableViewId
      notificationPermissionHandler = handler
      sdkIntProvider = { Build.VERSION_CODES.TIRAMISU }
    }

    flowView.performRequestNotifications("journey_1")
    shadowOf(Looper.getMainLooper()).idle()

    val savedState = SparseArray<Parcelable>()
    flowView.saveHierarchyState(savedState)

    val triggered = mutableListOf<Pair<String, Map<String, Any?>?>>()
    val restoredView = FlowView(activity).apply {
      id = stableViewId
      notificationPermissionHandler = handler
      sdkIntProvider = { Build.VERSION_CODES.TIRAMISU }
      notificationPermissionEventSink = { event, properties, _ ->
        triggered += event to properties
      }
    }
    restoredView.restoreHierarchyState(savedState)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(2, handler.requests.size)
    val initialRequest = handler.requests[0]
    val reboundRequest = handler.requests[1]
    assertEquals(true, initialRequest.launchIfNeeded)
    assertEquals(false, reboundRequest.launchIfNeeded)
    assertEquals(initialRequest.requestId, reboundRequest.requestId)

    handler.resolve(initialRequest.requestId, granted = true)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(
      listOf(
        SystemEventNames.notificationsEnabled to mapOf("journey_id" to "journey_1"),
      ),
      triggered,
    )
    assertEquals(stableViewId, flowView.id)
    assertEquals(stableViewId, restoredView.id)
  }

  @Test
  fun requestPermission_emitsGrantedWhenPermissionAlreadyGranted() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeRuntimePermissionHandler(permissionGranted = true)
    val flowView = FlowView(activity).apply {
      runtimePermissionHandler = handler
    }

    val triggered = mutableListOf<Pair<String, Map<String, Any?>?>>()
    flowView.permissionEventSink = { event, properties, _ ->
      triggered += event to properties
    }

    flowView.performRequestPermission("camera", "journey_1")
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(0, handler.requestInvocations)
    assertEquals(
      listOf(
        SystemEventNames.permissionGranted to
          mapOf("journey_id" to "journey_1", "type" to "camera"),
      ),
      triggered,
    )
  }

  @Test
  fun requestPermission_waitsForNotificationPromptToResolve() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val notificationHandler =
      FakeNotificationPermissionHandler(
        notificationsEnabled = false,
        permissionGranted = false,
        notificationsEnabledAfterRequest = true,
      )
    val permissionHandler = FakeRuntimePermissionHandler(permissionGranted = false)
    val flowView = FlowView(activity).apply {
      this.notificationPermissionHandler = notificationHandler
      this.runtimePermissionHandler = permissionHandler
      this.sdkIntProvider = { Build.VERSION_CODES.TIRAMISU }
    }

    flowView.performRequestNotifications("journey_1")
    flowView.performRequestPermission("camera", "journey_1")
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue(permissionHandler.requests.isEmpty())

    val notificationRequest = notificationHandler.requests.single()
    notificationHandler.resolve(notificationRequest.requestId, granted = true)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(1, permissionHandler.requests.size)
    assertEquals(listOf(Manifest.permission.CAMERA), permissionHandler.requests.single().permissions)
  }

  @Test
  fun requestPermission_requestsPhotosPermissionAndEmitsGranted() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeRuntimePermissionHandler(permissionGranted = false)
    val flowView = FlowView(activity).apply {
      runtimePermissionHandler = handler
      sdkIntProvider = { Build.VERSION_CODES.TIRAMISU }
    }

    val triggered = mutableListOf<String>()
    flowView.permissionEventSink = { event, _, _ -> triggered += event }

    flowView.performRequestPermission("photos", "journey_1")
    val request = handler.requests.single()
    assertEquals(listOf(Manifest.permission.READ_MEDIA_IMAGES), request.permissions)

    handler.resolve(request.requestId, granted = true)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(1, handler.requestInvocations)
    assertEquals(listOf(SystemEventNames.permissionGranted), triggered)
  }

  @Test
  fun requestPermission_requestsExplicitSelectedPhotosAccessOnAndroid14() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeRuntimePermissionHandler(permissionGranted = false)
    val flowView = FlowView(activity).apply {
      runtimePermissionHandler = handler
      sdkIntProvider = { Build.VERSION_CODES.UPSIDE_DOWN_CAKE }
    }

    flowView.performRequestPermission("photos", "journey_1")

    assertEquals(
      listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
      ),
      handler.requests.single().permissions,
    )
  }

  @Test
  fun requestPermission_reRequestsPhotosWhenOnlySelectedPhotosAccessExists() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler =
      FakeRuntimePermissionHandler(
        permissionGranted = false,
        grantedPermissions =
          mutableSetOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
      )
    val flowView = FlowView(activity).apply {
      runtimePermissionHandler = handler
      sdkIntProvider = { Build.VERSION_CODES.UPSIDE_DOWN_CAKE }
    }

    flowView.performRequestPermission("photos", "journey_1")

    assertEquals(1, handler.requestInvocations)
    assertEquals(
      listOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
      ),
      handler.requests.single().permissions,
    )
  }

  @Test
  fun requestPermission_emitsDeniedWhenOnlySelectedPhotosAccessIsGranted() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeRuntimePermissionHandler(permissionGranted = false)
    val flowView = FlowView(activity).apply {
      runtimePermissionHandler = handler
      sdkIntProvider = { Build.VERSION_CODES.UPSIDE_DOWN_CAKE }
    }

    val triggered = mutableListOf<Pair<String, Map<String, Any?>?>>()
    flowView.permissionEventSink = { event, properties, _ ->
      triggered += event to properties
    }

    flowView.performRequestPermission("photos", "journey_1")
    shadowOf(Looper.getMainLooper()).idle()

    val request = handler.requests.single()
    handler.resolve(
      request.requestId,
      granted = true,
      grantedPermissionsAfterRequest =
        setOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
    )
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(
      listOf(
        SystemEventNames.permissionDenied to
          mapOf("journey_id" to "journey_1", "type" to "photos"),
      ),
      triggered,
    )
  }

  @Test
  fun requestPermission_standaloneFlowRoutesResultToRuntimeOnly() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeRuntimePermissionHandler(permissionGranted = true)
    val flowView = FlowView(activity).apply {
      runtimePermissionHandler = handler
    }

    val runtimeEvents = mutableListOf<Pair<String, Map<String, Any?>?>>() 
    flowView.permissionRuntimeEventSink = { event, properties ->
      runtimeEvents += event to properties
    }

    flowView.performRequestPermission("microphone")
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(
      listOf(
        SystemEventNames.permissionGranted to mapOf("type" to "microphone"),
      ),
      runtimeEvents,
    )
  }

  @Test
  fun requestPermission_routesJourneyScopedResultsToDelegateReceiver() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeRuntimePermissionHandler(permissionGranted = true)
    val receiver = FakePermissionEventReceiver()
    val flowView = FlowView(activity).apply {
      runtimePermissionHandler = handler
      runtimeDelegate = receiver
    }

    flowView.performRequestPermission("camera", "journey_1")
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(
      listOf(
        SystemEventNames.permissionGranted to
          mapOf("journey_id" to "journey_1", "type" to "camera"),
      ),
      receiver.events,
    )
    assertTrue(receiver.runtimeMessages.isEmpty())
  }

  @Test
  fun requestPermission_rebindsPendingRequestAfterHierarchyStateRestore() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeRuntimePermissionHandler(permissionGranted = false)
    val stableViewId = R.id.nuxie_flow_view
    val flowView = FlowView(activity).apply {
      id = stableViewId
      runtimePermissionHandler = handler
    }

    flowView.performRequestPermission("microphone", "journey_1")
    shadowOf(Looper.getMainLooper()).idle()

    val savedState = SparseArray<Parcelable>()
    flowView.saveHierarchyState(savedState)

    val triggered = mutableListOf<Pair<String, Map<String, Any?>?>>()
    val restoredView = FlowView(activity).apply {
      id = stableViewId
      runtimePermissionHandler = handler
      permissionEventSink = { event, properties, _ ->
        triggered += event to properties
      }
    }
    restoredView.restoreHierarchyState(savedState)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(2, handler.requests.size)
    val initialRequest = handler.requests[0]
    val reboundRequest = handler.requests[1]
    assertEquals(listOf(Manifest.permission.RECORD_AUDIO), initialRequest.permissions)
    assertEquals(initialRequest.requestId, reboundRequest.requestId)
    assertEquals(true, initialRequest.launchIfNeeded)
    assertEquals(false, reboundRequest.launchIfNeeded)

    handler.resolve(initialRequest.requestId, granted = true)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(
      listOf(
        SystemEventNames.permissionGranted to
          mapOf("journey_id" to "journey_1", "type" to "microphone"),
      ),
      triggered,
    )
  }

  @Test
  fun requestPermission_queuesLaterPromptsUntilTheCurrentPromptResolves() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeRuntimePermissionHandler(permissionGranted = false)
    val flowView = FlowView(activity).apply {
      runtimePermissionHandler = handler
    }

    val triggered = mutableListOf<Pair<String, Map<String, Any?>?>>()
    flowView.permissionEventSink = { event, properties, _ ->
      triggered += event to properties
    }

    flowView.performRequestPermission("camera", "journey_1")
    flowView.performRequestPermission("microphone", "journey_1")
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(1, handler.requestInvocations)
    val firstRequest = handler.requests.single()
    assertEquals(listOf(Manifest.permission.CAMERA), firstRequest.permissions)

    handler.resolve(firstRequest.requestId, granted = false)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(2, handler.requestInvocations)
    val secondRequest = handler.requests[1]
    assertNotEquals(firstRequest.requestId, secondRequest.requestId)
    assertEquals(listOf(Manifest.permission.RECORD_AUDIO), secondRequest.permissions)

    handler.resolve(secondRequest.requestId, granted = true)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(
      listOf(
        SystemEventNames.permissionDenied to
          mapOf("journey_id" to "journey_1", "type" to "camera"),
        SystemEventNames.permissionGranted to
          mapOf("journey_id" to "journey_1", "type" to "microphone"),
      ),
      triggered,
    )
  }

  @Test
  fun requestPermission_restoresQueuedPromptsAfterHierarchyStateRestore() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeRuntimePermissionHandler(permissionGranted = false)
    val stableViewId = R.id.nuxie_flow_view
    val flowView = FlowView(activity).apply {
      id = stableViewId
      runtimePermissionHandler = handler
    }

    flowView.performRequestPermission("camera", "journey_1")
    flowView.performRequestPermission("microphone", "journey_1")
    shadowOf(Looper.getMainLooper()).idle()

    val savedState = SparseArray<Parcelable>()
    flowView.saveHierarchyState(savedState)

    val restoredView = FlowView(activity).apply {
      id = stableViewId
      runtimePermissionHandler = handler
    }
    restoredView.restoreHierarchyState(savedState)
    shadowOf(Looper.getMainLooper()).idle()

    val activeRequest = handler.requests.first()
    handler.resolve(activeRequest.requestId, granted = false)
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(3, handler.requests.size)
    val reboundRequest = handler.requests[1]
    val queuedRequest = handler.requests[2]
    assertEquals(activeRequest.requestId, reboundRequest.requestId)
    assertEquals(true, queuedRequest.launchIfNeeded)
    assertEquals(listOf(Manifest.permission.RECORD_AUDIO), queuedRequest.permissions)
  }

  @Test
  fun requestPermission_dropsPhantomRestoredRequestAfterProcessDeath() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = FakeRuntimePermissionHandler(permissionGranted = false)
    val stableViewId = R.id.nuxie_flow_view
    val flowView = FlowView(activity).apply {
      id = stableViewId
      runtimePermissionHandler = handler
    }

    flowView.performRequestPermission("camera", "journey_1")
    shadowOf(Looper.getMainLooper()).idle()

    val savedState = SparseArray<Parcelable>()
    flowView.saveHierarchyState(savedState)

    RuntimePermissionRequestRegistry.resetForTest()

    val restoredView = FlowView(activity).apply {
      id = stableViewId
      runtimePermissionHandler = handler
    }
    restoredView.restoreHierarchyState(savedState)
    shadowOf(Looper.getMainLooper()).idle()

    restoredView.performRequestPermission("microphone", "journey_1")
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(2, handler.requests.size)
    val relaunchedRequest = handler.requests[1]
    assertEquals(true, relaunchedRequest.launchIfNeeded)
    assertEquals(listOf(Manifest.permission.RECORD_AUDIO), relaunchedRequest.permissions)
  }

  @Test
  fun notificationPermissionRequestRegistry_deliversPendingResultToReboundCallback() {
    val requestId = "req_1"
    var reboundGranted: Boolean? = null

    NotificationPermissionRequestRegistry.markLaunched(requestId)
    NotificationPermissionRequestRegistry.complete(requestId, granted = true)
    NotificationPermissionRequestRegistry.bind(requestId) { granted ->
      reboundGranted = granted
    }

    assertEquals(true, reboundGranted)
  }

  @Test
  fun defaultNotificationPermissionHandler_keepsComponentActivityLauncherRegisteredBeforeLaunch() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val handler = DefaultNotificationPermissionHandler()
    val requestId = "req_component_activity"

    val launched =
      handler.requestPostNotificationsPermission(
        activity = activity,
        requestId = requestId,
        launchIfNeeded = true,
      ) {}

    assertTrue(launched)

    val field = DefaultNotificationPermissionHandler::class.java.getDeclaredField("activityResultLaunchers")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val launcherMap =
      field.get(handler) as WeakHashMap<ComponentActivity, MutableMap<String, Any?>>
    val activityLaunchers: MutableMap<String, Any?>? = launcherMap[activity]

    assertNotNull(activityLaunchers)
    assertTrue(activityLaunchers!!.containsKey(requestId))
  }

  @Test
  fun flowViews_useUniqueIdsByDefault() {
    val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    val first = FlowView(activity)
    val second = FlowView(activity)

    assertNotEquals(View.NO_ID, first.id)
    assertNotEquals(View.NO_ID, second.id)
    assertNotEquals(first.id, second.id)
  }

}

private class FakeNotificationPermissionEventReceiver :
  FlowRuntimeDelegate,
  NotificationPermissionEventReceiver {
  val events = mutableListOf<Pair<String, Map<String, Any?>>>()
  val runtimeMessages = mutableListOf<Triple<String, JsonObject, String?>>()

  override fun onRuntimeMessage(type: String, payload: JsonObject, id: String?) {
    runtimeMessages += Triple(type, payload, id)
  }

  override fun onDismissRequested(reason: CloseReason) = Unit

  override fun onNotificationPermissionEvent(
    eventName: String,
    properties: Map<String, Any?>,
  ) {
    events += eventName to properties
  }
}

private class FakeNotificationPermissionHandler(
  private var notificationsEnabled: Boolean,
  private var permissionGranted: Boolean,
  private val notificationsEnabledAfterRequest: Boolean? = null,
) : NotificationPermissionHandler {
  data class Request(val requestId: String, val launchIfNeeded: Boolean)

  var requestInvocations: Int = 0
  val requests = mutableListOf<Request>()
  private val callbacks = mutableMapOf<String, (Boolean) -> Unit>()

  override fun areNotificationsEnabled(context: Context): Boolean {
    return notificationsEnabled
  }

  override fun isPostNotificationsPermissionGranted(context: Context): Boolean {
    return permissionGranted
  }

  override fun requestPostNotificationsPermission(
    activity: ComponentActivity,
    requestId: String,
    launchIfNeeded: Boolean,
    onResult: (Boolean) -> Unit,
  ): Boolean {
    requestInvocations += 1
    requests += Request(requestId = requestId, launchIfNeeded = launchIfNeeded)
    callbacks[requestId] = onResult
    return true
  }

  fun resolve(requestId: String, granted: Boolean) {
    permissionGranted = granted
    if (notificationsEnabledAfterRequest != null) {
      notificationsEnabled = notificationsEnabledAfterRequest
    }
    callbacks.remove(requestId)?.invoke(granted)
  }
}

private class FakePermissionEventReceiver : FlowRuntimeDelegate, PermissionEventReceiver {
  val events = mutableListOf<Pair<String, Map<String, Any?>>>()
  val runtimeMessages = mutableListOf<Triple<String, JsonObject, String?>>()

  override fun onRuntimeMessage(type: String, payload: JsonObject, id: String?) {
    runtimeMessages += Triple(type, payload, id)
  }

  override fun onDismissRequested(reason: CloseReason) = Unit

  override fun onPermissionEvent(eventName: String, properties: Map<String, Any?>) {
    events += eventName to properties
  }
}

private class FakeRuntimePermissionHandler(
  private var permissionGranted: Boolean,
  private val grantedPermissions: MutableSet<String> = mutableSetOf(),
) : RuntimePermissionHandler {
  data class Request(
    val permissions: List<String>,
    val requestId: String,
    val launchIfNeeded: Boolean,
  )

  var requestInvocations: Int = 0
  val requests = mutableListOf<Request>()
  private val permissionsByRequestId = mutableMapOf<String, List<String>>()

  override fun hasPermissionAccess(context: Context, permissions: List<String>): Boolean {
    return permissionGranted || permissions.any { permission -> grantedPermissions.contains(permission) }
  }

  override fun requestPermissions(
    activity: ComponentActivity,
    permissions: List<String>,
    requestId: String,
    launchIfNeeded: Boolean,
    onResult: (Boolean) -> Unit,
  ): Boolean {
    requestInvocations += 1
    requests +=
      Request(
        permissions = permissions,
        requestId = requestId,
        launchIfNeeded = launchIfNeeded,
      )
    permissionsByRequestId[requestId] = permissions
    if (launchIfNeeded) {
      RuntimePermissionRequestRegistry.markLaunched(requestId)
    }
    RuntimePermissionRequestRegistry.bind(requestId, onResult)
    return true
  }

  fun resolve(
    requestId: String,
    granted: Boolean,
    grantedPermissionsAfterRequest: Set<String>? = null,
  ) {
    val requestedPermissions = permissionsByRequestId.remove(requestId).orEmpty()
    if (grantedPermissionsAfterRequest != null) {
      permissionGranted = false
      grantedPermissions.clear()
      grantedPermissions.addAll(grantedPermissionsAfterRequest)
    } else {
      permissionGranted = false
      if (granted) {
        grantedPermissions.addAll(requestedPermissions)
      }
    }
    RuntimePermissionRequestRegistry.complete(requestId, granted)
  }
}
