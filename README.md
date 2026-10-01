# Adobe Experience Platform - Live Updates extension for Android

[![Maven Central](https://img.shields.io/maven-central/v/com.adobe.marketing.mobile/liveupdates.svg?logo=android&logoColor=white&label=liveupdates)](https://mvnrepository.com/artifact/com.adobe.marketing.mobile/liveupdates)
[![Build and Test](https://github.com/adobe/aepsdk-liveupdates-android/actions/workflows/build-and-test.yml/badge.svg)](https://github.com/adobe/aepsdk-liveupdates-android/actions/workflows/build-and-test.yml)
[![Code Coverage](https://codecov.io/gh/adobe/aepsdk-liveupdates-android/branch/main/graph/badge.svg)](https://codecov.io/gh/adobe/aepsdk-liveupdates-android)

## About this project
The AEPLiveUpdates extension for Adobe Experience Platform Mobile SDKs powers Live Updates for your Android apps — ongoing, promoted status-bar notifications (the Android counterpart to iOS Live Activities) built on Android 16 (API 36). Live Updates are delivered as Adobe Journey Optimizer (AJO) pushes through the Messaging extension, rendered with a notification style supplied by your app, and tracked (start, update, end, tap, and dismiss) back to Adobe Experience Platform through the Edge Network.

## Installation

Integrate the AEPLiveUpdates extension into your app by including the following in your app level gradle file's `dependencies`:

```groovy
    implementation platform('com.adobe.marketing.mobile:sdk-bom:3.+')
    implementation 'com.adobe.marketing.mobile:core'
    implementation 'com.adobe.marketing.mobile:assurance'
    implementation 'com.adobe.marketing.mobile:edge'
    implementation 'com.adobe.marketing.mobile:edgeidentity'
    implementation 'com.adobe.marketing.mobile:messaging'
    implementation 'com.adobe.marketing.mobile:liveupdates'
```

The Live Updates extension requires the following minimum versions, which are included in `sdk-bom` 3.24.0 or newer:

| Dependency | Minimum version |
| ---------- | --------------- |
| `core`        | 3.10.0 |
| `edge`        | 3.1.0  |
| `messaging`   | 3.13.0 |
| `liveupdates` | 3.0.0  |

Your app must use `compileSdk` 36 or newer, as the Live Updates extension is built against API 36 and `androidx.core:core-ktx` 1.17.0. The minimum supported `minSdk` is 21. Live Updates are promoted to a status-bar chip on devices running Android 16 (API 36) or newer; on older devices they are posted as regular ongoing notifications.

Adding Firebase messaging sdk as it is required for using [FCM](https://firebase.google.com/docs/cloud-messaging/android/client#add_firebase_sdks_to_your_app)
```
implementation 'com.google.firebase:firebase-messaging:<latest-version>'
```

Register the Live Update plugin with an `ILiveUpdateStyleProvider` that returns the `NotificationCompat.Style` to use for each Live Update:

```kotlin
MobileCore.addPlugins(LiveUpdatePlugin(MyLiveUpdateStyleProvider()))
```

### Development

**Open the project**

To open and run the project, open the `code/build.gradle.kts` file in Android Studio

**Run demo application**
- Follow this [Firebase documentation](https://firebase.google.com/docs/cloud-messaging/android/client#add_a_firebase_configuration_file) to add the configuration file for your firebase project, replacing the template at `code/testapp/google-services.json`.
- Set your Data Collection (Tags) mobile property environment file ID in `code/testapp/src/main/java/com/adobe/marketing/mobile/liveupdatessample/LiveUpdatesApplication.kt`.
- Once you opened the project in Android Studio (see above), select the `testapp` runnable and your favorite emulator (Android 16 / API 36 or newer to see the status-bar chip) and run the program.

**Build and test**

From the repository root:

```bash
make assemble-phone   # build the Live Updates SDK
make unit-test        # run unit tests
make lint             # run Spotless and Checkstyle checks
```

## Documentation
Additional documentation for configuration and SDK usage can be found under the [Documentation](Documentation/README.md) directory.

## Related Projects

| Project                                                      | Description                                                  |
| ------------------------------------------------------------ | ------------------------------------------------------------ |
| [Core extensions](https://github.com/adobe/aepsdk-core-android) | The Mobile Core represents the foundation of the Adobe Experience Platform Mobile SDK. |
| [Messaging extension](https://github.com/adobe/aepsdk-messaging-android) | The Messaging extension powers push notifications, in-app messages, and code-based experiences, and routes Live Update pushes to the Live Updates extension. |
| [Edge Network extension](https://github.com/adobe/aepsdk-edge-android) | The Edge Network extension allows you to send data to the Adobe Experience Platform (AEP) from a mobile application. |
| [Identity for Edge Network extension](https://github.com/adobe/aepsdk-edgeidentity-android) | The Identity for Edge Network extension enables identity management from a mobile app when using the Edge Network extension. |

## Contributing
Contributions are welcomed! Read the [CONTRIBUTING](.github/CONTRIBUTING.md) for more information.

## Licensing
This project is licensed under the Apache V2 License. See [LICENSE](LICENSE) for more information.
