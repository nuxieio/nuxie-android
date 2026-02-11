# Nuxie Android SDK

Kotlin/Android reference implementation of the Nuxie client SDK.

This repo is intended to be used as a git submodule from the Nuxie monorepo under `packages/nuxie-android`.

## Development

- JDK 17+
- Android SDK (compileSdk 34)

### Build

```sh
./gradlew build
```

### Tests

```sh
./gradlew test
```

## Example App

A runnable example app lives in `example-app/` and demonstrates:

- SDK setup with custom endpoint
- identify/trigger/showFlow usage
- session management APIs
- plugin installation + lifecycle callbacks
- delegate callback logging for flow actions

### Run

```sh
./gradlew :example-app:installDebug
```

Set optional demo values via Gradle properties:

- `NUXIE_EXAMPLE_API_KEY`
- `NUXIE_EXAMPLE_API_ENDPOINT`

Example:

```sh
./gradlew :example-app:installDebug -PNUXIE_EXAMPLE_API_KEY=YOUR_KEY -PNUXIE_EXAMPLE_API_ENDPOINT=https://i.nuxie.io
```

### Verify

The example module includes a Robolectric smoke test:

```sh
./gradlew :example-app:testDebugUnitTest
```
