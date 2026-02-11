package io.nuxie.example

import io.nuxie.sdk.plugins.NuxiePlugin
import io.nuxie.sdk.plugins.NuxiePluginHost

/**
 * Tiny plugin used by the example app to demonstrate plugin installation and lifecycle callbacks.
 */
class ExampleAppLifecyclePlugin : NuxiePlugin {
  override val pluginId: String = "example-app-lifecycle"

  private var host: NuxiePluginHost? = null

  override fun install(host: NuxiePluginHost) {
    this.host = host
  }

  override fun uninstall() {
    host = null
  }

  override fun start() {
    host?.trigger(
      event = "\$example_plugin_started",
      properties = mapOf("identified" to host?.isIdentified(), "distinct_id" to host?.getDistinctId()),
    )
  }

  override fun stop() = Unit

  override fun onAppWillEnterForeground() {
    host?.trigger(event = "\$example_plugin_foreground", properties = mapOf("source" to "example_plugin"))
  }

  override fun onAppDidEnterBackground() {
    host?.trigger(event = "\$example_plugin_background", properties = mapOf("source" to "example_plugin"))
  }
}
