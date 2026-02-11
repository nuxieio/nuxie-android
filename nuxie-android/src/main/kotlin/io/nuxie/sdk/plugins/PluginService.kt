package io.nuxie.sdk.plugins

import io.nuxie.sdk.logging.NuxieLogger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Lightweight plugin manager for Android parity with iOS PluginService.
 */
class PluginService {
  private val pluginLock = ReentrantLock()
  private val installedPlugins: MutableMap<String, NuxiePlugin> = mutableMapOf()

  private var host: NuxiePluginHost? = null

  fun initialize(host: NuxiePluginHost) {
    pluginLock.withLock {
      this.host = host
    }
    NuxieLogger.info("PluginService initialized")
  }

  @Throws(PluginError::class)
  fun installPlugin(plugin: NuxiePlugin) {
    val sdkHost = pluginLock.withLock {
      if (installedPlugins[plugin.pluginId] != null) {
        throw PluginError.PluginAlreadyInstalled(plugin.pluginId)
      }
      host ?: throw PluginError.PluginInstallationFailed(plugin.pluginId, "SDK host unavailable")
    }

    try {
      plugin.install(sdkHost)
    } catch (t: Throwable) {
      throw PluginError.PluginInstallationFailed(plugin.pluginId, t.message)
    }

    pluginLock.withLock {
      installedPlugins[plugin.pluginId] = plugin
    }
    NuxieLogger.info("Plugin installed: ${plugin.pluginId}")
  }

  @Throws(PluginError::class)
  fun uninstallPlugin(pluginId: String) {
    val plugin = pluginLock.withLock {
      installedPlugins[pluginId] ?: throw PluginError.PluginNotFound(pluginId)
    }

    try {
      plugin.stop()
      plugin.uninstall()
    } catch (t: Throwable) {
      throw PluginError.PluginUninstallationFailed(pluginId, t.message)
    }

    pluginLock.withLock {
      installedPlugins.remove(pluginId)
    }
    NuxieLogger.info("Plugin uninstalled: $pluginId")
  }

  fun startPlugin(pluginId: String) {
    val plugin = pluginLock.withLock { installedPlugins[pluginId] } ?: return
    runCatching { plugin.start() }
      .onFailure { NuxieLogger.warning("Failed to start plugin $pluginId: ${it.message}") }
  }

  fun stopPlugin(pluginId: String) {
    val plugin = pluginLock.withLock { installedPlugins[pluginId] } ?: return
    runCatching { plugin.stop() }
      .onFailure { NuxieLogger.warning("Failed to stop plugin $pluginId: ${it.message}") }
  }

  fun isPluginInstalled(pluginId: String): Boolean {
    return pluginLock.withLock { installedPlugins[pluginId] != null }
  }

  fun onAppBecameActive() {
    val plugins = pluginLock.withLock { installedPlugins.values.toList() }
    for (plugin in plugins) {
      runCatching { plugin.onAppBecameActive() }
    }
  }

  fun onAppDidEnterBackground() {
    val plugins = pluginLock.withLock { installedPlugins.values.toList() }
    for (plugin in plugins) {
      runCatching { plugin.onAppDidEnterBackground() }
    }
  }

  fun onAppWillEnterForeground() {
    val plugins = pluginLock.withLock { installedPlugins.values.toList() }
    for (plugin in plugins) {
      runCatching { plugin.onAppWillEnterForeground() }
    }
  }

  fun cleanup() {
    val plugins = pluginLock.withLock {
      val values = installedPlugins.values.toList()
      installedPlugins.clear()
      host = null
      values
    }

    for (plugin in plugins) {
      runCatching {
        plugin.stop()
        plugin.uninstall()
      }
    }
  }
}
