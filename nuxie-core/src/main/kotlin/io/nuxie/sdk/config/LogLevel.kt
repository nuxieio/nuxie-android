package io.nuxie.sdk.config

/**
 * Log levels.
 *
 * Ordered by verbosity, to allow ">= configuredLevel" checks.
 */
enum class LogLevel(val priority: Int) {
  VERBOSE(0),
  DEBUG(1),
  INFO(2),
  WARNING(3),
  ERROR(4),
  NONE(5),
}

