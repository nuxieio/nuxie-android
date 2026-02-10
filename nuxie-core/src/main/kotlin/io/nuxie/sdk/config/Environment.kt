package io.nuxie.sdk.config

/**
 * Environment settings.
 *
 * Mirrors iOS `Environment` behavior:
 * - Each environment has a default ingest endpoint.
 * - `CUSTOM` keeps the default endpoint unless the caller overrides `apiEndpoint`.
 */
enum class Environment(val defaultEndpoint: String) {
  PRODUCTION("https://i.nuxie.io"),
  STAGING("https://staging-i.nuxie.io"),
  DEVELOPMENT("https://dev-i.nuxie.io"),
  CUSTOM("https://i.nuxie.io"),
}

