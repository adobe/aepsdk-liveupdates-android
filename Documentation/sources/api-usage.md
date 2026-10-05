# API usage

All public classes of the Live Updates plugin are in the `com.adobe.marketing.mobile.messaging.liveupdate` package.

- [LiveUpdatePlugin](#liveupdateplugin)
- [LiveUpdates](#liveupdates)
  - [extensionVersion](#extensionversion)
  - [setLiveUpdateListener / getLiveUpdateListener](#setliveupdatelistener--getliveupdatelistener)
  - [setLiveUpdateInterceptor / getLiveUpdateInterceptor](#setliveupdateinterceptor--getliveupdateinterceptor)
  - [triggerLocalLiveUpdate](#triggerlocalliveupdate)
  - [trackLiveUpdateEvent](#trackliveupdateevent)
  - [addPushTrackingDetails](#addpushtrackingdetails)
  - [handleNotificationResponse](#handlenotificationresponse)
  - [trackTopicSubscribed / trackTopicUnsubscribed](#tracktopicsubscribed--tracktopicunsubscribed)
- [ILiveUpdateStyleProvider](#iliveupdatestyleprovider)
- [ILiveUpdateListener](#iliveupdatelistener)
- [ILiveUpdateInterceptor](#iliveupdateinterceptor)
- [LiveUpdatePayload](#liveupdatepayload)

---

## LiveUpdatePlugin

The class you register with `MobileCore.addPlugins`. It implements Mobile Core's `com.adobe.marketing.mobile.plugin.ILiveupdatePlugin` contract, and the Messaging extension hands every Live Update push to it. The plugin parses the push, asks your style provider for a style, posts the notification, dispatches tracking, and calls your listener.

#### Syntax

```kotlin
class LiveUpdatePlugin(styleProvider: ILiveUpdateStyleProvider) : ILiveupdatePlugin
```

#### Example

```kotlin
MobileCore.addPlugins(LiveUpdatePlugin(MyLiveUpdateStyleProvider()))
```

```java
MobileCore.addPlugins(new LiveUpdatePlugin(new MyLiveUpdateStyleProvider()));
```

For each push, the plugin performs these steps in order:

1. Drops the push if it is not a Firebase `RemoteMessage`, or if `adb_liveupdate_data` fails to parse.
2. Consults the registered [`ILiveUpdateInterceptor`](#iliveupdateinterceptor). If it returns `false`, the Live Update is dropped.
3. Drops the Live Update if `event_type` is not `start`, `update`, `end`, or `localstart`.
4. Drops the Live Update if its `timestamp` is more than 28 days old, or is not newer than the last accepted `timestamp` for the same `notification_id` and `notification_channel_id`.
5. Calls the [`ILiveUpdateStyleProvider`](#iliveupdatestyleprovider).
6. Creates the notification channel if needed, then builds and posts the notification with id `notification_id.hashCode()`. A later push with the same `notification_id` updates the notification in place.
7. Dispatches the [lifecycle tracking event](./tracking-and-diagnostics.md#tracking-events) and calls the [`ILiveUpdateListener`](#iliveupdatelistener).

The [payload reference](./payload.md#how-envelope-fields-are-applied) explains how each envelope field is applied to the notification.

---

## LiveUpdates

The public entry point for listener registration, local Live Updates, and manual tracking.

### extensionVersion

Returns the version of the Live Updates plugin. The method keeps the `extensionVersion` name used by the other Adobe Experience Platform Mobile SDK libraries.

#### Syntax

```kotlin
@JvmStatic fun extensionVersion(): String
```

#### Example

```kotlin
val version = LiveUpdates.extensionVersion()
```

```java
String version = LiveUpdates.extensionVersion();
```

### setLiveUpdateListener / getLiveUpdateListener

Registers an [`ILiveUpdateListener`](#iliveupdatelistener) that receives lifecycle, tap, and dismiss callbacks. Only one listener is active at a time. Setting a new listener replaces the previous one, and passing `null` clears it.

The listener is held in memory only. Register it in `Application.onCreate` so it is available when the app process is started in the background to handle a push, tap, or dismissal.

#### Syntax

```kotlin
@JvmStatic fun setLiveUpdateListener(listener: ILiveUpdateListener?)
@JvmStatic fun getLiveUpdateListener(): ILiveUpdateListener?
```

#### Example

```kotlin
LiveUpdates.setLiveUpdateListener(object : ILiveUpdateListener {
    override fun onStart(payload: LiveUpdatePayload) {
        Log.d(TAG, "Live Update started: ${payload.notificationId}")
    }
})
```

### setLiveUpdateInterceptor / getLiveUpdateInterceptor

Registers an [`ILiveUpdateInterceptor`](#iliveupdateinterceptor) that can stop a Live Update before it is rendered, tracked, or sent to the listener. Only one interceptor is active at a time. Passing `null` clears it, after which every Live Update proceeds.

The interceptor applies to Live Updates rendered by `LiveUpdatePlugin`, including local Live Updates. It does not apply to [manual handling](./integration-patterns.md#manual-handling).

#### Syntax

```kotlin
@JvmStatic fun setLiveUpdateInterceptor(interceptor: ILiveUpdateInterceptor?)
@JvmStatic fun getLiveUpdateInterceptor(): ILiveUpdateInterceptor?
```

#### Example

```kotlin
LiveUpdates.setLiveUpdateInterceptor(object : ILiveUpdateInterceptor {
    override fun shouldDisplayLiveUpdate(payload: LiveUpdatePayload): Boolean =
        !dismissedIds.contains(payload.notificationId)
})
```

### triggerLocalLiveUpdate

Renders a Live Update from app state, without an FCM push. The payload goes through the same steps as a push, including the interceptor, timestamp validation, style provider, and listener callbacks. Build the payload with [`LiveUpdatePayload.create`](#create) and use `EVENT_TYPE_LOCAL_START` as the event type.

Returns `true` if `LiveUpdatePlugin` is registered and the payload was handed to it. Returns `false` if no plugin is registered, or if the registered `ILiveupdatePlugin` is a custom implementation. A return value of `true` does not mean the notification was posted, because the payload can still be dropped, for example by the interceptor.

A `localstart` Live Update does not dispatch a tracking event immediately because it has no `_xdm`. See [Local Live Updates](./integration-patterns.md#local-live-updates) for how the start is reported later.

#### Syntax

```kotlin
@JvmStatic fun triggerLocalLiveUpdate(context: Context, payload: LiveUpdatePayload): Boolean
```

#### Example

```kotlin
val payload = LiveUpdatePayload.create(
    notificationId = "workout_42",
    channelId = "live_updates_channel",
    eventType = LiveUpdatePayload.EVENT_TYPE_LOCAL_START,
    title = "Morning run",
    timestamp = System.currentTimeMillis() / 1000,
    criticalText = "5 km",
    contentState = JSONObject().put("progress", 10)
)
val rendered = LiveUpdates.triggerLocalLiveUpdate(context, payload)
```

### trackLiveUpdateEvent

For [manual handling](./integration-patterns.md#manual-handling), where the app builds and posts the notification itself. This method dispatches the lifecycle tracking event for the push and calls the registered listener. It does not post a notification and does not consult the interceptor.

If the payload does not parse, nothing happens. If `event_type` is not recognized, no tracking event is dispatched and only `onLiveUpdateReceived` is called. As with all tracking, no event is dispatched if the push has no `_xdm`.

#### Syntax

```kotlin
@JvmStatic fun trackLiveUpdateEvent(context: Context, message: RemoteMessage)
```

#### Example

```kotlin
override fun onMessageReceived(message: RemoteMessage) {
    if (LiveUpdatePayload.isLiveUpdate(message)) {
        // Build and post your own notification here.
        LiveUpdates.trackLiveUpdateEvent(this, message)
        return
    }
    MessagingService.handleRemoteMessage(this, message)
}
```

### addPushTrackingDetails

For manual handling. Adds Live Update tracking extras to an `Intent` that your app uses in a notification `PendingIntent`. When the intent is later passed to [`handleNotificationResponse`](#handlenotificationresponse), the extras are used to build the interaction tracking event.

Returns `true` if the extras were added. Returns `false` if either argument is `null` or the message is not a valid Live Update.

#### Syntax

```kotlin
@JvmStatic fun addPushTrackingDetails(intent: Intent?, message: RemoteMessage?): Boolean
```

#### Example

```kotlin
val tapIntent = Intent(context, MainActivity::class.java).apply {
    LiveUpdates.addPushTrackingDetails(this, message)
}
```

### handleNotificationResponse

Dispatches an interaction tracking event (a tap, an action, or a dismissal) for an intent prepared with [`addPushTrackingDetails`](#addpushtrackingdetails). The plugin's own tap activity and dismiss receiver call it automatically. Call it yourself only when you handle interactions in manual mode.

| Interaction | `applicationOpened` | `customActionId` |
| ----------- | ------------------- | ---------------- |
| Notification tap | `true` | `null` |
| Custom action | `false` | The action id |
| Dismissal | `false` | `LiveUpdates.ACTION_ID_DISMISS` (`"Dismiss"`) |

Returns `true` if the intent carries a Live Update notification id, even when no event is dispatched because the intent has no `_xdm`. Returns `false` for `null` or non-Live Update intents, so you can fall back to `Messaging.handleNotificationResponse` for standard pushes.

This method only dispatches tracking. It does not call `ILiveUpdateListener.onClick` or `onDismissed`.

#### Syntax

```kotlin
@JvmStatic
@JvmOverloads
fun handleNotificationResponse(
    intent: Intent?,
    applicationOpened: Boolean,
    customActionId: String? = null
): Boolean
```

#### Example

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (!LiveUpdates.handleNotificationResponse(intent, true)) {
        Messaging.handleNotificationResponse(intent, true, null)
    }
}
```

### trackTopicSubscribed / trackTopicUnsubscribed

Dispatches a topic tracking event after your app has subscribed the device to, or unsubscribed it from, an FCM topic. The topic is taken from the payload's `topic_name`. The event is linked to the originating campaign through the payload's `_xdm`.

The plugin does not subscribe or unsubscribe the device itself. Call `FirebaseMessaging.subscribeToTopic` or `unsubscribeFromTopic` first, then call these methods when the Firebase task succeeds. No event is dispatched if the payload has no `topic_name` or no `_xdm`.

#### Syntax

```kotlin
@JvmStatic fun trackTopicSubscribed(payload: LiveUpdatePayload)
@JvmStatic fun trackTopicUnsubscribed(payload: LiveUpdatePayload)
```

#### Example

```kotlin
override fun onStart(payload: LiveUpdatePayload) {
    val topic = payload.topicName ?: return
    FirebaseMessaging.getInstance().subscribeToTopic(topic).addOnCompleteListener { task ->
        if (task.isSuccessful) LiveUpdates.trackTopicSubscribed(payload)
    }
}
```

---

## ILiveUpdateStyleProvider

The interface your app implements to supply the notification style for each Live Update. Return any `NotificationCompat.Style`, for example `ProgressStyle` on API 36+ or a newer style on later API levels. The plugin does not inspect the returned style.

Returning `null` posts the notification without a style and dispatches a `style_null` diagnostic event. The provider is called on the push-processing thread.

#### Syntax

```kotlin
fun interface ILiveUpdateStyleProvider {
    fun provideStyle(payload: LiveUpdatePayload): NotificationCompat.Style?
}
```

#### Example

```java
ILiveUpdateStyleProvider provider = payload ->
        new NotificationCompat.ProgressStyle()
                .setProgress(payload.getContentState() != null
                        ? payload.getContentState().optInt("progress", 0)
                        : 0);
```

---

## ILiveUpdateListener

Optional callbacks for Live Update activity. In Kotlin, every method has an empty default implementation. In Java, override every method.

| Method | Called when |
| ------ | ----------- |
| `onLiveUpdateReceived(payload)` | First, for every processed Live Update, regardless of `event_type`. |
| `onStart(payload)` | `event_type` is `start` or `localstart`. |
| `onUpdate(payload)` | `event_type` is `update`. |
| `onEnd(payload)` | `event_type` is `end`. |
| `onClick(payload)` | The user taps the chip body. The plugin records the tap but does not open the app. Your app decides what to open. |
| `onDismissed(payload)` | The user swipes the chip away. Not called once the Live Update has ended, as explained in [Handling taps and dismissals](./integration-patterns.md#handling-taps-and-dismissals). |

`onClick` and `onDismissed` may run in an app process that was started just to deliver the interaction. Their payload is rebuilt from data saved on the notification when it was last posted, so use `payload.notificationId` to look up current state in your app. The plugin catches and logs exceptions thrown from the listener. The receive callbacks run on the push-processing thread, so do not do long-running work in them.

---

## ILiveUpdateInterceptor

Decides whether the plugin should process a Live Update. Return `false` to drop it entirely: no notification, tracking event, or listener callback. An `app_discarded` diagnostic event is dispatched instead. If the interceptor throws, the plugin proceeds as if it returned `true`.

A common use is suppressing later pushes for a Live Update the user has already dismissed.

#### Syntax

```kotlin
interface ILiveUpdateInterceptor {
    fun shouldDisplayLiveUpdate(payload: LiveUpdatePayload): Boolean
}
```

---

## LiveUpdatePayload

The parsed Live Update envelope. See the [payload reference](./payload.md) for the wire format.

#### Properties

| Property | Type | Envelope key |
| -------- | ---- | ------------ |
| `notificationId` | `String` | `notification_id` |
| `channelId` | `String` | `notification_channel_id` |
| `eventType` | `String` | `event_type` |
| `timestamp` | `Long` (epoch seconds) | `timestamp` |
| `title` | `String?` | `title` |
| `body` | `String?` | `body` |
| `priority` | `String?` | `priority` |
| `criticalText` | `String?` | `critical_text` |
| `whenSeconds` | `Long?` (epoch seconds) | `when` |
| `dismissAfterSeconds` | `Long?` | `dismiss_after` |
| `contentState` | `JSONObject?` | `content_state` |
| `topicName` | `String?` | `topic_name` |
| `smallIcon` | `String?` | `small_icon` |
| `xdm` | `JSONObject?` | The FCM data key `_xdm` |

#### Constants

| Constant | Value |
| -------- | ----- |
| `EVENT_TYPE_START` | `"start"` |
| `EVENT_TYPE_UPDATE` | `"update"` |
| `EVENT_TYPE_END` | `"end"` |
| `EVENT_TYPE_LOCAL_START` | `"localstart"` |

### parse

Parses a `RemoteMessage` into a payload. Returns `null` if `adb_liveupdate_data` is missing or malformed, or if a required field is missing or invalid.

```kotlin
@JvmStatic fun parse(message: RemoteMessage): LiveUpdatePayload?
```

### isLiveUpdate

Returns `true` if the message's data contains the `adb_liveupdate_data` key. The payload is not validated.

```kotlin
@JvmStatic fun isLiveUpdate(message: RemoteMessage): Boolean
```

### create

Builds a payload from values in your app, typically for [`triggerLocalLiveUpdate`](#triggerlocalliveupdate). `notificationId`, `channelId`, `eventType`, and `timestamp` are required, and `title` is nullable. All other parameters are optional. From Java, use the overloads generated by `@JvmOverloads`.

`timestamp` is in epoch seconds. If you pass a value too large to be seconds, such as epoch milliseconds, it is converted to seconds and a warning is logged.

```kotlin
@JvmStatic
@JvmOverloads
fun create(
    notificationId: String,
    channelId: String,
    eventType: String,
    title: String?,
    timestamp: Long,
    priority: String? = null,
    body: String? = null,
    criticalText: String? = null,
    whenSeconds: Long? = null,
    dismissAfterSeconds: Long? = null,
    contentState: JSONObject? = null,
    topicName: String? = null,
    smallIcon: String? = null,
    xdm: JSONObject? = null
): LiveUpdatePayload
```
