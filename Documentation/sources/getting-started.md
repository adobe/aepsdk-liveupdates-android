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
| `com.adobe.marketing.mobile:core` | 3.10.0 or newer |
| `com.adobe.marketing.mobile:edge` | 3.1.0 or newer |
| `com.adobe.marketing.mobile:messaging` | 3.13.0 or newer |
| `com.google.firebase:firebase-messaging` | Required. Provided by the app. |

The plugin is compiled against API 36 and `androidx.core:core-ktx` 1.17.0, so apps with a lower `compileSdk` fail the AAR metadata check at build time.

Live Updates are promoted to a status-bar chip only on devices running Android 16 (API 36) or newer. On older devices, or when promotion is not possible (see [Promotion requirements](#promotion-requirements)), the notification is still posted as a regular ongoing notification.

## Add the dependencies

Add the following to your app level gradle file's `dependencies`:

```groovy
implementation platform('com.adobe.marketing.mobile:sdk-bom:3.+')
implementation 'com.adobe.marketing.mobile:core'
implementation 'com.adobe.marketing.mobile:edge'
implementation 'com.adobe.marketing.mobile:edgeidentity'
implementation 'com.adobe.marketing.mobile:messaging'
implementation 'com.adobe.marketing.mobile:liveupdates'

implementation 'com.google.firebase:firebase-messaging:<latest-version>'
```

`sdk-bom` 3.24.0 or newer includes all of the required minimum versions.

## Register the extensions and the Live Updates plugin

Register the Messaging and Edge extensions with `MobileCore.registerExtensions`, and the Live Updates plugin with `MobileCore.addPlugins`. The plugin takes an [`ILiveUpdateStyleProvider`](./api-usage.md#iliveupdatestyleprovider) that returns the notification style for each Live Update.

Register the plugin in `Application.onCreate`, not in an `Activity`, so it is available when a push arrives and the system starts the app process in the background.

#### Kotlin

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        MobileCore.setApplication(this)
        MobileCore.setSmallIconResourceID(R.drawable.ic_notification)

        MobileCore.registerExtensions(
            listOf(Messaging.EXTENSION, Identity.EXTENSION, Edge.EXTENSION)
        ) {
            MobileCore.configureWithAppID("YOUR_ENVIRONMENT_FILE_ID")
        }

        MobileCore.addPlugins(LiveUpdatePlugin(MyLiveUpdateStyleProvider()))

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

        MobileCore.registerExtensions(
                Arrays.asList(Messaging.EXTENSION, Identity.EXTENSION, Edge.EXTENSION),
                o -> MobileCore.configureWithAppID("YOUR_ENVIRONMENT_FILE_ID"));

        MobileCore.addPlugins(new LiveUpdatePlugin(new MyLiveUpdateStyleProvider()));

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

If your app has its own `FirebaseMessagingService`, or needs to build the notification itself, see [Automatic, mixed, and manual handling of Live Updates](./integration-patterns.md).

## Permissions

The plugin's manifest declares the following permissions, which are merged into your app automatically:

| Permission | Notes |
| ---------- | ----- |
| `android.permission.POST_NOTIFICATIONS` | Runtime permission on Android 13 (API 33) and newer. Your app must request it. |
| `android.permission.POST_PROMOTED_NOTIFICATIONS` | Install-time permission required to request promotion to a status-bar chip. |

The plugin's manifest also declares its own tap tracking activity (`LiveUpdateTrackerActivity`) and dismiss receiver (`LiveUpdateInteractionReceiver`). You do not need to declare them.

## Notification channel

Each Live Update targets the channel named by the payload's `notification_channel_id`. If no channel with that id exists, the plugin creates it with `IMPORTANCE_HIGH`, the name "Live Updates", and the description "Status-bar chips for AJO Live Updates".

If your app has already created the channel, the plugin leaves it unchanged. In that case the channel must have `IMPORTANCE_HIGH` for the Live Update to be promoted to a chip.

## Small icon

The small icon is resolved in this order:

1. The drawable named by the payload's `small_icon` field, if it exists in the app's resources.
2. The icon set with `MobileCore.setSmallIconResourceID`.
3. The app's launcher icon.

## Promotion requirements

A posted Live Update is promoted to a status-bar chip only when all of the following are true. When one is not, the notification is posted as a regular ongoing notification, a warning is logged, and an `incompatible` [diagnostic event](./tracking-and-diagnostics.md#diagnostic-events) is dispatched.

- The device runs Android 16 (API 36) or newer.
- `Notification.hasPromotableCharacteristics()` returns `true`. For example, the style must be eligible for promotion, and the payload must include a `title`.
- The notification channel exists and has `IMPORTANCE_HIGH`.
- `NotificationManager.canPostPromotedNotifications()` returns `true`. The user can turn off Live Updates for the app in system settings.

## Next steps

- [API usage](./api-usage.md)
- [Live Update payload](./payload.md)
- [Test app setup](./testapp-setup.md)
