# Architecture — Approach 1: `LiveUpdateHandler` in Messaging

## Constraints

| Constraint | Why |
|---|---|
| Core and Messaging MUST NOT depend on Live Updates SDK | Live Updates uses commons 4.0.0 (compileSdk 36). Forcing Core/Messaging onto that toolchain would propagate to every AEP consumer. |
| Messaging MUST NOT *reference* (compile against) any API-36 type (`NotificationCompat.ProgressStyle`, `setRequestPromotedOngoing`) | Messaging compiles at compileSdk 34. Its bytecode never names these types. They are applied to the builder later, inside the Live Updates SDK. |
| `NotificationCompat.Builder` MAY cross the boundary as an object | At runtime only one `core-ktx` is on the classpath (1.17 wins). The `Builder` class is the same instance type for both SDKs. |
| Live Updates SDK MAY depend on Messaging and Core | One-directional AAR coexistence already validated. Lets the interface accept `MessagingPushPayload` directly. |
| Live Updates SDK uses package `com.adobe.marketing.mobile.messaging.liveupdate` | Future merge into Messaging changes no app-side imports. |

## Division of responsibilities

| Responsibility | Owner |
|---|---|
| Detect "is this a Live Update push?" | Messaging (`MessagingPushPayload.isLiveUpdate`) |
| Build the base `NotificationCompat.Builder` (title, body, small icon, click intent, delete intent, action buttons, channel, sound, visibility) | Messaging (`MessagingPushBuilder` — refactored to expose a builder-returning entry point) |
| Provide the `NotificationCompat.ProgressStyle` for the template | **App** (via `LiveUpdateStyleProvider`) |
| Apply `setStyle(progressStyle)`, `setRequestPromotedOngoing(true)`, `setOngoing(true)` | Live Updates SDK |
| Set the notification ID from `payload.liveUpdateId.hashCode()` | Live Updates SDK |
| Call `NotificationManagerCompat.notify(...)` | Live Updates SDK |

The app's surface is intentionally narrow — one method, returns one object — so all other notification behavior continues to come from Messaging.

## Flow

```
       ┌──────────────────┐
       │   FCM Push       │
       └────────┬─────────┘
                ▼
   ┌──────────────────────────────┐
   │  MessagingService            │   (app-registered in manifest)
   │  .onMessageReceived          │
   └────────┬─────────────────────┘
            ▼
   ┌──────────────────────────────┐
   │  isAJONotification?          │──no──► drop
   └────────┬─────────────────────┘
            ▼ yes
   ┌──────────────────────────────┐
   │  payload = new               │
   │   MessagingPushPayload(...)  │
   └────────┬─────────────────────┘
            ▼
   ┌──────────────────────────────┐
   │  builder =                   │
   │   MessagingPushBuilder       │
   │   .buildBuilder(payload, ctx)│   (NEW — refactored from existing build())
   │   (title/body/icon/intents/  │
   │    channel/sound/buttons)    │
   └────────┬─────────────────────┘
            ▼
   ┌──────────────────────────────┐
   │  isLiveUpdate(payload)?      │──no──┐
   └────────┬─────────────────────┘      │
            ▼ yes                        │
   ┌──────────────────────────────┐      │
   │ Messaging                    │      │
   │  .getLiveUpdateHandler()     │      │
   └────────┬─────────────────────┘      │
            │ null ──────────────────────┤
            ▼ non-null                   │
   ┌──────────────────────────────┐      │
   │ LiveUpdateHandler            │      │
   │  .handleLiveUpdatePush(      │      │
   │     ctx, builder, payload)   │      │
   │ [Live Updates SDK]           │      │
   └────────┬─────────────────────┘      │
            ▼                            │
   ┌──────────────────────────────┐      │
   │ LiveUpdateStyleProvider      │      │
   │  .provideProgressStyle(      │      │
   │     templateType, payload)   │      │
   │ [APP-SUPPLIED — API 36 OK]   │      │
   │ returns NotificationCompat   │      │
   │  .ProgressStyle              │      │
   └────────┬─────────────────────┘      │
            ▼                            │
   ┌──────────────────────────────┐      ▼
   │ Live Updates SDK applies:    │  ┌───────────────────────────┐
   │  builder.setStyle(           │  │ builder.build()           │
   │      progressStyle)          │  │ NotificationManagerCompat │
   │  builder.setRequestPromoted  │  │   .notify(messageId.hash, │
   │      Ongoing(true)           │  │     notification)         │
   │  builder.setOngoing(true)    │  │ (existing path)           │
   │ NotificationManagerCompat    │  └───────────────────────────┘
   │  .notify(                    │
   │     liveUpdateId.hashCode(), │
   │     builder.build())         │
   └──────────────────────────────┘
```

## New types

### Messaging — `com.adobe.marketing.mobile.LiveUpdateHandler`

```java
package com.adobe.marketing.mobile;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

/**
 * Receives push payloads that Messaging has classified as Live Updates, along with
 * the already-populated {@link NotificationCompat.Builder} Messaging would otherwise
 * post directly. Implemented by the Live Updates SDK; registered with Messaging via
 * {@link Messaging#setLiveUpdateHandler(LiveUpdateHandler)}.
 *
 * Lives in Messaging (not Core) so the interface can reference {@link MessagingPushPayload}
 * directly without forcing Core to depend on Messaging types.
 */
public interface LiveUpdateHandler {
    void handleLiveUpdatePush(
            @NonNull Context context,
            @NonNull NotificationCompat.Builder builder,
            @NonNull MessagingPushPayload payload);
}
```

### Messaging — `Messaging` facade additions

```java
public static void setLiveUpdateHandler(@Nullable final LiveUpdateHandler handler) {
    LiveUpdateHandlerStore.INSTANCE.setHandler(handler);
}

public static @Nullable LiveUpdateHandler getLiveUpdateHandler() {
    return LiveUpdateHandlerStore.INSTANCE.getHandler();
}
```

### Messaging — `internal/LiveUpdateHandlerStore.kt`

```kotlin
package com.adobe.marketing.mobile.messaging.internal

import com.adobe.marketing.mobile.LiveUpdateHandler

internal object LiveUpdateHandlerStore {
    @Volatile
    private var handler: LiveUpdateHandler? = null

    fun setHandler(h: LiveUpdateHandler?) { handler = h }
    fun getHandler(): LiveUpdateHandler? = handler
}
```

### Messaging — `MessagingPushPayload` additions

```java
/** Read-only access to the template type, if any. Returns null when absent. */
public @Nullable String getTemplateType() {
    return data == null ? null : data.get(MessagingConstants.Push.PayloadKeys.TEMPLATE_TYPE);
}

/**
 * Stable identifier for this Live Update, mirroring iOS's Live Activity ID. Used by the
 * Live Updates SDK as the Android notification ID — repeating pushes with the same value
 * update the same chip rather than spawning new ones. Returns null when absent.
 */
public @Nullable String getLiveUpdateId() {
    return data == null ? null : data.get(MessagingConstants.Push.PayloadKeys.LIVE_UPDATE_ID);
}

/**
 * Returns true when the payload represents a Live Update. Phase 1: returns true unconditionally
 * (no production payloads ship this flag yet). Phase 2: returns true iff
 * templateType == "live_update".
 */
public static boolean isLiveUpdate(@NonNull final MessagingPushPayload payload) {
    return true; // Phase 1 stub — to be tightened.
}
```

New constants in `MessagingConstants.Push.PayloadKeys`:

```java
public static final String TEMPLATE_TYPE   = "adb_template_type";
public static final String LIVE_UPDATE_ID  = "adb_live_update_id";
```

### Messaging — `MessagingPushBuilder` refactor

Split today's `build(payload, context): Notification` into two:

```java
/** NEW: returns the populated builder without calling build() on it. */
@NonNull static NotificationCompat.Builder buildBuilder(
        final MessagingPushPayload payload, final Context context) {
    final String channelId = createChannelAndGetChannelID(payload, context);
    final NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId);
    builder.setContentTitle(payload.getTitle());
    builder.setContentText(payload.getBody());
    builder.setNumber(payload.getBadgeCount());
    builder.setPriority(payload.getNotificationPriority());
    builder.setAutoCancel(true);
    setSmallIcon(builder, payload, context);
    setVisibility(builder, payload);
    addActionButtons(builder, payload, context);
    setSound(builder, payload, context);
    setNotificationClickAction(builder, payload, context);
    setNotificationDeleteAction(builder, payload, context);
    return builder;
}

/** Existing path — keeps the BigPictureStyle / GIF handling. */
@NonNull static Notification build(final MessagingPushPayload payload, final Context context) {
    return buildNotification(buildBuilder(payload, context), payload);
}
```

The Live Updates SDK calls `buildBuilder(...)`. The existing non-Live-Update path keeps calling `build(...)`.

### Messaging — `MessagingService.handleRemoteMessage` patch

```diff
   if (!isAJONotification(remoteMessage)) { return false; }

   final MessagingPushPayload payload = new MessagingPushPayload(remoteMessage);

+  if (MessagingPushPayload.isLiveUpdate(payload)) {
+      LiveUpdateHandler handler = Messaging.getLiveUpdateHandler();
+      if (handler != null) {
+          NotificationCompat.Builder builder = MessagingPushBuilder.buildBuilder(payload, context);
+          handler.handleLiveUpdatePush(context, builder, payload);
+          PushCallbackHandler.notifyReceived(payload);
+          // dispatch Push notification displayed event (same as existing path)
+          ...
+          return true;
+      }
+      // No handler registered — fall through to default path.
+  }

   final Notification notification = MessagingPushBuilder.build(payload, context);
   ...
```

### Live Updates SDK — `LiveUpdates` facade

```kotlin
package com.adobe.marketing.mobile.messaging.liveupdate

object LiveUpdates {

    @Volatile private var styleProvider: LiveUpdateStyleProvider? = null

    /** Call once at app startup. Registers the SDK's handler with Messaging. */
    fun setApplication(application: Application) {
        Messaging.setLiveUpdateHandler(DefaultLiveUpdateHandler())
    }

    /** App registers its ProgressStyle factory here. Required for any live update to render. */
    fun registerStyleProvider(provider: LiveUpdateStyleProvider) {
        styleProvider = provider
    }

    internal fun styleProvider(): LiveUpdateStyleProvider? = styleProvider
}
```

### Live Updates SDK — `LiveUpdateStyleProvider` (narrow surface)

```kotlin
package com.adobe.marketing.mobile.messaging.liveupdate

import androidx.core.app.NotificationCompat
import com.adobe.marketing.mobile.MessagingPushPayload

/**
 * App-side hook. Given the parsed payload, return the ProgressStyle the live update
 * should render with. Returning null skips the live update.
 *
 * The app's responsibility is intentionally narrow: just the ProgressStyle. Everything
 * else (title, body, small icon, click intent, channel, action buttons, sound,
 * visibility) is supplied by Messaging via the builder, which the app never sees.
 */
fun interface LiveUpdateStyleProvider {
    fun provideProgressStyle(
        templateType: String?,
        payload: MessagingPushPayload
    ): NotificationCompat.ProgressStyle?
}
```

### Live Updates SDK — `DefaultLiveUpdateHandler`

```kotlin
package com.adobe.marketing.mobile.messaging.liveupdate

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.adobe.marketing.mobile.LiveUpdateHandler
import com.adobe.marketing.mobile.MessagingPushPayload

internal class DefaultLiveUpdateHandler : LiveUpdateHandler {

    override fun handleLiveUpdatePush(
        context: Context,
        builder: NotificationCompat.Builder,
        payload: MessagingPushPayload
    ) {
        // Live Update ID is mandatory — mirrors iOS Live Activity ID.
        val liveUpdateId = payload.liveUpdateId ?: run {
            Log.warning(TAG, "Dropping Live Update push: adb_live_update_id missing.")
            return
        }

        val provider = LiveUpdates.styleProvider() ?: run {
            Log.warning(TAG, "Dropping Live Update push: no LiveUpdateStyleProvider registered.")
            return
        }

        val progressStyle = provider.provideProgressStyle(payload.templateType, payload) ?: return

        // Messaging already populated title/body/icon/intents/channel/buttons.
        // We only add the Live-Update-specific bits the app cannot know about.
        builder.setStyle(progressStyle)
        builder.setRequestPromotedOngoing(true)
        builder.setOngoing(true)

        NotificationManagerCompat.from(context).notify(liveUpdateId.hashCode(), builder.build())
    }
}
```

### Sample app — wiring

```kotlin
class LiveUpdatesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MobileCore.setApplication(this)
        MobileCore.registerExtensions(listOf(Messaging.EXTENSION, ...)) { /* configure */ }

        LiveUpdates.setApplication(this)
        LiveUpdates.registerStyleProvider { templateType, payload ->
            // App only owns the ProgressStyle — everything else comes from Messaging.
            val progress = payload.data["progress"]?.toIntOrNull() ?: 0
            NotificationCompat.ProgressStyle()
                .setProgress(progress)
                .setStyledByProgress(true)
        }
    }
}
```

Manifest registration of `MessagingService` is unchanged from today's messaging sample.

## Notification ID

Mirrors iOS Live Activity ID. Source of truth is the `adb_live_update_id` payload key. The Android notification ID is `liveUpdateId.hashCode()` — same string ID always hashes to the same int, so repeating pushes with the same `adb_live_update_id` update the same chip rather than spawning new ones. A push without `adb_live_update_id` is dropped with a warning.

## Phasing

1. **Today (scaffolding):**
   - Messaging: add `LiveUpdateHandler`, store, `Messaging.setLiveUpdateHandler` / `getLiveUpdateHandler`. Add `isLiveUpdate` (always true), `getTemplateType()`, `getLiveUpdateId()`. Refactor `MessagingPushBuilder.build` to expose `buildBuilder`. Patch `MessagingService.handleRemoteMessage` to branch.
   - Live Updates SDK: `LiveUpdates`, `LiveUpdateStyleProvider`, `DefaultLiveUpdateHandler`.
   - Publish Messaging 4.0.0 (on `staging_liveupdate_1`) to `~/.m2`.
   - Testapp depends on Messaging from mavenLocal.

2. **Phase 2:**
   - Tighten `isLiveUpdate` to look at `adb_template_type == "live_update"`.
   - Add live-update lifecycle telemetry to Edge from the Live Updates SDK.

3. **Future merge:**
   - When Live Updates ships inside Messaging, the source files move from this repo into Messaging's source tree under the same package. The `Messaging.setLiveUpdateHandler` registration may become an internal hook or disappear entirely (Messaging calls its internal Live Update code directly). App imports do not change.

## Open questions

1. **What happens when no `LiveUpdateStyleProvider` is registered** — currently silently drops with a warning. Should we fall back to posting the non-promoted notification via the existing Messaging path instead? That's a graceful degradation.
2. **Process death** — if the app is killed between updates, the Live Update notification persists but no further updates land until the app re-registers. Acceptable for v1, but documents the limitation.
