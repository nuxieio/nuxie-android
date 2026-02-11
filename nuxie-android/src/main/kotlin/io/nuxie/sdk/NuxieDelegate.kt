package io.nuxie.sdk

import io.nuxie.sdk.features.FeatureAccess

/**
 * Delegate for receiving Nuxie SDK callbacks.
 *
 * Mirrors iOS `NuxieDelegate` for feature access changes and adds Android delegate-action callbacks.
 */
interface NuxieDelegate {
  fun featureAccessDidChange(featureId: String, from: FeatureAccess?, to: FeatureAccess) {}

  /**
   * Called when a flow journey executes a `call_delegate` interaction action.
   *
   * Parity with iOS `Notification.Name.nuxieCallDelegate` payload shape:
   * - [message]
   * - [payload]
   * - [journeyId]
   * - [campaignId]
   */
  fun flowDelegateCalled(
    message: String,
    payload: Any?,
    journeyId: String,
    campaignId: String?,
  ) {}

  fun flowPurchaseRequested(
    journeyId: String,
    campaignId: String?,
    screenId: String?,
    productId: String,
    placementIndex: Any?,
  ) {}

  fun flowRestoreRequested(
    journeyId: String,
    campaignId: String?,
    screenId: String?,
  ) {}

  fun flowOpenLinkRequested(
    journeyId: String,
    campaignId: String?,
    screenId: String?,
    url: String,
    target: String?,
  ) {}

  fun flowDismissed(
    journeyId: String,
    campaignId: String?,
    screenId: String?,
    reason: String,
    error: String?,
  ) {}

  fun flowBackRequested(
    journeyId: String,
    campaignId: String?,
    screenId: String?,
    steps: Int,
  ) {}
}
