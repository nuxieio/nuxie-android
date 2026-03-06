package io.nuxie.sdk.flows

enum class FlowColorSchemeMode(val rawValue: String) {
  LIGHT("light"),
  DARK("dark");

  companion object {
    fun fromRawValue(rawValue: String?): FlowColorSchemeMode {
      return entries.firstOrNull { it.rawValue == rawValue } ?: LIGHT
    }
  }
}
