# Tracking and diagnostic events

The Live Updates extension dispatches two kinds of events:

- **Tracking events** are Edge requests. They are sent to Adobe Experience Platform for AJO reporting.
- **Diagnostic events** stay on the SDK's Event Hub and are never sent to the Edge Network. They explain why a Live Update was dropped or was not promoted, and you can inspect them with [Adobe Experience Platform Assurance](https://experienceleague.adobe.com/docs/experience-platform/assurance/home.html).

## Tracking events

| Trigger | `xdm.eventType` | `liveActivity.event` |
| ------- | --------------- | -------------------- |
| `start` push rendered | `liveUpdateTracking.received` | `liveupdate_start` |
| `update` push rendered | `liveUpdateTracking.received` | `liveupdate_update` |
| `end` push rendered | `liveUpdateTracking.received` | `liveupdate_end` |
| First backend push after a [local start](./integration-patterns.md#local-live-updates), dispatched once | `liveUpdateTracking.received` | `liveupdate_localstart` |
| Chip tapped | `liveUpdateTracking.applicationOpened` | Omitted |
| Chip dismissed | `liveUpdateTracking.customAction`, with `pushNotificationTracking.customAction.actionID` = `Dismiss` | Omitted |
| `handleNotificationResponse` called with a custom action id | `liveUpdateTracking.customAction`, with `pushNotificationTracking.customAction.actionID` set to the id | Omitted |
| `trackTopicSubscribed` | `liveUpdateTracking.topic` | `topic_subscribed` |
| `trackTopicUnsubscribed` | `liveUpdateTracking.topic` | `topic_unsubscribed` |

`liveActivity.event` refers to `xdm._experience.customerJourneyManagement.pushChannelContext.liveActivity.event`. It is omitted for taps and dismissals so they are not counted against a lifecycle phase.

A tracking event is dispatched only when the push carried `_xdm`. Topic events also require the payload to have a `topic_name`. A locally started Live Update dispatches nothing until its catch-up event.

### Event format

| Event property | Value |
| -------------- | ----- |
| Name | `Live Update Event Tracking` |
| Type | `com.adobe.eventType.edge` |
| Source | `com.adobe.eventSource.requestContent` |

The XDM is built as follows:

1. Sets `eventType`.
2. Sets `pushNotificationTracking.pushProvider` to `fcm` and `pushProviderMessageID` to the `notification_id`. For custom actions and dismissals, also sets `customAction.actionID`.
3. Merges the push's `_xdm` into the XDM root. If `_xdm` contains a `mixins` object (or `cjm`), its contents are merged instead.
4. In `_experience.customerJourneyManagement`, sets `messageProfile.channel._id` to `https://ns.adobe.com/xdm/channels/liveactivity` unless `_xdm` already provides a channel, and sets `pushChannelContext` to the platform (`fcm`) and the `liveActivity` details.

If configuration has a `messaging.eventDataset` value, it is added as `meta.collect.datasetId`, so the events go to the same AJO dataset as Messaging's push tracking events.

#### Example: start push

```json
{
  "xdm": {
    "eventType": "liveUpdateTracking.received",
    "pushNotificationTracking": {
      "pushProvider": "fcm",
      "pushProviderMessageID": "flight_DL241_2026_10_01"
    },
    "_experience": {
      "customerJourneyManagement": {
        "messageExecution": {
          "messageExecutionID": "...",
          "campaignID": "..."
        },
        "messageProfile": {
          "channel": { "_id": "https://ns.adobe.com/xdm/channels/liveactivity" }
        },
        "pushChannelContext": {
          "platform": "fcm",
          "liveActivity": {
            "liveActivityID": "flight_DL241_2026_10_01",
            "channelID": "flight_DL241",
            "event": "liveupdate_start"
          }
        }
      }
    }
  },
  "meta": {
    "collect": { "datasetId": "<messaging.eventDataset>" }
  }
}
```

`liveActivity.channelID` is the payload's `topic_name`, or an empty string when there is none.

#### Example: dismissal

```json
{
  "xdm": {
    "eventType": "liveUpdateTracking.customAction",
    "pushNotificationTracking": {
      "pushProvider": "fcm",
      "pushProviderMessageID": "flight_DL241_2026_10_01",
      "customAction": { "actionID": "Dismiss" }
    },
    "_experience": {
      "customerJourneyManagement": {
        "messageExecution": { "...": "..." },
        "messageProfile": {
          "channel": { "_id": "https://ns.adobe.com/xdm/channels/liveactivity" }
        },
        "pushChannelContext": {
          "platform": "fcm",
          "liveActivity": {
            "liveActivityID": "flight_DL241_2026_10_01",
            "channelID": "flight_DL241"
          }
        }
      }
    }
  },
  "meta": {
    "collect": { "datasetId": "<messaging.eventDataset>" }
  }
}
```

## Diagnostic events

The Live Updates extension dispatches diagnostic events with the same XDM structure as tracking events, but without the dataset `meta`. The category is in `xdm.eventType`, and the subcategory is in `pushChannelContext.liveActivity.event`.

| Event property | Render error | Incompatible |
| -------------- | ------------ | ------------ |
| Name | `Live Update Render Error` | `Live Update Incompatible` |
| Type | `com.adobe.eventType.messaging` | `com.adobe.eventType.messaging` |
| Source | `com.adobe.eventSource.errorResponseContent` | `com.adobe.eventSource.errorResponseContent` |
| `xdm.eventType` | `liveUpdateTracking.renderError` | `liveUpdateTracking.incompatible` |

### Render errors

| Subcategory | Cause | Outcome |
| ----------- | ----- | ------- |
| `app_discarded` | The `ILiveUpdateInterceptor` returned `false`. | Dropped |
| `invalid_event_type` | `event_type` is not `start`, `update`, `end`, or `localstart`. | Dropped |
| `invalid_timestamp` | `timestamp` is more than 28 days old. | Dropped |
| `outdated_timestamp` | `timestamp` is not newer than the last accepted one for the same `notification_id` and `notification_channel_id`. | Dropped |
| `style_null` | The `ILiveUpdateStyleProvider` returned `null`. | Posted without a style |
| `notification_permission_missing` | Notifications are disabled for the app, for example because `POST_NOTIFICATIONS` was denied. | Posted, but not shown by the system |
| `no_plugin` | Dispatched by the Messaging extension when a Live Update push arrives and no `ILiveupdatePlugin` is registered. | Dropped |

The `no_plugin` event comes from the Messaging extension and has a different data format: `{ "category": "liveUpdateTracking.renderError", "subcategory": "no_plugin", "xdm": <the push's _xdm> }`.

### Incompatible

The notification is posted as a regular ongoing notification instead of a status-bar chip. Only the first unmet requirement is reported, in this order:

| Subcategory | Cause |
| ----------- | ----- |
| `device_api_below_36` | The device runs a version earlier than Android 16 (API 36). |
| `not_promotable` | `Notification.hasPromotableCharacteristics()` returned `false`. For example, the style is not eligible for promotion or the payload has no `title`. |
| `notification_manager_unavailable` | The `NotificationManager` system service is unavailable. |
| `channel_not_registered` | The notification channel does not exist. |
| `channel_importance_low` | The channel's importance is lower than `IMPORTANCE_HIGH`. |
| `promotion_not_permitted` | `NotificationManager.canPostPromotedNotifications()` returned `false`, for example because the user turned off Live Updates for the app. |
