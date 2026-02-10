package io.nuxie.sdk.storage

/**
 * Minimal synchronous key-value store abstraction used by core services.
 *
 * Android implements this using SharedPreferences; tests can use in-memory.
 */
interface KeyValueStore {
  fun getString(key: String): String?
  fun putString(key: String, value: String?)

  fun getLong(key: String): Long?
  fun putLong(key: String, value: Long?)

  fun getBoolean(key: String): Boolean?
  fun putBoolean(key: String, value: Boolean?)
}

