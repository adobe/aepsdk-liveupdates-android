# Getting started

The Live Updates plugin renders Adobe Journey Optimizer (AJO) Live Update pushes as Android 16 promoted ongoing notifications (status-bar chips) and tracks their lifecycle and interactions through the Edge Network. When the Messaging extension receives a Live Update push, it hands the push to the plugin, which builds, posts, and tracks the notification.

## A plugin, not an extension

Live Updates is an Adobe Experience Platform Mobile SDK plugin. Plugins are optional capabilities that are built as separate libraries and found at runtime by type. They are set up differently from extensions:

| | Extensions (Messaging, Edge Network, Edge Identity, ...) | Live Updates plugin |
| --- | --- | --- |
| Registered with | `MobileCore.registerExtensions` | `MobileCore.addPlugins` |
| How it is called | Registered with the Event Hub, and receives events | Looked up with `MobileCore.getPlugin` and called directly by the Messaging extension when a Live Update push arrives |
| `EXTENSION` constant | Yes, for example `Messaging.EXTENSION` | No |

Do not add Live Updates to the `registerExtensions` list. The Messaging extension is still required, because it receives the push and passes it to the plugin.

## Requirements

| Requirement | Version |
| ----------- | ------- |
| App `compileSdk` | 36 or newer |
| App `minSdk` | 21 or newer |
| `com.adobe.marketing.mobile:sdk-bom` | 3.24.0 or newer, if you use the BOM |
| `com.adobe.marketing.mobile:core` | 3.10.0 or newer |
| `com.adobe.marketing.mobile:edge` | 3.1.0 or newer |
| `com.adobe.marketing.mobile:messaging` | 3.13.0 or newer |
| `com.adobe.marketing.mobile:liveupdates` | 3.0.0 or newer |
| `androidx.core:core-ktx` | 1.17.0 or newer. Declare it explicitly. |
| `com.google.firebase:firebase-messaging` | Required. Provided by the app. |
| Device, for promotion to a status-bar chip | Android 16 QPR2 (API 36.1) or newer |

The plugin calls `NotificationCompat` APIs that were added in `androidx.core` 1.17.0, such as `setRequestPromotedOngoing` and `setShortCriticalText`. The plugin's published POM declares only `core` and `edge`, so your app must add `androidx.core:core-ktx` 1.17.0 or newer itself. Without it, Gradle resolves an older `androidx.core` through the other Adobe libraries (1.12.0 with `sdk-bom` 3.24.0), and the plugin fails at runtime when it posts a notification. `androidx.core` 1.17.0 requires `compileSdk` 36 or newer.

Live Updates are promoted to a status-bar chip only on devices running Android 16 QPR2 (API 36.1) or newer, and only when Android allows it (see [Promotion requirements](#promotion-requirements)). Otherwise, the notification is still posted as a regular ongoing notification.

## Add the dependencies

Add the following to your app level gradle file's `dependencies`:

```groovy
implementation platform('com.adobe.marketing.mobile:sdk-bom:<bom-version>')
implementation 'com.adobe.marketing.mobile:core'
implementation 'com.adobe.marketing.mobile:edge'
implementation 'com.adobe.marketing.mobile:edgeidentity'
implementation 'com.adobe.marketing.mobile:messaging'
implementation 'com.adobe.marketing.mobile:liveupdates'

implementation 'androidx.core:core-ktx:1.17.0'
implementation 'com.google.firebase:firebase-messaging:<latest-version>'
```

Replace `<bom-version>` with the latest BOM version listed on [Current SDK versions](https://developer.adobe.com/client-sdks/home/current-sdk-versions/#android-bom). `sdk-bom` 3.24.0 or newer includes all of the required minimum Adobe versions.

## Register the extensions and the Live Updates plugin

Register the Messaging and Edge extensions with `MobileCore.registerExtensions`, and the Live Updates plugin with `MobileCore.addPlugins`. The plugin takes an [`ILiveUpdateStyleProvider`](./api-usage.md#iliveupdatestyleprovider) that returns the notification style for each Live Update.

Add the plugin in `Application.onCreate`, before you register the extensions and initialize the SDK, so it is available when the extensions start processing. Do not add it in an `Activity`: a push can start the app process in the background, and only `Application.onCreate` is guaranteed to run.

#### Kotlin

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        MobileCore.setApplication(this)
        MobileCore.setSmallIconResourceID(R.drawable.ic_notification)

        MobileCore.addPlugins(LiveUpdatePlugin(MyLiveUpdateStyleProvider()))

        MobileCore.registerExtensions(
            listOf(Messaging.EXTENSION, Identity.EXTENSION, Edge.EXTENSION)
        ) {
            MobileCore.configureWithAppID("YOUR_ENVIRONMENT_FILE_ID")
        }

        // Optional: react to Live Update lifecycle events, taps, and dismissals.
        LiveUpdates.setLiveUpdateListener(object : ILiveUpdateListener {
            override fun onStart(payload: LiveUpdatePayload) { /* ... */ }
            override fun onClick(payload: LiveUpdatePayload) { /* open a screen */ }
        })
    }
}
```

#### Java

```java
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();

        MobileCore.setApplication(this);
        MobileCore.setSmallIconResourceID(R.drawable.ic_notification);

        MobileCore.addPlugins(new LiveUpdatePlugin(new MyLiveUpdateStyleProvider()));

        MobileCore.registerExtensions(
                Arrays.asList(Messaging.EXTENSION, Identity.EXTENSION, Edge.EXTENSION),
                o -> MobileCore.configureWithAppID("YOUR_ENVIRONMENT_FILE_ID"));

        // Optional: react to Live Update lifecycle events, taps, and dismissals.
        // Java implementations must override every ILiveUpdateListener method.
        LiveUpdates.setLiveUpdateListener(new ILiveUpdateListener() {
            @Override public void onLiveUpdateReceived(@NonNull LiveUpdatePayload payload) {}
            @Override public void onStart(@NonNull LiveUpdatePayload payload) {}
            @Override public void onUpdate(@NonNull LiveUpdatePayload payload) {}
            @Override public void onEnd(@NonNull LiveUpdatePayload payload) {}
            @Override public void onDismissed(@NonNull LiveUpdatePayload payload) {}
            @Override public void onClick(@NonNull LiveUpdatePayload payload) { /* open a screen */ }
        });
    }
}
```

> [!NOTE]
> The `LiveUpdates` object starts observing configuration the first time it is used, and caches the `messaging.eventDataset` value used to route Live Update tracking events to the AJO dataset. Calling `LiveUpdates.setLiveUpdateListener` or `LiveUpdates.setLiveUpdateInterceptor` in `Application.onCreate`, as shown above, makes sure that value is captured when configuration loads.

## Implement a style provider

The style provider is the only required app code. It receives the parsed [`LiveUpdatePayload`](./api-usage.md#liveupdatepayload) and returns the `NotificationCompat.Style` to apply. App-specific state, such as a template type or progress value, is carried in the payload's `content_state` object, which the plugin does not interpret.

```kotlin
class MyLiveUpdateStyleProvider : ILiveUpdateStyleProvider {
    override fun provideStyle(payload: LiveUpdatePayload): NotificationCompat.Style? {
        val state = payload.contentState
        return when (state?.optString("template_type")) {
            "progress" -> NotificationCompat.ProgressStyle()
                .setProgress(state.optInt("progress", 0))
                .setStyledByProgress(true)
            "big_text" -> NotificationCompat.BigTextStyle().bigText(payload.body)
            else -> null
        }
    }
}
```

The plugin applies no default style. If the provider returns `null`, the notification is still posted without a style, and a `style_null` [diagnostic event](./tracking-and-diagnostics.md#diagnostic-events) is dispatched. The provider is called on the push-processing thread, which is normally the FCM background thread, so keep it fast.

## Receive pushes

Live Update pushes reach the plugin through the Messaging extension. For automatic handling, declare the Messaging extension's `MessagingService` in your `AndroidManifest.xml`:

```xml
<service
    android:name="com.adobe.marketing.mobile.messaging.MessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

This is the same service registration that Adobe Journey Optimizer push notifications use, so if push notifications already work in your app, no change is needed. If your app has its own `FirebaseMessagingService`, or needs to build the notification itself, see [Automatic, mixed, and manual handling of Live Updates](./integration-patterns.md).

## Permissions

The plugin's manifest declares the following permissions, which are merged into your app automatically:

| Permission | Notes |
| ---------- | ----- |
| `android.permission.POST_NOTIFICATIONS` | Runtime permission on Android 13 (API 33) and newer. Your app must request it. |
| `android.permission.POST_PROMOTED_NOTIFICATIONS` | Install-time permission required to request promotion to a status-bar chip. |

The plugin's manifest also declares its own tap tracking activity (`LiveUpdateTrackerActivity`) and dismiss receiver (`LiveUpdateInteractionReceiver`). You do not need to declare them.

## Notification channel

Each Live Update targets the channel named by the payload's `notification_channel_id`. If no channel with that id exists, the plugin creates it with `IMPORTANCE_HIGH`, the name "Live Updates", and the description "Status-bar chips for AJO Live Updates".

If your app has already created the channel, the plugin leaves it unchanged. In that case the channel must have `IMPORTANCE_HIGH` for the Live Update to be promoted to a chip. See [Create and manage notification channels](https://developer.android.com/develop/ui/views/notifications/channels) in the Android documentation.

## Small icon

The plugin resolves the small icon in this order:

1. The drawable named by the payload's `small_icon` field, if it exists in the app's resources.
2. The icon set with `MobileCore.setSmallIconResourceID`.
3. The app icon (`android:icon` in your manifest).

Setting a small icon is optional: if you set none, the plugin uses the app icon. Android draws the small icon as a single-color silhouette in the status bar and in the chip, so a dedicated notification icon usually looks better than the app icon.

## Promotion requirements

The plugin requests promotion for every Live Update and always posts the notification. Android decides whether to promote it to a status-bar chip, based on the device, the notification, the channel, and the user's settings. A Live Update is promoted only when all of the following are true:

- The device runs Android 16 QPR2 (API 36.1) or newer.
- `Notification.hasPromotableCharacteristics()` returns `true`. For example, the notification must use a style that Android allows for Live Updates. Some styles also need a title to render, so whether the payload's `title` is needed depends on the style you use, and can vary by device manufacturer.
- The notification channel exists and has `IMPORTANCE_HIGH`.
- `NotificationManager.canPostPromotedNotifications()` returns `true`. The user can turn off Live Updates for the app in system settings.

For the full list of requirements, see [Live Updates](https://developer.android.com/develop/ui/views/notifications/live-update) in the Android documentation.

Before posting, the plugin checks the device, `hasPromotableCharacteristics()`, the channel, and `canPostPromotedNotifications()`. When a check fails, the notification is posted as a regular ongoing notification, a warning is logged, and an `incompatible` [diagnostic event](./tracking-and-diagnostics.md#incompatible) is dispatched. The plugin's device check only detects devices below API 36. On a device running API 36.0, it does not report the Android version, and Android decides whether to promote the notification.

## Next steps

- [API usage](./api-usage.md)
- [Live Update payload](./payload.md)
- [Test app setup](./testapp-setup.md)
