package io.nuxie.sdk.storage

import android.content.Context
import android.content.SharedPreferences

internal class SharedPreferencesKeyValueStore(
  context: Context,
  name: String = "nuxie_sdk",
) : KeyValueStore {
  private val prefs: SharedPreferences =
    context.getSharedPreferences(name, Context.MODE_PRIVATE)

  override fun getString(key: String): String? = prefs.getString(key, null)

  override fun putString(key: String, value: String?) {
    prefs.edit().apply {
      if (value == null) remove(key) else putString(key, value)
    }.apply()
  }

  override fun getLong(key: String): Long? =
    if (prefs.contains(key)) prefs.getLong(key, 0L) else null

  override fun putLong(key: String, value: Long?) {
    prefs.edit().apply {
      if (value == null) remove(key) else putLong(key, value)
    }.apply()
  }

  override fun getBoolean(key: String): Boolean? =
    if (prefs.contains(key)) prefs.getBoolean(key, false) else null

  override fun putBoolean(key: String, value: Boolean?) {
    prefs.edit().apply {
      if (value == null) remove(key) else putBoolean(key, value)
    }.apply()
  }
}

