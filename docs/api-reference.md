# API Reference

This page documents the public Android SDK surface.

Source files:

- `nuxie-android/src/main/kotlin/io/nuxie/sdk/NuxieSDK.kt`
- `nuxie-android/src/main/kotlin/io/nuxie/sdk/NuxieDelegate.kt`
- `nuxie-core/src/main/kotlin/io/nuxie/sdk/triggers/TriggerModels.kt`

## Singleton Access

```kotlin
val sdk = NuxieSDK.shared()
```

## Lifecycle and Setup

| API | Description |
| --- | --- |
| `val isSetup: Boolean` | Returns `true` after successful `setup(...)`. |
| `fun setup(context: Context, configuration: NuxieConfiguration)` | Initializes SDK services, persistence, queues, flow runtime, plugins. |
| `suspend fun shutdown()` | Best-effort teardown for tests/process shutdown. |
| `val version: String` | SDK version string. |

## Identity APIs

| API | Description |
| --- | --- |
| `fun identify(distinctId: String, userProperties: Map<String, Any?>? = null, userPropertiesSetOnce: Map<String, Any?>? = null)` | Sets identified user, tracks `$identify`, handles migration/cache handoff. |
| `fun reset(keepAnonymousId: Boolean = true)` | Clears identity/session and user-scoped caches. |
| `fun getDistinctId(): String` | Current distinct ID (identified or anonymous fallback). |
| `fun getAnonymousId(): String` | Anonymous ID. |
| `val isIdentified: Boolean` | `true` when user has been identified. |

## Trigger APIs

| API | Description |
| --- | --- |
| `fun trigger(event: String, properties: Map<String, Any?>? = null, userProperties: Map<String, Any?>? = null, userPropertiesSetOnce: Map<String, Any?>? = null, handler: ((TriggerUpdate) -> Unit)? = null): TriggerHandle` | Sends event and emits progressive updates. |

### `TriggerUpdate`

`trigger(...)` can emit:

- `TriggerUpdate.Decision(TriggerDecision)`
- `TriggerUpdate.Entitlement(EntitlementUpdate)`
- `TriggerUpdate.Journey(JourneyUpdate)`
- `TriggerUpdate.Error(TriggerError)`

Important `TriggerDecision` values:

- `NoMatch`
- `Suppressed(SuppressReason)`
- `JourneyStarted(JourneyRef)`
- `JourneyResumed(JourneyRef)`
- `FlowShown(JourneyRef)`
- `AllowedImmediate`
- `DeniedImmediate`

## Session APIs

| API | Description |
| --- | --- |
| `fun startNewSession()` | Starts a new session immediately. |
| `fun getCurrentSessionId(): String?` | Returns current session ID without creating a new one. |
| `fun setSessionId(sessionId: String)` | Overrides current session ID. |
| `fun endSession()` | Ends current session. |
| `fun resetSession()` | Clears and starts a fresh session. |

## Feature APIs

| API | Description |
| --- | --- |
| `val features: FeatureInfo` | In-memory feature access map + observers. Throws if not configured. |
| `suspend fun hasFeature(featureId: String): FeatureAccess` | Cache-first check for boolean features and default balance checks. |
| `suspend fun hasFeature(featureId: String, requiredBalance: Int, entityId: String? = null): FeatureAccess` | Cache-first check with required balance. |
| `suspend fun getCachedFeature(featureId: String, entityId: String? = null): FeatureAccess?` | Reads cache only. |
| `suspend fun checkFeature(featureId: String, requiredBalance: Int? = null, entityId: String? = null): FeatureCheckResult` | Network-backed entitlement check. |
| `suspend fun refreshFeature(featureId: String, requiredBalance: Int? = null, entityId: String? = null): FeatureCheckResult` | Alias of `checkFeature(...)`. |
| `fun useFeature(featureId: String, amount: Double = 1.0, entityId: String? = null, metadata: Map<String, Any?>? = null)` | Fire-and-forget usage event with optimistic local balance decrement. |
| `suspend fun useFeatureAndWait(featureId: String, amount: Double = 1.0, entityId: String? = null, setUsage: Boolean = false, metadata: Map<String, Any?>? = null): FeatureUsageResult` | Sends usage and waits for server response. |
| `suspend fun refreshProfile(): ProfileResponse` | Refetches profile and syncs features. |

## Flow APIs

| API | Description |
| --- | --- |
| `fun showFlow(flowId: String)` | Presents flow in SDK-managed `NuxieFlowActivity`. Requires foreground host activity. |
| `suspend fun getFlowView(activity: Activity, flowId: String): FlowView` | Returns `FlowView` for custom host UI (Activity/Fragment/Compose). |

## Plugin APIs

| API | Description |
| --- | --- |
| `fun installPlugin(plugin: NuxiePlugin)` | Installs plugin at runtime. Throws on failure/not configured. |
| `fun uninstallPlugin(pluginId: String)` | Uninstalls plugin. Throws on failure/not found. |
| `fun startPlugin(pluginId: String)` | Starts plugin if installed. |
| `fun stopPlugin(pluginId: String)` | Stops plugin if installed. |
| `fun isPluginInstalled(pluginId: String): Boolean` | Installation check. |

## Event Queue APIs

| API | Description |
| --- | --- |
| `suspend fun flushEvents(): Boolean` | Immediately flushes queued events to backend. |
| `suspend fun getQueuedEventCount(): Int` | Queue size. |
| `suspend fun pauseEventQueue()` | Pauses network queue processing. |
| `suspend fun resumeEventQueue()` | Resumes queue processing. |

## Delegate API (`NuxieDelegate`)

Set `NuxieSDK.shared().delegate` to receive:

- `featureAccessDidChange(featureId, from, to)`
- `flowDelegateCalled(message, payload, journeyId, campaignId)`
- `flowPurchaseRequested(journeyId, campaignId, screenId, productId, placementIndex)`
- `flowRestoreRequested(journeyId, campaignId, screenId)`
- `flowOpenLinkRequested(journeyId, campaignId, screenId, url, target)`
- `flowDismissed(journeyId, campaignId, screenId, reason, error)`
- `flowBackRequested(journeyId, campaignId, screenId, steps)`

All callbacks are no-op defaults; implement only what you need.

## Error Behavior

Many APIs no-op when SDK is not configured. APIs that throw `NuxieError.NotConfigured` include:

- `installPlugin(...)`
- `uninstallPlugin(...)`
- `refreshProfile()`
- `getFlowView(...)`
- feature check APIs that require service access

## Threading Notes

- Delegate callbacks are delivered on the main thread.
- `trigger(...)` handler updates are marshaled to main thread.
- Most public suspend APIs are safe to call from any coroutine dispatcher.
