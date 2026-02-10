package io.nuxie.sdk

import android.content.Context

/**
 * Android entrypoint.
 *
 * This will evolve to match the iOS SDK surface area.
 */
class NuxieSDK private constructor() {
  companion object {
    @Volatile private var instance: NuxieSDK? = null

    @JvmStatic
    fun shared(): NuxieSDK = instance ?: synchronized(this) {
      instance ?: NuxieSDK().also { instance = it }
    }
  }

  fun setup(context: Context) {
    // TODO: Implement.
    context.applicationContext
  }

  fun version(): String = NuxieVersion.current
}
