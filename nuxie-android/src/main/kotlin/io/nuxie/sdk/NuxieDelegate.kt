package io.nuxie.sdk

import io.nuxie.sdk.features.FeatureAccess

/**
 * Delegate for receiving Nuxie SDK callbacks.
 *
 * Mirrors iOS `NuxieDelegate` (feature access changes).
 */
interface NuxieDelegate {
  fun featureAccessDidChange(featureId: String, from: FeatureAccess?, to: FeatureAccess) {}
}

