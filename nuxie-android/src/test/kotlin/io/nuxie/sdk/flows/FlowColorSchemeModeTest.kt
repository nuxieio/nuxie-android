package io.nuxie.sdk.flows

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class FlowColorSchemeModeTest {

  @Test
  fun resolveFlowColorScheme_honors_explicit_modes() {
    val configuration = Configuration()
    configuration.uiMode = Configuration.UI_MODE_NIGHT_NO

    assertEquals(
      ResolvedFlowColorScheme.LIGHT,
      resolveFlowColorScheme(FlowColorSchemeMode.LIGHT, configuration),
    )
    assertEquals(
      ResolvedFlowColorScheme.DARK,
      resolveFlowColorScheme(FlowColorSchemeMode.DARK, configuration),
    )
  }

  @Test
  fun resolveFlowColorScheme_uses_ui_mode_for_system() {
    val darkConfiguration = Configuration()
    darkConfiguration.uiMode = Configuration.UI_MODE_NIGHT_YES
    assertEquals(
      ResolvedFlowColorScheme.DARK,
      resolveFlowColorScheme(FlowColorSchemeMode.SYSTEM, darkConfiguration),
    )

    val lightConfiguration = Configuration()
    lightConfiguration.uiMode = Configuration.UI_MODE_NIGHT_NO
    assertEquals(
      ResolvedFlowColorScheme.LIGHT,
      resolveFlowColorScheme(FlowColorSchemeMode.SYSTEM, lightConfiguration),
    )
  }
}
