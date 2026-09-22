/*
  Copyright 2026 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
*/

package com.adobe.marketing.mobile.messaging.liveupdate

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdatePayload.Companion.EVENT_TYPE_END
import com.adobe.marketing.mobile.plugin.ILiveupdatePlugin
import com.adobe.marketing.mobile.services.Log
import com.google.firebase.messaging.RemoteMessage

/**
 * Canonical [ILiveupdatePlugin] implementation. Parses the [RemoteMessage] into a
 * [LiveUpdatePayload], asks the app-supplied [ILiveUpdateStyleProvider] for a style, builds
 * a fresh [NotificationCompat.Builder] from envelope fields only (no [MessagingPushBuilder]
 * reuse), and posts the resulting chip.
 *
 * Wire at app startup:
 * ```
 * MobileCore.addPlugins(LiveUpdatePlugin(MyStyleProvider()))
 * ```
 *
 * Drops the push (warning log, no notification posted) in these cases:
 *  - payload fails to parse (any required field missing: notification_id, notification_channel_id, event_type, title)
 *  - style provider returns `null`
 *
 * After a successful `notify(...)`, dispatches the Live Update event tracking
 * (`MobileCore.dispatchEvent`) so Edge forwards it to AJO, and invokes the registered
 * [ILiveUpdateListener] (if any) - both via [LiveUpdates] helpers.
 *
 * On successful post, logs a warning if the notification will degrade to a non-promoted
 * ongoing (channel importance too low, device API &lt; 36, app lacks promotion permission, or
 * style is not promotion-eligible). The notification still posts in that case — a non-promoted
 * ongoing notification is a degradation, not a failure.
 */
class LiveUpdatePlugin(
    private val styleProvider: ILiveUpdateStyleProvider
) : ILiveupdatePlugin {

    override fun handleLiveUpdatePush(context: Context, message: Any) {
        // The push object arrives as Any (Core stays free of Firebase types); cast it back here.
        val remoteMessage = message as? RemoteMessage
        if (remoteMessage == null) {
            Log.warning(
                LiveUpdatesConstants.LOG_TAG,
                TAG,
                "Dropping Live Update: expected a Firebase RemoteMessage but received " +
                    "${message.javaClass.name}."
            )
            return
        }

        // Log the raw envelope up front so any payload/parsing issue is diagnosable from logs
        // even when parse() returns null without surfacing the specific failing field.
        val rawEnvelope = remoteMessage.data[LiveUpdatesConstants.LIVE_UPDATE_DATA_KEY]
        Log.debug(
            LiveUpdatesConstants.LOG_TAG,
            TAG,
            "handleLiveUpdatePush received. dataKeys=${remoteMessage.data.keys}; " +
                "adb_liveupdate_data=$rawEnvelope"
        )

        val payload = LiveUpdatePayload.parse(remoteMessage)
        if (payload == null) {
            Log.warning(
                LiveUpdatesConstants.LOG_TAG,
                TAG,
                "Dropping Live Update: failed to parse payload. Raw adb_liveupdate_data=$rawEnvelope"
            )
            return
        }
        // Consult the app-registered interceptor before any rendering / tracking / listener
        // dispatch. A `false` verdict drops the Live Update entirely.
        if (!LiveUpdates.shouldDisplay(payload)) {
            Log.debug(
                LiveUpdatesConstants.LOG_TAG, TAG,
                "Live Update id=${payload.notificationId} vetoed by ILiveUpdateInterceptor; dropping."
            )
            return
        }
        postLiveUpdate(context, payload)
    }

    /**
     * Renders and posts the chip for [payload], then dispatches the receive lifecycle
     * tracking event and invokes any registered [ILiveUpdateListener]. Shared between the
     * FCM-received flow ([handleLiveUpdatePush]) and the app-triggered local flow
     * ([LiveUpdates.triggerLocalLiveUpdate]).
     */
    internal fun postLiveUpdate(context: Context, payload: LiveUpdatePayload) {
        if (!NotificationHistoryManager.recordAndValidate(payload)) {
            Log.warning(
                LiveUpdatesConstants.LOG_TAG,
                TAG,
                "Dropping Live Update id=${payload.notificationId}: rejected by " +
                    "NotificationHistoryManager (see prior warning for reason)."
            )
            return
        }
        val style = styleProvider.provideStyle(payload)
        // basic
        if (style == null) {
            Log.warning(
                LiveUpdatesConstants.LOG_TAG,
                TAG,
                "Dropping Live Update id=${payload.notificationId}: style provider returned null."
            )
            return
        }

        // Ensure the NotificationChannel exists. On API 26+ Android silently drops
        // notifications targeting an unregistered channel id, so the SDK auto-creates
        // with IMPORTANCE_HIGH (chip promotion requirement) if the app hasn't already
        // registered the channel. If the channel exists, we leave it alone - the app's
        // pre-existing configuration wins.
        ensureChannelExists(context, payload.channelId)

        val builder = NotificationCompat.Builder(context, payload.channelId)
            .setSmallIcon(resolveSmallIcon(context, payload.smallIcon))
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(style)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .setPriority(mapPriority(payload.priority))
            .setContentIntent(buildTapPendingIntent(context, payload))
            .setDeleteIntent(buildDismissPendingIntent(context, payload))
        payload.criticalText?.let { builder.setShortCriticalText(it) }
        payload.whenMillis?.let { builder.setWhen(it).setShowWhen(true) }

        // Apply auto-dismiss only for the terminal `end` push, and only when dismiss_after is
        // present and positive. The chip persists through start/update pushes and then times
        // out the server-specified number of seconds after the end push is received.
        if (payload.eventType == EVENT_TYPE_END) {
            payload.dismissAfterSeconds?.takeIf { it > 0L }?.let {
                builder.setTimeoutAfter(it * 1000L)
            }
        }

        val notification = builder.build()

        checkPromotionEligibility(context, notification)?.let { reason ->
            Log.warning(
                LiveUpdatesConstants.LOG_TAG,
                TAG,
                "Live Update will post as a NORMAL ongoing notification (not promoted to chip). Reason: $reason"
            )
        }

        NotificationManagerCompat.from(context)
            .notify(payload.notificationId.hashCode(), notification)

        // Live Update event tracking dispatch + listener invocation. Both no-op gracefully
        // if event_type is non-canonical (logged inside the helpers); listener can be null.
        LiveUpdates.dispatchLiveUpdateEventTracking(context, payload)
        LiveUpdates.invokeListener(payload)
    }

    /**
     * Builds the content-intent PendingIntent for the chip body tap. Routes through the
     * SDK's [LiveUpdateTrackerActivity] so tracking fires before the destination launches.
     */
    private fun buildTapPendingIntent(
        context: Context,
        payload: LiveUpdatePayload
    ): PendingIntent {
        val tapIntent = Intent(context, LiveUpdateTrackerActivity::class.java).apply {
            addTrackingExtras(payload)
            // Serialize the full payload so onClick can re-hydrate it, even if the app
            // process was killed between post and tap (only the intent extras survive).
            putExtra(LiveUpdates.EXTRA_PAYLOAD, payload.toEnvelopeJson())
        }
        return PendingIntent.getActivity(
            context,
            payload.notificationId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Builds the delete-intent PendingIntent for chip dismissal. Broadcasts to
     * [LiveUpdateInteractionReceiver] which fires `customActionId="Dismiss"` tracking.
     */
    private fun buildDismissPendingIntent(
        context: Context,
        payload: LiveUpdatePayload
    ): PendingIntent {
        val dismissIntent = Intent(context, LiveUpdateInteractionReceiver::class.java).apply {
            action = LiveUpdateInteractionReceiver.ACTION_DISMISS
            addTrackingExtras(payload)
            // Serialize the full payload so onDismissed can re-hydrate it, even if the app
            // process was killed between post and dismiss (only the intent extras survive).
            putExtra(LiveUpdates.EXTRA_PAYLOAD, payload.toEnvelopeJson())
        }
        return PendingIntent.getBroadcast(
            context,
            payload.notificationId.hashCode() + DISMISS_REQUEST_CODE_OFFSET,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Adds Live Update tracking extras to [this] intent so the tracker Activity or dismiss
     * receiver can reconstruct enough context for the outbound XDM without a second parse
     * of the FCM message.
     */
    private fun Intent.addTrackingExtras(payload: LiveUpdatePayload) {
        putExtra(LiveUpdates.EXTRA_NOTIFICATION_ID, payload.notificationId)
        putExtra(LiveUpdates.EXTRA_EVENT_TYPE, payload.eventType)
        payload.topicName?.let { putExtra(LiveUpdates.EXTRA_CHANNEL_ID, it) }
        payload.xdm?.let { putExtra(LiveUpdates.EXTRA_XDM, it.toString()) }
    }

    /**
     * Resolves the small icon resource id using a three-step fallback:
     *  1. If the envelope carried a `small_icon` name and it resolves to a drawable in the
     *     host app's resources, use it.
     *  2. Otherwise, use the icon configured globally via
     *     [MobileCore.setSmallIconResourceID].
     *  3. Otherwise, use the app's launcher icon.
     *
     * A small icon is mandatory or `notify()` throws.
     */
    private fun resolveSmallIcon(context: Context, payloadIconName: String?): Int {
        if (!payloadIconName.isNullOrEmpty()) {
            val resolved = context.resources.getIdentifier(
                payloadIconName,
                "drawable",
                context.packageName
            )
            if (resolved > 0) return resolved
        }
        val configured = MobileCore.getSmallIconResourceID()
        if (configured > 0) return configured
        return context.applicationInfo.icon
    }

    /**
     * Mirrors Messaging's `createChannelAndGetChannelID` pattern: if the channel id from the
     * envelope is not yet registered on the device, create it with `IMPORTANCE_HIGH` (the
     * chip-promotion requirement) and a default name. If the channel already exists, leave
     * it alone - the app's pre-existing configuration wins (importance, sound, vibration).
     * No-op on API < 26 (channels were introduced in O).
     */
    private fun ensureChannelExists(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(channelId) != null) return
        nm.createNotificationChannel(
            NotificationChannel(channelId, DEFAULT_CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
                .apply { description = DEFAULT_CHANNEL_DESCRIPTION }
        )
    }

    /**
     * Maps the envelope's string priority (e.g. `"PRIORITY_HIGH"`) to the
     * `NotificationCompat.PRIORITY_*` int. Defaults to `PRIORITY_DEFAULT` for unknown/null.
     */
    private fun mapPriority(priority: String?): Int = when (priority) {
        "PRIORITY_MAX" -> NotificationCompat.PRIORITY_MAX
        "PRIORITY_HIGH" -> NotificationCompat.PRIORITY_HIGH
        "PRIORITY_LOW" -> NotificationCompat.PRIORITY_LOW
        "PRIORITY_MIN" -> NotificationCompat.PRIORITY_MIN
        else -> NotificationCompat.PRIORITY_DEFAULT
    }

    /**
     * Returns null when [notification] meets all structural and runtime preconditions for
     * promotion. Otherwise returns a human-readable reason naming the failing precondition.
     */
    private fun checkPromotionEligibility(
        context: Context,
        notification: Notification
    ): String? {
        if (Build.VERSION.SDK_INT < 36) {
            return "device API ${Build.VERSION.SDK_INT} < 36 — Live Update promotion requires API 36+"
        }

        if (!notification.hasPromotableCharacteristics()) {
            return "Notification.hasPromotableCharacteristics() = false " +
                "(likely cause: style is not promotion-eligible, or small icon missing)"
        }

        val nm = context.getSystemService(NotificationManager::class.java)
            ?: return "NotificationManager service unavailable"

        val channel = nm.getNotificationChannel(notification.channelId)
            ?: return "channel '${notification.channelId}' is not registered"

        if (channel.importance < NotificationManager.IMPORTANCE_HIGH) {
            return "channel '${notification.channelId}' has importance=${channel.importance}, " +
                "requires IMPORTANCE_HIGH (${NotificationManager.IMPORTANCE_HIGH})"
        }

        if (!nm.canPostPromotedNotifications()) {
            return "NotificationManager.canPostPromotedNotifications() = false " +
                "(app or device not currently permitted to post promoted notifications)"
        }

        return null
    }

    private companion object {
        const val TAG = "LiveUpdatePlugin"
        const val DEFAULT_CHANNEL_NAME = "Live Updates"
        const val DEFAULT_CHANNEL_DESCRIPTION = "Status-bar chips for AJO Live Updates"

        // Keeps dismiss PendingIntent's request code distinct from tap's so
        // PendingIntent.FLAG_UPDATE_CURRENT does not collapse them.
        const val DISMISS_REQUEST_CODE_OFFSET = 1
    }
}
