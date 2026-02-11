package io.nuxie.sdk.triggers

/**
 * Lightweight cancellation handle returned from `trigger(...)`.
 *
 * Mirrors iOS `TriggerHandle.cancel()`.
 */
class TriggerHandle constructor(
  private val cancelHandler: (() -> Unit)? = null,
) {
  fun cancel() {
    cancelHandler?.invoke()
  }

  companion object {
    val empty: TriggerHandle = TriggerHandle(cancelHandler = null)
  }
}
