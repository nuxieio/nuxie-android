# Architecture Overview

This SDK is split into core runtime and Android platform integration.

## Modules

- `nuxie-core`: platform-agnostic logic
  - identity/session
  - network client and models
  - event queueing and batching
  - profile + segment + feature services
  - journey/IR runtime
  - trigger models
- `nuxie-android`: Android platform layer
  - `NuxieSDK` public API
  - Room-backed queue/history stores
  - flow rendering (`FlowView`, `FlowWebView`, `NuxieFlowActivity`)
  - activity/lifecycle bridge
  - plugin manager

## Runtime Flow

1. `NuxieSDK.setup(...)` creates services and starts queue processing.
2. `trigger(...)` records event and receives gate response.
3. Journey runtime evaluates local campaign + interaction logic.
4. Flow UI is shown if required (`showFlow` / journey presentation).
5. Delegate callbacks are emitted for flow actions.
6. Profile refresh syncs features, segments, journeys, and flows.

## Key Services

- `IdentityService`: manages distinct/anonymous IDs and user properties.
- `SessionService`: controls session lifecycle and ID rotation.
- `EventService` + `NuxieNetworkQueue`: local queue + flush/retry logic.
- `ProfileService`: cache-first profile fetch with background refresh.
- `FeatureService`: cached/remote entitlement and balance checks.
- `SegmentService`: segment membership and change stream.
- `JourneyService`: campaign trigger handling and flow journey execution.
- `FlowService`: remote flow fetch/cache and `FlowView` creation.
- `PluginService` (Android layer): plugin install/start/stop/lifecycle fanout.

## Persistence

- Room DB (`nuxie-android`): event queue + event history.
- File stores (`nuxie-core`): profile cache, journey state, segment membership, flow bundles/fonts.

## Threading Model

- Internal services run on a background `CoroutineScope`.
- SDK delegate callbacks are marshaled to the main thread.
- Queue/network and file I/O run off the main thread.

## Android Lifecycle Integration

`CurrentActivityTracker` monitors app activity transitions and drives:

- session foreground/background hooks
- profile/feature refresh on app active
- plugin lifecycle callbacks
- current host activity lookup for flow presentation
