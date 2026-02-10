package io.nuxie.sdk.storage

import java.util.concurrent.ConcurrentHashMap

class InMemoryKeyValueStore : KeyValueStore {
  private val strings = ConcurrentHashMap<String, String>()
  private val longs = ConcurrentHashMap<String, Long>()
  private val bools = ConcurrentHashMap<String, Boolean>()

  override fun getString(key: String): String? = strings[key]

  override fun putString(key: String, value: String?) {
    if (value == null) strings.remove(key) else strings[key] = value
  }

  override fun getLong(key: String): Long? = longs[key]

  override fun putLong(key: String, value: Long?) {
    if (value == null) longs.remove(key) else longs[key] = value
  }

  override fun getBoolean(key: String): Boolean? = bools[key]

  override fun putBoolean(key: String, value: Boolean?) {
    if (value == null) bools.remove(key) else bools[key] = value
  }
}

