package io.nuxie.sdk.flows

import org.junit.Assert.assertEquals
import org.junit.Test

class FlowColorSchemeModeTest {

  @Test
  fun fromRawValue_maps_explicit_modes() {
    assertEquals(FlowColorSchemeMode.LIGHT, FlowColorSchemeMode.fromRawValue("light"))
    assertEquals(FlowColorSchemeMode.DARK, FlowColorSchemeMode.fromRawValue("dark"))
  }

  @Test
  fun fromRawValue_defaults_to_light_for_unknown_values() {
    assertEquals(FlowColorSchemeMode.LIGHT, FlowColorSchemeMode.fromRawValue("system"))
    assertEquals(FlowColorSchemeMode.LIGHT, FlowColorSchemeMode.fromRawValue(null))
    assertEquals(FlowColorSchemeMode.LIGHT, FlowColorSchemeMode.fromRawValue("unknown"))
  }
}
