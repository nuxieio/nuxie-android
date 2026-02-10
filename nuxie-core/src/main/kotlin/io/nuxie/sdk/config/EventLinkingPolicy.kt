package io.nuxie.sdk.config

/**
 * Policy for handling anonymous -> identified transitions.
 *
 * Mirrors iOS `EventLinkingPolicy`.
 */
enum class EventLinkingPolicy {
  KEEP_SEPARATE,
  MIGRATE_ON_IDENTIFY,
}

