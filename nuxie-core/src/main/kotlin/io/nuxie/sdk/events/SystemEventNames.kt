package io.nuxie.sdk.events

/**
 * System event names used by flow/journey runtime.
 *
 * Mirrors iOS `SystemEventNames`.
 */
object SystemEventNames {
  const val screenShown: String = "\$screen_shown"
  const val screenDismissed: String = "\$screen_dismissed"
  const val flowEntered: String = "\$flow_entered"

  const val purchaseCompleted: String = "\$purchase_completed"
  const val purchaseFailed: String = "\$purchase_failed"
  const val purchaseCancelled: String = "\$purchase_cancelled"

  const val restoreCompleted: String = "\$restore_completed"
  const val restoreFailed: String = "\$restore_failed"
  const val restoreNoPurchases: String = "\$restore_no_purchases"
}

