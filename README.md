# Adobe Experience Platform - Live Updates extension for Android

## About this project

The AEPLiveUpdates extension for Adobe Experience Platform Mobile SDKs will provide Live Updates support (dynamic, ongoing-update notifications — analogous to Live Activities / Dynamic Island on iOS) for Android applications.

**This repository currently contains only initial scaffolding.** Functionality will be implemented in subsequent sessions.

## Toolchain

This SDK uses **commons 4.0.0** (AGP 8.9.1 / Kotlin 2.0.21 / compileSdk 36 / Gradle 8.11.1), which is a newer toolchain than the rest of the AEP Android SDK family. See `code/settings.gradle.kts` for the composite-build setup that allows the sample app to consume both toolchains side-by-side.

## Local dependency requirements

Before building, the commons `aep-library` plugin (version 4.0.0) must be published to Maven local:

```bash
# In aepsdk-commons, on branch api_36_upgrade:
cd android/aepsdk-gradle-plugin
./gradlew publishToMavenLocal
```

Verify it landed at:
```
~/.m2/repository/com/github/adobe/aepsdk-commons/aepsdk-gradle-plugin/4.0.0/
```

## Building

```bash
cd code
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew :liveupdates:assembleRelease
./gradlew :testapp:assembleDebug
```

## TODO for next sessions

- [ ] Implement `LiveUpdatesExtension` — register with MobileCore, event listeners, notification management
- [ ] Add `liveupdatestestutils/` module once there is testable code
- [ ] Unit tests
- [ ] Publishing config (Maven Central, version naming)
- [ ] CI/CD
- [ ] Decide whether to retain or drop the core/edge dependencies
