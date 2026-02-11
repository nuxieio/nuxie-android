package io.nuxie.sdk.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import io.nuxie.sdk.flows.NuxieFlowActivity
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

class CurrentActivityTracker(
  private val application: Application,
) : Application.ActivityLifecycleCallbacks {
  private val currentRef = AtomicReference<WeakReference<Activity>?>(null)

  init {
    application.registerActivityLifecycleCallbacks(this)
  }

  fun getCurrentActivity(): Activity? = currentRef.get()?.get()

  fun stop() {
    application.unregisterActivityLifecycleCallbacks(this)
    currentRef.set(null)
  }

  override fun onActivityResumed(activity: Activity) {
    // Don't treat the Flow overlay as the host surface for future presentations.
    if (activity is NuxieFlowActivity) return
    currentRef.set(WeakReference(activity))
  }

  override fun onActivityDestroyed(activity: Activity) {
    val current = currentRef.get()?.get()
    if (current === activity) {
      currentRef.set(null)
    }
  }

  override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
  override fun onActivityStarted(activity: Activity) = Unit
  override fun onActivityPaused(activity: Activity) = Unit
  override fun onActivityStopped(activity: Activity) = Unit
  override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
}

