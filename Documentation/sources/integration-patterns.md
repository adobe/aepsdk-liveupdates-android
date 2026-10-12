# Automatic, mixed, and manual handling of Live Updates

There are three ways to connect Live Update pushes to the Live Updates plugin. In the first two, the plugin posts the notification. In the third, your app posts it.

- **Automatic**: If your app registers the Messaging extension's `MessagingService` as its push receiver, it takes care of everything for you: Adobe Journey Optimizer push notifications and Live Updates.
- **Mixed**: If your app has its own `FirebaseMessagingService`, it receives every push and hands Adobe Journey Optimizer pushes to the Messaging extension, which takes care of both standard pushes and Live Updates.
- **Manual**: Your app builds and posts the Live Update notification itself and uses the plugin only for tracking. Use this only when you need full control over the notification.

| | Automatic | Mixed | Manual |
| --- | --------- | ----- | ------ |
| FCM entry point | Messaging's `MessagingService` | Your `FirebaseMessagingService` | Your `FirebaseMessagingService` |
| Who posts the notification | `LiveUpdatePlugin` | `LiveUpdatePlugin` | Your app |
| `MobileCore.addPlugins(LiveUpdatePlugin(...))` | Required | Required | Not required |
| Style provider, interceptor, and timestamp validation | Yes | Yes | No |
| Promotion checks and diagnostic events | Yes | Yes | No |
| Lifecycle tracking | Automatic | Automatic | Only when you call `LiveUpdates.trackLiveUpdateEvent` |
| `onLiveUpdateReceived`, `onStart`, `onUpdate`, and `onEnd` callbacks | Automatic | Automatic | Only when you call `LiveUpdates.trackLiveUpdateEvent` |
| Tap and dismiss tracking | Automatic | Automatic | `addPushTrackingDetails` and `handleNotificationResponse` |
| `onClick` and `onDismissed` callbacks | Yes | Yes | No |

## Automatic handling

Register the plugin as shown in [Getting started](./getting-started.md#register-the-extensions-and-the-live-updates-plugin), and declare the Messaging extension's service in your `AndroidManifest.xml`:

```xml
<service
    android:name="com.adobe.marketing.mobile.messaging.MessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

This is the same service registration that Adobe Journey Optimizer push notifications use, described in [Automatically display and track push notification](https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/push-notification/android/automatic-display-and-tracking). If push notifications already work in your app, the only additional step is registering the plugin.

`MessagingService` detects the `adb_liveupdate_data` key and passes the push to the registered `ILiveupdatePlugin`. All other AJO pushes are displayed by the Messaging extension as usual.

If a Live Update push arrives and no plugin is registered, Messaging drops it, logs a warning, and dispatches a `no_plugin` [diagnostic event](./tracking-and-diagnostics.md#diagnostic-events).

## Mixed handling

If your app already has its own `FirebaseMessagingService`, register it instead of `MessagingService` and pass every message to `MessagingService.handleRemoteMessage`, as described in [Using your own FirebaseMessagingService](https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/push-notification/android/automatic-display-and-tracking#using-your-own-firebasemessagingservice). The same call handles both standard Adobe Journey Optimizer pushes and Live Updates: it routes Live Updates to the plugin exactly as in automatic handling, so your style provider, interceptor, listener, and automatic tracking all work. The method returns `true` for any AJO push it handled, including Live Updates, and `false` for pushes that were not sent by AJO, so your app can handle those itself.

```kotlin
class MyPushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        MobileCore.setPushIdentifier(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (MessagingService.handleRemoteMessage(this, message)) return
        // Handle pushes that were not sent by AJO.
    }
}
```

## Manual handling

Use manual handling only when your app needs full control of the notification. Your app parses the payload, builds and posts the notification, and uses the plugin's `LiveUpdates` APIs only for tracking. It is the Live Update counterpart of [Manual display and tracking of push notification](https://developer.adobe.com/client-sdks/edge/adobe-journey-optimizer/push-notification/android/manual-display-and-tracking).

> [!WARNING]
> In manual mode, your app controls the notification fully, and the plugin flow does not run, even if you registered `LiveUpdatePlugin`, a listener, and an interceptor:
>
> - Your `ILiveUpdateInterceptor` and `ILiveUpdateStyleProvider` are not called.
> - `event_type` and `timestamp` are not validated, and no promotion checks or diagnostic events run.
> - `onLiveUpdateReceived`, `onStart`, `onUpdate`, and `onEnd` are called only when your app calls `LiveUpdates.trackLiveUpdateEvent`.
> - `onClick` and `onDismissed` are never called.

Because your app builds the notification, it must set a small icon. The plugin's fallback to the app icon applies only to notifications that the plugin posts. Android rejects a notification without a small icon: for apps targeting API 23 or higher, `NotificationManager.notify` throws an `IllegalArgumentException`.

```kotlin
class MyPushService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (LiveUpdatePayload.isLiveUpdate(message)) {
            val payload = LiveUpdatePayload.parse(message) ?: return

            val tapIntent = Intent(this, MainActivity::class.java).apply {
                LiveUpdates.addPushTrackingDetails(this, message)
            }
            val tapPendingIntent = PendingIntent.getActivity(
                this,
                payload.notificationId.hashCode(),
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, payload.channelId)
                .setSmallIcon(R.drawable.ic_notification) // required in manual mode
                .setContentTitle(payload.title)
                .setContentText(payload.body)
                .setOngoing(true)
                .setRequestPromotedOngoing(true)
                .setContentIntent(tapPendingIntent)
                .build()
            NotificationManagerCompat.from(this)
                .notify(payload.notificationId.hashCode(), notification)

            // Dispatches the lifecycle tracking event and calls the listener.
            LiveUpdates.trackLiveUpdateEvent(this, message)
            return
        }

        MessagingService.handleRemoteMessage(this, message)
    }
}
```

Then, in the activity that the tap intent opens, dispatch the tap tracking event:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    LiveUpdates.handleNotificationResponse(intent, applicationOpened = true)
}
```

To track dismissals, set a delete intent that targets your own `BroadcastReceiver`, add the tracking details to it with `addPushTrackingDetails`, and call the following from the receiver:

```kotlin
LiveUpdates.handleNotificationResponse(intent, false, LiveUpdates.ACTION_ID_DISMISS)
```

## Local Live Updates

Your app can start a Live Update from its own state without an FCM push, for example for a workout timer or a download. Build a payload with `EVENT_TYPE_LOCAL_START` and pass it to `LiveUpdates.triggerLocalLiveUpdate`. This requires `LiveUpdatePlugin` to be registered.

```kotlin
val payload = LiveUpdatePayload.create(
    notificationId = "order_1234",
    channelId = "live_updates_channel",
    eventType = LiveUpdatePayload.EVENT_TYPE_LOCAL_START,
    title = "Order #1234",
    timestamp = System.currentTimeMillis() / 1000,
    body = "Preparing your order",
    criticalText = "Preparing",
    contentState = JSONObject().put("template_type", "progress").put("progress", 10)
)
LiveUpdates.triggerLocalLiveUpdate(context, payload)
```

The local Live Update is rendered like a pushed one, and the listener's `onLiveUpdateReceived` and `onStart` are called. Because it has no `_xdm`, no tracking event is dispatched at that point.

Instead, the plugin stores the `notification_id` and `notification_channel_id`. When the first backend push with the same ids and an `_xdm` arrives, the plugin dispatches a `liveupdate_localstart` event, timestamped with when the local start happened, before that push's own tracking event. This links the local start to the campaign. The catch-up is dispatched only once.

The backend push still goes through the interceptor and validation, so it must not be dropped by your interceptor, and its `timestamp` must be newer than the `timestamp` of the local payload. A local start that is not reported within 28 days is discarded.

## Topic subscriptions

Broadcast Live Updates, such as a sports score sent to many devices, are delivered through FCM topics. The plugin does not manage topic subscriptions. Your app subscribes through Firebase, typically from a listener callback, and reports the change with `trackTopicSubscribed` or `trackTopicUnsubscribed`.

Subscribe in `onStart`. Unsubscribe in `onEnd`, and also in `onDismissed`, so a device whose user dismissed the Live Update stops receiving its broadcast updates:

```kotlin
LiveUpdates.setLiveUpdateListener(object : ILiveUpdateListener {
    override fun onStart(payload: LiveUpdatePayload) {
        val topic = payload.topicName ?: return
        FirebaseMessaging.getInstance().subscribeToTopic(topic).addOnCompleteListener { task ->
            if (task.isSuccessful) LiveUpdates.trackTopicSubscribed(payload)
        }
    }

    override fun onEnd(payload: LiveUpdatePayload) {
        unsubscribeFromTopic(payload)
    }

    override fun onDismissed(payload: LiveUpdatePayload) {
        // The user dismissed the Live Update: stop receiving its broadcast updates.
        unsubscribeFromTopic(payload)
    }

    private fun unsubscribeFromTopic(payload: LiveUpdatePayload) {
        val topic = payload.topicName ?: return
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic).addOnCompleteListener { task ->
            if (task.isSuccessful) LiveUpdates.trackTopicUnsubscribed(payload)
        }
    }
})
```

No topic event is dispatched when the payload has no `topic_name` or no `_xdm`.

## Handling taps and dismissals

With automatic or mixed handling, the plugin handles chip interactions:

- **Tap**: The plugin's `LiveUpdateTrackerActivity` dispatches a `liveUpdateTracking.applicationOpened` event and calls `ILiveUpdateListener.onClick`. The plugin does not open the app or any deep link. Open the screen you want from `onClick`:

  ```kotlin
  override fun onClick(payload: LiveUpdatePayload) {
      val intent = Intent(applicationContext, MainActivity::class.java)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      startActivity(intent)
  }
  ```

- **Dismiss**: When the user swipes the chip away, `LiveUpdateInteractionReceiver` dispatches a `liveUpdateTracking.customAction` event with action id `Dismiss` and calls `ILiveUpdateListener.onDismissed`.

The listener must be registered in `Application.onCreate`, because a tap or dismissal can start the app process just to handle it. In that case, the payload is rebuilt from the most recently posted version of the notification.

After a Live Update has ended, `onDismissed` is not called, whether the notification is removed automatically by `dismiss_after` or swiped away by the user. No dismiss tracking event is dispatched either, because notifications posted for an `end` push have no delete intent.

### Suppress Live Updates the user dismissed

After the user dismisses a Live Update, later `update` or `end` pushes for the same `notification_id` show the notification again. To prevent that, record dismissed ids in `onDismissed` and drop them with an interceptor:

```kotlin
LiveUpdates.setLiveUpdateInterceptor(object : ILiveUpdateInterceptor {
    override fun shouldDisplayLiveUpdate(payload: LiveUpdatePayload): Boolean =
        !dismissedStore.isDismissed(payload.notificationId)
})
```

The test app's `SampleLiveUpdateInterceptor` and `DismissedLiveUpdateStore` show a complete implementation.
