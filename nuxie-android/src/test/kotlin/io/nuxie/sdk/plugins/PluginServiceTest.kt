package io.nuxie.sdk.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginServiceTest {

  private class FakeHost : NuxiePluginHost {
    val triggered = mutableListOf<String>()

    override fun trigger(
      event: String,
      properties: Map<String, Any?>?,
      userProperties: Map<String, Any?>?,
      userPropertiesSetOnce: Map<String, Any?>?,
    ) {
      triggered += event
    }

    override fun getDistinctId(): String = "distinct_1"

    override fun getAnonymousId(): String = "anon_1"

    override fun isIdentified(): Boolean = true
  }

  private class FakePlugin(
    override val pluginId: String,
    private val throwOnInstall: Boolean = false,
    private val throwOnUninstall: Boolean = false,
  ) : NuxiePlugin {
    var installedHost: NuxiePluginHost? = null
    var installCalls: Int = 0
    var uninstallCalls: Int = 0
    var startCalls: Int = 0
    var stopCalls: Int = 0
    var activeCalls: Int = 0
    var backgroundCalls: Int = 0
    var foregroundCalls: Int = 0

    override fun install(host: NuxiePluginHost) {
      if (throwOnInstall) {
        error("install_failed")
      }
      installCalls += 1
      installedHost = host
    }

    override fun uninstall() {
      if (throwOnUninstall) {
        error("uninstall_failed")
      }
      uninstallCalls += 1
      installedHost = null
    }

    override fun start() {
      startCalls += 1
    }

    override fun stop() {
      stopCalls += 1
    }

    override fun onAppBecameActive() {
      activeCalls += 1
    }

    override fun onAppDidEnterBackground() {
      backgroundCalls += 1
    }

    override fun onAppWillEnterForeground() {
      foregroundCalls += 1
    }
  }

  @Test
  fun install_start_stop_uninstall_roundtrip() {
    val service = PluginService()
    val host = FakeHost()
    val plugin = FakePlugin(pluginId = "test")

    service.initialize(host)
    service.installPlugin(plugin)

    assertTrue(service.isPluginInstalled("test"))
    assertEquals(1, plugin.installCalls)
    assertNotNull(plugin.installedHost)

    service.startPlugin("test")
    service.stopPlugin("test")
    assertEquals(1, plugin.startCalls)
    assertEquals(1, plugin.stopCalls)

    service.uninstallPlugin("test")
    assertFalse(service.isPluginInstalled("test"))
    assertEquals(2, plugin.stopCalls)
    assertEquals(1, plugin.uninstallCalls)
  }

  @Test
  fun installPlugin_withoutInitialization_throwsInstallationFailed() {
    val service = PluginService()
    val plugin = FakePlugin(pluginId = "test")

    val error = runCatching { service.installPlugin(plugin) }.exceptionOrNull()

    assertTrue(error is PluginError.PluginInstallationFailed)
    assertFalse(service.isPluginInstalled("test"))
  }

  @Test
  fun installPlugin_duplicatePluginId_throwsAlreadyInstalled() {
    val service = PluginService()
    val host = FakeHost()
    val first = FakePlugin(pluginId = "dup")
    val second = FakePlugin(pluginId = "dup")

    service.initialize(host)
    service.installPlugin(first)

    val error = runCatching { service.installPlugin(second) }.exceptionOrNull()

    assertTrue(error is PluginError.PluginAlreadyInstalled)
  }

  @Test
  fun uninstallPlugin_unknownPlugin_throwsNotFound() {
    val service = PluginService()
    service.initialize(FakeHost())

    val error = runCatching { service.uninstallPlugin("missing") }.exceptionOrNull()

    assertTrue(error is PluginError.PluginNotFound)
  }

  @Test
  fun lifecycleCallbacks_areBroadcastToInstalledPlugins() {
    val service = PluginService()
    val host = FakeHost()
    val first = FakePlugin(pluginId = "one")
    val second = FakePlugin(pluginId = "two")

    service.initialize(host)
    service.installPlugin(first)
    service.installPlugin(second)

    service.onAppWillEnterForeground()
    service.onAppBecameActive()
    service.onAppDidEnterBackground()

    assertEquals(1, first.foregroundCalls)
    assertEquals(1, first.activeCalls)
    assertEquals(1, first.backgroundCalls)
    assertEquals(1, second.foregroundCalls)
    assertEquals(1, second.activeCalls)
    assertEquals(1, second.backgroundCalls)
  }

  @Test
  fun cleanup_stopsAndUninstallsAllPlugins() {
    val service = PluginService()
    val host = FakeHost()
    val first = FakePlugin(pluginId = "one")
    val second = FakePlugin(pluginId = "two")

    service.initialize(host)
    service.installPlugin(first)
    service.installPlugin(second)

    service.cleanup()

    assertFalse(service.isPluginInstalled("one"))
    assertFalse(service.isPluginInstalled("two"))
    assertEquals(1, first.stopCalls)
    assertEquals(1, first.uninstallCalls)
    assertEquals(1, second.stopCalls)
    assertEquals(1, second.uninstallCalls)
  }
}
