package io.nuxie.example

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.nuxie.sdk.NuxieSDK
import io.nuxie.sdk.config.NuxieConfiguration
import io.nuxie.sdk.plugins.NuxiePlugin
import io.nuxie.sdk.plugins.NuxiePluginHost
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExampleSdkSmokeTest {

  private val sdk = NuxieSDK.shared()

  private class TestPlugin : NuxiePlugin {
    override val pluginId: String = "smoke-plugin"

    var installCalls: Int = 0
    var uninstallCalls: Int = 0
    var startCalls: Int = 0
    var stopCalls: Int = 0

    override fun install(host: NuxiePluginHost) {
      installCalls += 1
    }

    override fun uninstall() {
      uninstallCalls += 1
    }

    override fun start() {
      startCalls += 1
    }

    override fun stop() {
      stopCalls += 1
    }
  }

  @Before
  fun setUp() = runBlocking {
    sdk.shutdown()
  }

  @After
  fun tearDown() = runBlocking {
    sdk.shutdown()
  }

  @Test
  fun setup_session_and_plugin_management_work_endToEnd() {
    val application = ApplicationProvider.getApplicationContext<Application>()
    val configuration = NuxieConfiguration(apiKey = "example_test_key").apply {
      // Keep network calls deterministic for test runs.
      setApiEndpoint("http://127.0.0.1:9")
      enablePlugins = false
    }

    sdk.setup(application, configuration)
    assertTrue(sdk.isSetup)

    sdk.startNewSession()
    assertNotNull(sdk.getCurrentSessionId())

    sdk.setSessionId("custom_session")
    assertEquals("custom_session", sdk.getCurrentSessionId())

    sdk.endSession()
    assertNull(sdk.getCurrentSessionId())

    sdk.resetSession()
    assertNotNull(sdk.getCurrentSessionId())

    val plugin = TestPlugin()
    sdk.installPlugin(plugin)
    assertTrue(sdk.isPluginInstalled(plugin.pluginId))

    sdk.startPlugin(plugin.pluginId)
    sdk.stopPlugin(plugin.pluginId)
    sdk.uninstallPlugin(plugin.pluginId)

    assertFalse(sdk.isPluginInstalled(plugin.pluginId))
    assertEquals(1, plugin.installCalls)
    assertEquals(1, plugin.startCalls)
    assertEquals(2, plugin.stopCalls)
    assertEquals(1, plugin.uninstallCalls)
  }
}
