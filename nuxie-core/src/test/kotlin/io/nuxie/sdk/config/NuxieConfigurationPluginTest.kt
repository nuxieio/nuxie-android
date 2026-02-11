package io.nuxie.sdk.config

import io.nuxie.sdk.plugins.NuxiePlugin
import io.nuxie.sdk.plugins.NuxiePluginHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NuxieConfigurationPluginTest {

  private class TestPlugin(
    override val pluginId: String,
  ) : NuxiePlugin {
    override fun install(host: NuxiePluginHost) = Unit
    override fun uninstall() = Unit
    override fun start() = Unit
    override fun stop() = Unit
  }

  @Test
  fun addPlugin_appendsPluginToConfiguration() {
    val configuration = NuxieConfiguration(apiKey = "test_key")

    configuration.addPlugin(TestPlugin(pluginId = "p1"))

    assertEquals(1, configuration.plugins.size)
    assertEquals("p1", configuration.plugins.first().pluginId)
  }

  @Test
  fun removePlugin_removesAllMatchingIds() {
    val configuration = NuxieConfiguration(apiKey = "test_key")
    configuration.addPlugin(TestPlugin(pluginId = "p1"))
    configuration.addPlugin(TestPlugin(pluginId = "p2"))
    configuration.addPlugin(TestPlugin(pluginId = "p1"))

    configuration.removePlugin("p1")

    assertEquals(1, configuration.plugins.size)
    assertTrue(configuration.plugins.any { it.pluginId == "p2" })
    assertFalse(configuration.plugins.any { it.pluginId == "p1" })
  }
}
