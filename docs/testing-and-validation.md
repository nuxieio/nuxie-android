# Testing and Validation

This repository includes unit tests for `nuxie-core`, `nuxie-android`, and integration smoke coverage in `example-app`.

## Prerequisites

- JDK 17
- Android SDK (compile SDK 34)
- `JAVA_HOME`, `ANDROID_HOME`, and `ANDROID_SDK_ROOT` set in your shell

Example:

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT=/opt/homebrew/share/android-commandlinetools
```

## Test Commands

Run everything:

```sh
./gradlew test
```

Run parity-critical suites:

```sh
./gradlew :nuxie-core:test :nuxie-android:test :example-app:testDebugUnitTest
```

Run only example smoke test module:

```sh
./gradlew :example-app:testDebugUnitTest
```

Build the example APK:

```sh
./gradlew :example-app:assembleDebug
```

## Coverage Areas

Current tests cover:

- trigger and gate decision flow
- journey start/suppress/complete lifecycle
- interaction action callback plumbing (`call_delegate`, `purchase`, `restore`, `open_link`, `dismiss`, `back`)
- server-journey resume behavior
- session service behavior
- profile cache + refresh behavior
- plugin install/start/stop/uninstall and lifecycle callback fanout
- end-to-end SDK setup/session/plugin smoke validation in `example-app`

## Manual Validation Checklist

1. Install/run `example-app` on device or emulator.
2. Tap **Setup SDK** and confirm setup logs appear.
3. Tap **Identify User** and confirm no errors.
4. Tap **Trigger Event** and confirm trigger update logs.
5. Tap **Start Session** / **End Session** / **Reset Session** and verify session ID behavior.
6. If backend test data is configured, tap **Show Flow** and verify callbacks in log output.

## Troubleshooting

- `SDK not configured` warnings: ensure `setup(...)` is called once before API use.
- `showFlow requires a foreground Activity`: call from active UI state.
- Purchase/restore errors from flow UI: set `NuxieConfiguration.purchaseDelegate`.
- Plugin install failures: check unique `pluginId` and plugin exception handling.
