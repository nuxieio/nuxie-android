package io.nuxie.sdk.plugins

/**
 * Host bridge exposed to plugins.
 *
 * This keeps plugin definitions in `nuxie-core` while allowing platform SDKs
 * (Android, iOS wrappers, etc.) to provide concrete behavior.
 */
interface NuxiePluginHost {
  fun trigger(
    event: String,
    properties: Map<String, Any?>? = null,
    userProperties: Map<String, Any?>? = null,
    userPropertiesSetOnce: Map<String, Any?>? = null,
  )

  fun getDistinctId(): String
  fun getAnonymousId(): String
  fun isIdentified(): Boolean
}

/**
 * Plugin contract for SDK extensions.
 */
interface NuxiePlugin {
  val pluginId: String

  fun install(host: NuxiePluginHost)
  fun uninstall()
  fun start()
  fun stop()

  fun onAppBecameActive() {}
  fun onAppDidEnterBackground() {}
  fun onAppWillEnterForeground() {}
}

sealed class PluginError(message: String) : Exception(message) {
  class PluginNotFound(pluginId: String) : PluginError("Plugin not found: $pluginId")
  class PluginAlreadyInstalled(pluginId: String) : PluginError("Plugin already installed: $pluginId")
  class PluginInstallationFailed(pluginId: String, causeMessage: String?) :
    PluginError("Plugin installation failed for $pluginId: ${causeMessage ?: "unknown"}")

  class PluginUninstallationFailed(pluginId: String, causeMessage: String?) :
    PluginError("Plugin uninstallation failed for $pluginId: ${causeMessage ?: "unknown"}")
}
