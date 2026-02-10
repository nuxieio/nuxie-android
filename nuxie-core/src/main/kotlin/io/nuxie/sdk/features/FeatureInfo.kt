package io.nuxie.sdk.features

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * In-memory observable feature map.
 *
 * Mirrors iOS `FeatureInfo` enough to keep parity in behavior:
 * - `update(...)` diffs and emits per-feature change callbacks
 * - local balance adjustments for instant UI feedback (`decrementBalance`, `setBalance`)
 */
class FeatureInfo {
  private val lock = ReentrantLock()
  private val state = MutableStateFlow<Map<String, FeatureAccess>>(emptyMap())

  val features: StateFlow<Map<String, FeatureAccess>> = state.asStateFlow()

  var onFeatureChange: ((featureId: String, oldValue: FeatureAccess?, newValue: FeatureAccess) -> Unit)? = null

  fun feature(featureId: String): FeatureAccess? = state.value[featureId]

  fun isAllowed(featureId: String): Boolean = feature(featureId)?.allowed == true

  fun update(all: Map<String, FeatureAccess>) {
    val (oldMap, newMap, changedIds) = lock.withLock {
      val old = state.value
      state.value = all
      Triple(old, all, (old.keys + all.keys).toSet())
    }

    val cb = onFeatureChange ?: return
    for (id in changedIds) {
      val old = oldMap[id]
      val next = newMap[id]
      if (next != null && old != next) {
        cb(id, old, next)
      }
    }
  }

  fun update(featureId: String, access: FeatureAccess) {
    val oldValue = lock.withLock {
      val old = state.value
      val prev = old[featureId]
      state.value = old + (featureId to access)
      prev
    }

    val cb = onFeatureChange ?: return
    if (oldValue != access) {
      cb(featureId, oldValue, access)
    }
  }

  fun decrementBalance(featureId: String, amount: Int) {
    val updated = lock.withLock {
      val current = state.value[featureId] ?: return
      if (current.unlimited) return
      val curBalance = current.balance ?: 0
      val newBalance = (curBalance - amount).coerceAtLeast(0)
      FeatureAccess.withBalance(newBalance, unlimited = false, type = current.type)
    }
    update(featureId, updated)
  }

  fun setBalance(featureId: String, balance: Int, unlimited: Boolean = false) {
    val updated = lock.withLock {
      val current = state.value[featureId]
      val type = current?.type ?: FeatureType.METERED
      FeatureAccess.withBalance(balance, unlimited = unlimited, type = type)
    }
    update(featureId, updated)
  }
}
