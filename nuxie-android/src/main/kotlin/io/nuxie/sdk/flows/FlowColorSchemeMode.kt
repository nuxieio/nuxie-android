package io.nuxie.sdk.flows

import android.content.res.Configuration

enum class FlowColorSchemeMode(val rawValue: String) {
  LIGHT("light"),
  DARK("dark"),
  SYSTEM("system");

  companion object {
    fun fromRawValue(rawValue: String?): FlowColorSchemeMode {
      return entries.firstOrNull { it.rawValue == rawValue } ?: SYSTEM
    }
  }
}

internal enum class ResolvedFlowColorScheme(val rawValue: String) {
  LIGHT("light"),
  DARK("dark"),
}

internal fun resolveFlowColorScheme(
  mode: FlowColorSchemeMode,
  configuration: Configuration,
): ResolvedFlowColorScheme {
  return when (mode) {
    FlowColorSchemeMode.LIGHT -> ResolvedFlowColorScheme.LIGHT
    FlowColorSchemeMode.DARK -> ResolvedFlowColorScheme.DARK
    FlowColorSchemeMode.SYSTEM -> {
      val nightMode = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
      if (nightMode == Configuration.UI_MODE_NIGHT_YES) {
        ResolvedFlowColorScheme.DARK
      } else {
        ResolvedFlowColorScheme.LIGHT
      }
    }
  }
}
