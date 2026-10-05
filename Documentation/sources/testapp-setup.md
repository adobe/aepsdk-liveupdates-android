# Test app setup

The test app is in `code/testapp` (application id `com.adobe.marketing.mobile.liveupdatessample`). It builds the Live Updates plugin from the `:liveupdates` module source, and uses the published Mobile Core library and the Messaging, Edge Network, Edge Identity, Lifecycle, and Assurance extensions.

## Configure the app

1. **Firebase**: Register the application id in your Firebase project, then replace the template at `code/testapp/google-services.json` with your project's file. For details, see the [Firebase documentation](https://firebase.google.com/docs/cloud-messaging/android/client#add_a_firebase_configuration_file).
2. **Data Collection**: Open `code/testapp/src/main/java/com/adobe/marketing/mobile/liveupdatessample/LiveUpdatesApplication.kt` and set your mobile property:
   - Set `ENVIRONMENT_FILE_ID` and set `STAGING` to `false` to use a production property.
   - `STAGING` defaults to `true`, which uses `STAGING_APP_ID` and sets `edge.environment` to `int`.
   - The property must have the Messaging and Edge Network extensions configured, including the `messaging.eventDataset` dataset.
3. **Assurance** (optional): Set `ASSURANCE_SESSION_URL`, use the **Connect Assurance (Quick Connect)** button, or open a session deep link with the `lutest://` scheme.

## Run the app

1. Open `code/build.gradle.kts` in Android Studio.
2. Select the `testapp` run configuration and a device or emulator running Android 16 (API 36) or newer. Older devices show Live Updates as regular ongoing notifications.
3. Allow notifications when prompted.

The main screen provides:

| Control | Description |
| ------- | ----------- |
| FCM token and **Copy token** | The device's registration token, for sending test pushes. |
| **Sync push identifier** | Sends the FCM token to Adobe with `MobileCore.setPushIdentifier`. |
| **Manage FCM topics** | Subscribe to, unsubscribe from, and list FCM topics. Listing requires an OAuth2 access token. |
| **Start a local Live Update** | Calls `LiveUpdates.triggerLocalLiveUpdate` with a `localstart` payload (`notification_id` = `local_live_update_sample`, channel `live_updates_channel`). |
| **Connect Assurance (Quick Connect)** | Starts an Assurance session. |
| Synced identities | The ECID and other identities, for finding the profile in AJO. |

## What the app demonstrates

- **Registration**: `LiveUpdatesApplication` registers the Messaging, Edge Identity, Lifecycle, Edge Network, and Assurance extensions with `MobileCore.registerExtensions`, and registers the Live Updates plugin separately with `MobileCore.addPlugins(LiveUpdatePlugin(...))`.
- **Mixed handling**: `SamplePushService` is registered in the manifest and passes messages to `MessagingService.handleRemoteMessage`. Set `FULL_MANUAL_MODE` to `true` to try [manual handling](./integration-patterns.md#manual-handling). To try automatic handling, replace the service in `AndroidManifest.xml` with `com.adobe.marketing.mobile.messaging.MessagingService`.
- **Style provider**: `SampleLiveUpdateStyleProvider` chooses a style from `content_state.custom_key_template_type`:

  | `custom_key_template_type` | Style | Other `content_state` keys |
  | -------------------------- | ----- | -------------------------- |
  | `progress` | `NotificationCompat.ProgressStyle` with colored segments and a tracker icon | `custom_key_journey_progress` (0-100) |
  | `metric` | `Notification.MetricStyle` on API 37+, through `MetricStyleCompat`. `ProgressStyle` on earlier versions. | `custom_key_home_team`, `custom_key_away_team`, `custom_key_home_score`, `custom_key_away_score`, `custom_key_match_time` |
  | `big_text` | `NotificationCompat.BigTextStyle` using `body` | None |
  | Any other value | `null`, which posts the notification without a style | None |

- **Listener**: `LiveUpdatesApplication` logs every callback under the tag `LiveUpdateSample`. It subscribes to the payload's `topic_name` on `onStart` and unsubscribes on `onEnd` and `onDismissed`, reporting each change with `trackTopicSubscribed` or `trackTopicUnsubscribed`. On `onClick`, it opens `MainActivity`.
- **Interceptor**: `onDismissed` records dismissed ids in `DismissedLiveUpdateStore`. `SampleLiveUpdateInterceptor` is set up to drop later pushes for those ids, but its check is commented out, so it currently lets every Live Update through.

## Send a test push

Send a Live Update from an AJO campaign or journey, or directly with the [FCM HTTP v1 API](https://firebase.google.com/docs/cloud-messaging/send-message):

```bash
curl -X POST "https://fcm.googleapis.com/v1/projects/<FIREBASE_PROJECT_ID>/messages:send" \
  -H "Authorization: Bearer <OAUTH2_ACCESS_TOKEN>" \
  -H "Content-Type: application/json" \
  -d @message.json
```

Use the message format in the [payload reference](./payload.md#fcm-message-example), and keep these points in mind:

- Set `timestamp` to the current time in epoch seconds (`date +%s`), and increase it for each update. Pushes with the same or an older `timestamp` are dropped.
- Include `_xdm` so the push is recognized as an AJO push and tracking events are dispatched.
- To use the sample style provider, set `content_state.custom_key_template_type`, for example to `progress`.

## Build and test

From the repository root:

```bash
make assemble-phone        # build the Live Updates plugin
make assemble-app          # build the test app
make unit-test             # run unit tests
make unit-test-coverage    # run unit tests with a coverage report
make lint                  # run Spotless and Checkstyle checks
```
