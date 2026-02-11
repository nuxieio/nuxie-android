# Nuxie Android SDK

Kotlin/Android reference implementation of the Nuxie client SDK.

This repository contains the Android-native SDK used as the parity baseline for other client SDKs (iOS, React Native wrapper, Flutter wrapper, etc.).

## What is in this Repo

- `nuxie-core/`: shared core runtime (networking, identity, profile, features, journeys, IR interpreter, trigger engine).
- `nuxie-android/`: Android SDK surface (`NuxieSDK`, flow UI, Android persistence, lifecycle integration).
- `example-app/`: runnable Android app that demonstrates SDK integration and includes an end-to-end smoke test.

## Platform Support

- `minSdk = 21` (Android 5.0+)
- `compileSdk = 34`
- Java/Kotlin target: 17

## Quick Start

### 1. Add Modules

The SDK is currently consumed from source in this repository/monorepo layout.

Inside this repo, Android apps can depend on:

```kotlin
implementation(project(":nuxie-android"))
```

`nuxie-android` already depends on `nuxie-core`.

### 2. Ensure Network Permission

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

### 3. Initialize the SDK

Initialize once (typically in `Application.onCreate`).

```kotlin
import android.app.Application
import io.nuxie.sdk.NuxieSDK
import io.nuxie.sdk.config.LogLevel
import io.nuxie.sdk.config.NuxieConfiguration

class App : Application() {
  override fun onCreate() {
    super.onCreate()

    val config = NuxieConfiguration(apiKey = "YOUR_API_KEY").apply {
      logLevel = LogLevel.DEBUG
      // Optional: environment = Environment.STAGING
      // Optional: setApiEndpoint("https://i.nuxie.io")
    }

    NuxieSDK.shared().setup(this, config)
  }
}
```

## Core Usage

### Identity

```kotlin
val sdk = NuxieSDK.shared()

sdk.identify(
  distinctId = "user_123",
  userProperties = mapOf("plan" to "pro"),
)

val distinctId = sdk.getDistinctId()
val anonymousId = sdk.getAnonymousId()
val isIdentified = sdk.isIdentified
```

### Triggering and Journey Updates

```kotlin
import io.nuxie.sdk.triggers.TriggerUpdate

sdk.trigger(
  event = "paywall_trigger",
  properties = mapOf("source" to "settings"),
) { update: TriggerUpdate ->
  // Progressive updates: Decision / Entitlement / Journey / Error
}
```

### Feature Checks and Usage

```kotlin
// Cache-first check
val access = sdk.hasFeature("pro_feature")

// Force network check
val result = sdk.checkFeature(featureId = "pro_feature")

// Track usage (fire-and-forget)
sdk.useFeature(featureId = "credits", amount = 1.0)

// Track usage and await server response
val usage = sdk.useFeatureAndWait(featureId = "credits", amount = 1.0)
```

### Session Controls

```kotlin
sdk.startNewSession()
val sessionId = sdk.getCurrentSessionId()

sdk.setSessionId("custom_session_id")
sdk.endSession()
sdk.resetSession()
```

## Flow UI Options

### A. SDK-managed Activity presentation

```kotlin
sdk.showFlow("flow_123")
```

This requires a foreground host `Activity`.

### B. Get a `FlowView` for custom host surfaces

```kotlin
val flowView = sdk.getFlowView(activity = this, flowId = "flow_123")
// Add `flowView` to your container view hierarchy.
```

Use `getFlowView(...)` if you want to host inside your own Activity/Fragment/Compose container.

## Delegate Callbacks

Set `sdk.delegate` to observe feature and flow action callbacks.

```kotlin
import io.nuxie.sdk.NuxieDelegate
import io.nuxie.sdk.features.FeatureAccess

class MyDelegate : NuxieDelegate {
  override fun featureAccessDidChange(featureId: String, from: FeatureAccess?, to: FeatureAccess) {
    // Feature info changed
  }

  override fun flowDelegateCalled(message: String, payload: Any?, journeyId: String, campaignId: String?) {}
  override fun flowPurchaseRequested(journeyId: String, campaignId: String?, screenId: String?, productId: String, placementIndex: Any?) {}
  override fun flowRestoreRequested(journeyId: String, campaignId: String?, screenId: String?) {}
  override fun flowOpenLinkRequested(journeyId: String, campaignId: String?, screenId: String?, url: String, target: String?) {}
  override fun flowDismissed(journeyId: String, campaignId: String?, screenId: String?, reason: String, error: String?) {}
  override fun flowBackRequested(journeyId: String, campaignId: String?, screenId: String?, steps: Int) {}
}
```

## Plugins

Plugins can be provided in configuration or installed at runtime.

Configuration-time install:

```kotlin
val plugin = MyPlugin()

val config = NuxieConfiguration("YOUR_API_KEY").apply {
  addPlugin(plugin)
}

sdk.setup(context, config)
```

Runtime install:

```kotlin
val plugin = MyPlugin()
sdk.installPlugin(plugin)
sdk.startPlugin(plugin.pluginId)
sdk.stopPlugin(plugin.pluginId)
sdk.uninstallPlugin(plugin.pluginId)
```

## Purchase Delegate

To support purchase/restore actions from flow runtime:

```kotlin
import io.nuxie.sdk.purchases.NuxiePurchaseDelegate
import io.nuxie.sdk.purchases.PurchaseResult
import io.nuxie.sdk.purchases.RestoreResult

class MyPurchaseDelegate : NuxiePurchaseDelegate {
  override suspend fun purchase(productId: String): PurchaseResult {
    return PurchaseResult.Success
  }

  override suspend fun restore(): RestoreResult {
    return RestoreResult.NoPurchases
  }
}
```

Attach it via `NuxieConfiguration.purchaseDelegate`.

## Example App

The `example-app/` module demonstrates:

- setup and configuration
- identify and trigger
- flow presentation
- plugin lifecycle events
- session APIs
- delegate callback logging

Run it:

```sh
./gradlew :example-app:installDebug
```

Optional properties:

- `NUXIE_EXAMPLE_API_KEY`
- `NUXIE_EXAMPLE_API_ENDPOINT`

```sh
./gradlew :example-app:installDebug \
  -PNUXIE_EXAMPLE_API_KEY=YOUR_KEY \
  -PNUXIE_EXAMPLE_API_ENDPOINT=https://i.nuxie.io
```

## Documentation

- `docs/getting-started.md`
- `docs/architecture.md`
- `docs/configuration-reference.md`
- `docs/api-reference.md`
- `docs/plugins-and-delegate-callbacks.md`
- `docs/testing-and-validation.md`

## Development

### Build

```sh
./gradlew build
```

### Test

```sh
./gradlew test
```

### Core + Android + Example validation

```sh
./gradlew :nuxie-core:test :nuxie-android:test :example-app:testDebugUnitTest
```
