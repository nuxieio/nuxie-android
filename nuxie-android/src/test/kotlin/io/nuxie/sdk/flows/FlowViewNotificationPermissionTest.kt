package io.nuxie.sdk.flows

import android.content.Context
import android.os.Build
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import io.nuxie.sdk.events.SystemEventNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FlowViewNotificationPermissionTest {
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
    flowView.triggerEvent = { event, properties -> triggered += event to properties }

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
      requestBehavior = { callback ->
        callback(true)
        true
      },
      notificationsEnabledAfterRequest = true,
    )
    val flowView = FlowView(activity).apply {
      notificationPermissionHandler = handler
      sdkIntProvider = { Build.VERSION_CODES.TIRAMISU }
    }

    val triggered = mutableListOf<String>()
    flowView.triggerEvent = { event, _ -> triggered += event }

    flowView.performRequestNotifications("journey_1")
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(1, handler.requestInvocations)
    assertEquals(listOf(SystemEventNames.notificationsEnabled), triggered)
  }

  @Test
  fun requestNotifications_emitsDeniedWhenPromptNeedsComponentActivityHost() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val handler = FakeNotificationPermissionHandler(
      notificationsEnabled = false,
      permissionGranted = false,
    )
    val flowView = FlowView(context).apply {
      notificationPermissionHandler = handler
      sdkIntProvider = { Build.VERSION_CODES.TIRAMISU }
    }

    val triggered = mutableListOf<Pair<String, Map<String, Any?>?>>()
    flowView.triggerEvent = { event, properties -> triggered += event to properties }

    flowView.performRequestNotifications("journey_1")
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(0, handler.requestInvocations)
    assertEquals(SystemEventNames.notificationsDenied, triggered.first().first)
    assertEquals("journey_1", triggered.first().second?.get("journey_id"))
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
    flowView.triggerEvent = { event, properties -> triggered += event to properties }

    flowView.performRequestNotifications()
    shadowOf(Looper.getMainLooper()).idle()

    assertEquals(1, triggered.size)
    assertEquals(SystemEventNames.notificationsDenied, triggered.first().first)
    assertNull(triggered.first().second)
  }
}

private class FakeNotificationPermissionHandler(
  private var notificationsEnabled: Boolean,
  private var permissionGranted: Boolean,
  private val requestBehavior: ((callback: (Boolean) -> Unit) -> Boolean)? = null,
  private val notificationsEnabledAfterRequest: Boolean? = null,
) : NotificationPermissionHandler {
  var requestInvocations: Int = 0

  override fun areNotificationsEnabled(context: Context): Boolean {
    return notificationsEnabled
  }

  override fun isPostNotificationsPermissionGranted(context: Context): Boolean {
    return permissionGranted
  }

  override fun requestPostNotificationsPermission(
    activity: ComponentActivity,
    onResult: (Boolean) -> Unit,
  ): Boolean {
    requestInvocations += 1
    val launched = requestBehavior?.invoke { granted ->
      permissionGranted = granted
      if (notificationsEnabledAfterRequest != null) {
        notificationsEnabled = notificationsEnabledAfterRequest
      }
      onResult(granted)
    } ?: false
    return launched
  }
}
