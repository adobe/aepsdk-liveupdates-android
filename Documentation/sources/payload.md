# Live Update payload

A Live Update is an FCM data message that carries two data keys:

| FCM data key | Required | Description |
| ------------ | -------- | ----------- |
| `adb_liveupdate_data` | Yes | The Live Update envelope, as a JSON string. Its presence marks the message as a Live Update. |
| `_xdm` | Yes, for tracking | The AJO tracking data, as a JSON string. It is passed through to the tracking events so AJO can attribute them to the campaign or journey. AJO adds it automatically. Without it, the Live Update still renders but no tracking event is dispatched. |

When the push is received through the Messaging extension, the message must also be recognized as an AJO push, which requires either `_xdm` or `adb_title` to be present.

## FCM message example

```json
{
  "message": {
    "token": "<FCM_REGISTRATION_TOKEN>",
    "android": {
      "priority": "HIGH",
      "data": {
        "_xdm": "{\"mixins\":{\"_experience\":{\"customerJourneyManagement\":{\"messageExecution\":{\"messageExecutionID\":\"...\",\"campaignID\":\"...\"}}}}}",
        "adb_liveupdate_data": "{\"notification_id\":\"flight_DL241_2026_10_01\",\"notification_channel_id\":\"live_updates_channel\",\"event_type\":\"start\",\"timestamp\":1790850000,\"title\":\"Flight DL241\"}"
      }
    }
  }
}
```

FCM merges `message.data` and `message.android.data` into `RemoteMessage.getData()`, so either location works.

## Envelope keys

| Key | Required | Type | Description |
| --- | -------- | ---- | ----------- |
| `notification_id` | Yes | String | Identifies the Live Update. Every push with the same id updates the same notification. |
| `notification_channel_id` | Yes | String | The Android notification channel. The plugin creates the channel with `IMPORTANCE_HIGH` if it does not exist. |
| `event_type` | Yes | String | `start`, `update`, or `end`. Local Live Updates use `localstart`. Any other value is dropped. |
| `timestamp` | Yes | Number | When the state was produced, in epoch **seconds**. See [Validation](#validation). |
| `title` | No | String | The notification title. Required for the notification to be promoted to a chip. |
| `body` | No | String | The notification text. |
| `priority` | No | String | `PRIORITY_MAX`, `PRIORITY_HIGH`, `PRIORITY_LOW`, or `PRIORITY_MIN`. Any other value uses `PRIORITY_DEFAULT`. |
| `critical_text` | No | String | Short text shown in the status-bar chip. |
| `when` | No | Number | A time to display on the notification, in epoch **seconds**. An invalid value is ignored. |
| `dismiss_after` | No | Number | Only applies when `event_type` is `end`. The number of seconds after the end push arrives before the notification is removed. |
| `content_state` | No | Object | App-defined state for the style provider, such as a template type or progress. The plugin does not interpret it. |
| `topic_name` | No | String | The FCM topic this Live Update is broadcast on. Reported in tracking events and used by `trackTopicSubscribed` and `trackTopicUnsubscribed`. |
| `small_icon` | No | String | The name of a drawable resource in your app to use as the small icon, for example `ic_flight_notification`. |

## Envelope examples

### Start

```json
{
  "notification_id": "flight_DL241_2026_10_01",
  "notification_channel_id": "live_updates_channel",
  "event_type": "start",
  "timestamp": 1790850000,
  "title": "Flight DL241",
  "body": "Boarding at gate D22",
  "priority": "PRIORITY_HIGH",
  "critical_text": "Boarding",
  "when": 1790853600,
  "topic_name": "flight_DL241",
  "small_icon": "ic_flight_notification",
  "content_state": {
    "template_type": "progress",
    "progress": 10
  }
}
```

### Update

The same `notification_id` and `notification_channel_id`, with a newer `timestamp`. The notification is updated in place.

```json
{
  "notification_id": "flight_DL241_2026_10_01",
  "notification_channel_id": "live_updates_channel",
  "event_type": "update",
  "timestamp": 1790851800,
  "title": "Flight DL241",
  "body": "In flight - landing in 40 min",
  "critical_text": "40 min",
  "topic_name": "flight_DL241",
  "content_state": {
    "template_type": "progress",
    "progress": 60
  }
}
```

### End with automatic dismissal

```json
{
  "notification_id": "flight_DL241_2026_10_01",
  "notification_channel_id": "live_updates_channel",
  "event_type": "end",
  "timestamp": 1790854200,
  "title": "Flight DL241",
  "body": "Landed at MUM",
  "critical_text": "Landed",
  "dismiss_after": 300,
  "topic_name": "flight_DL241",
  "content_state": {
    "template_type": "progress",
    "progress": 100
  }
}
```

`dismiss_after` is a duration measured from when the end push is posted on the device. Because it is relative, it does not depend on the device clock.

## Validation

A Live Update is dropped, and a warning is logged, when:

| Condition | Diagnostic event |
| --------- | ---------------- |
| `adb_liveupdate_data` is not valid JSON, or a required key is missing or empty. | None |
| `timestamp` is not a positive epoch-seconds value. Epoch milliseconds are rejected. | None |
| `event_type` is not `start`, `update`, `end`, or `localstart`. | `invalid_event_type` |
| `timestamp` is more than 28 days old, the FCM delivery window. | `invalid_timestamp` |
| `timestamp` is not newer than the last accepted `timestamp` for the same `notification_id` and `notification_channel_id`. This rejects duplicate and out-of-order pushes. | `outdated_timestamp` |
| The app's [`ILiveUpdateInterceptor`](./api-usage.md#iliveupdateinterceptor) returns `false`. | `app_discarded` |

Accepted timestamps are stored in a local SQLite database and kept for 28 days. See [Tracking and diagnostic events](./tracking-and-diagnostics.md#diagnostic-events) for the diagnostic event format.

## How envelope fields are applied

`LiveUpdatePlugin` builds a new `NotificationCompat.Builder` for every push:

| Builder call | Source |
| ------------ | ------ |
| Notification id | `notification_id.hashCode()` |
| Channel | `notification_channel_id` |
| `setSmallIcon` | `small_icon`, otherwise `MobileCore.setSmallIconResourceID`, otherwise the app icon |
| `setContentTitle` / `setContentText` | `title` / `body` |
| `setOngoing(true)` and `setRequestPromotedOngoing(true)` | Always |
| `setPriority` | `priority` |
| `setStyle` | The value returned by your `ILiveUpdateStyleProvider`, if not `null` |
| `setShortCriticalText` | `critical_text` |
| `setWhen` and `setShowWhen(true)` | `when` |
| `setTimeoutAfter` | `dismiss_after`, only for `end` pushes with a value greater than 0 |
| `setContentIntent` | The plugin's tap tracking activity |
| `setDeleteIntent` | The plugin's dismiss receiver. Not set for `end` pushes. |
