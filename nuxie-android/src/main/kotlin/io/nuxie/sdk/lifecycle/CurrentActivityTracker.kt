package io.nuxie.sdk.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.nuxie.sdk.flows.NuxieFlowActivity
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

class CurrentActivityTracker(
  private val application: Application,
  private val onAppWillEnterForeground: (() -> Unit)? = null,
  private val onAppBecameActive: (() -> Unit)? = null,
  private val onAppDidEnterBackground: (() -> Unit)? = null,
) : Application.ActivityLifecycleCallbacks {
  private val currentRef = AtomicReference<WeakReference<Activity>?>(null)
  private var startedCount: Int = 0
  private var resumedCount: Int = 0

  init {
    application.registerActivityLifecycleCallbacks(this)
  }

  fun getCurrentActivity(): Activity? = currentRef.get()?.get()

  fun stop() {
    application.unregisterActivityLifecycleCallbacks(this)
    currentRef.set(null)
  }

  override fun onActivityResumed(activity: Activity) {
    if (resumedCount == 0) {
      onAppBecameActive?.invoke()
    }
    resumedCount += 1

    // Don't treat the Flow overlay as the host surface for future presentations.
    if (activity is NuxieFlowActivity) return
    currentRef.set(WeakReference(activity))
  }

  override fun onActivityPaused(activity: Activity) {
    resumedCount = (resumedCount - 1).coerceAtLeast(0)
  }

  override fun onActivityStarted(activity: Activity) {
    if (startedCount == 0) {
      onAppWillEnterForeground?.invoke()
    }
    startedCount += 1
  }

  override fun onActivityStopped(activity: Activity) {
    startedCount = (startedCount - 1).coerceAtLeast(0)
    if (startedCount == 0) {
      onAppDidEnterBackground?.invoke()
    }
  }

  override fun onActivityDestroyed(activity: Activity) {
    val current = currentRef.get()?.get()
    if (current === activity) {
      currentRef.set(null)
    }
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}
