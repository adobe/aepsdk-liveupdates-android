# Adobe Experience Platform - Live Updates extension for Android

## About this project

The AEPLiveUpdates extension for Adobe Experience Platform Mobile SDKs provides Live Updates support — dynamic, ongoing-update status-bar notifications (analogous to Live Activities / Dynamic Island on iOS) — for Android applications, built on Android 16 (API 36) promoted-ongoing notifications.

The SDK is a plain class library, not a registered `MobileCore` Extension: it dispatches outbound tracking events via `MobileCore.dispatchEvent` (routed through Edge to AJO) but does not subscribe to the Event Hub for inbound events.

### Public API surface (`LiveUpdates`)

- `setLiveUpdateListener` / `getLiveUpdateListener` — register an `ILiveUpdateListener` for `onLiveUpdateReceived` / `onStart` / `onUpdate` / `onEnd` / `onClick` / `onDismissed` callbacks.
- `setLiveUpdateInterceptor` — register an `ILiveUpdateInterceptor` app-side gate consulted before a Live Update is rendered, tracked, or dispatched to the listener (e.g. to suppress a duplicate/late push for a chip the user already dismissed).
- `trackLiveUpdateEvent` — manual entry point for apps that build and post the notification themselves but still want AJO tracking + listener dispatch.
- `subscribeToTopic` / `unsubscribeFromTopic` — FCM topic helpers for the broadcast use case.

Integration happens through one of three patterns, all handled by the `aepsdk-messaging-android` `ILiveUpdateHandler` contract:

1. **Auto** — app registers `LiveUpdateHandlerImpl` via `Messaging.setLiveUpdateHandler(...)`; `MessagingService` detects Live Update pushes and dispatches to it automatically.
2. **Mixed** — app has its own `FirebaseMessagingService` and calls `MessagingService.handleRemoteMessage(context, message)` directly.
3. **Manual** — app builds and posts the notification itself, then calls `LiveUpdates.trackLiveUpdateEvent(context, message)` for tracking + listener dispatch only.

Chip interactions (tap and swipe-to-dismiss) are tracked automatically: `LiveUpdateTrackerActivity` fires `applicationOpened` tracking on a chip tap and then invokes the listener's `onClick` (the app decides what to open — the SDK does not launch a destination; there is no deep-link / web-URL or action-button support in this version), and `LiveUpdateInteractionReceiver` (registered in the SDK's own manifest, no host app setup required) fires dismiss tracking and invokes `onDismissed` when the user swipes a chip away.

## Toolchain

This SDK uses **commons 4.0.0** (AGP 8.9.1 / Kotlin 2.0.21 / compileSdk 36 / Gradle 8.11.1), which is a newer toolchain than the rest of the AEP Android SDK family. The `aep-library`/`aep-license` plugin ids are resolved via a `pluginManagement.resolutionStrategy.eachPlugin` redirect in `code/settings.gradle.kts` (commons doesn't publish plugin marker artifacts), so `:liveupdates` can request `id("aep-library")` directly without any per-module `buildscript {}` block.

## Dependencies

All dependencies resolve automatically — no manual publishing step is required before building:

| Dependency | Source |
|---|---|
| `aep-library` / `aep-license` Gradle plugins (commons 4.0.0) | JitPack, tracking `aepsdk-commons`'s `dev-v4.0.0-SNAPSHOT` branch |
| `com.adobe.marketing.mobile:core` | Maven Central |
| `com.adobe.marketing.mobile:messaging` | JitPack, tracking `aepsdk-messaging-android`'s `rc-liveupdates` branch |
| `com.adobe.marketing.mobile:edge`, `edgeidentity`, `lifecycle`, `assurance` | Maven Central |

## Building

```bash
cd code
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :liveupdates:assembleRelease
./gradlew :testapp:assembleDebug
```

## Remaining work

- [ ] `liveupdatestestutils/` module, once there's a need for shared test fixtures
- [ ] CI/CD
- [ ] Verify the Maven Central publishing config end-to-end (version naming, JReleaser wiring)
