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

package com.adobe.marketing.mobile.liveupdatessample

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.adobe.marketing.mobile.Messaging
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.messaging.MessagingService
import com.adobe.marketing.mobile.messaging.NotificationInteractionReceiver
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdatePayload
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdates
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Reference implementation of the **three integration patterns** for AEP push handling
 * when the app owns its own [FirebaseMessagingService] instead of using the auto-registered
 * [com.adobe.marketing.mobile.messaging.MessagingService].
 *
 * **The three patterns demonstrated:**
 *  - **Pattern 2 (Mixed / Auto-handled)**: `LiveUpdates.handleLiveUpdatePush(...)` parses,
 *    builds, posts, tracks, and fires the listener - everything automatic. The default
 *    branch when `MANUAL_LIVE_UPDATE_MODE` is `false`.
 *  - **Pattern 3 (Manual)**: the app parses the payload itself, builds its own
 *    [NotificationCompat.Builder] from envelope fields, posts via
 *    [NotificationManagerCompat.notify], and explicitly calls
 *    [LiveUpdates.trackLiveUpdateEvent] to fire Live Update event tracking + invoke any
 *    registered [com.adobe.marketing.mobile.messaging.liveupdate.ILiveUpdateListener].
 *    Activated by flipping `MANUAL_LIVE_UPDATE_MODE` to `true`.
 *  - **Standard AJO push fallthrough**: for non-Live-Update AJO pushes,
 *    [MessagingService.handleRemoteMessage] handles everything via the standard pipeline.
 *
 * **Not registered in AndroidManifest.xml by default.** This file exists as a code
 * reference; the test app keeps the Messaging auto-service registered so the
 * existing `fcm.sh` flow keeps working. To activate this service, swap the manifest
 * `<service android:name="com.adobe.marketing.mobile.messaging.MessagingService" .../>`
 * for `<service android:name=".SampleNotificationService" .../>` with the same FCM
 * intent-filter.
 */
class SampleNotificationService : FirebaseMessagingService() {

    /**
     * Flip to `true` to demonstrate Pattern 3 (manual) for Live Updates. Leave `false` to
     * use Pattern 2 (mixed) where the SDK does the rendering. Standard AJO pushes always
     * go through `MessagingService.handleRemoteMessage` regardless of this flag.
     */
    private val MANUAL_LIVE_UPDATE_MODE = false

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // Forward the new FCM token to the AEP SDK so AJO can target this device.
        MobileCore.setPushIdentifier(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // region ===== Live Update routing =====
        if (LiveUpdatePayload.isLiveUpdate(message)) {
            if (MANUAL_LIVE_UPDATE_MODE) {
                handleLiveUpdateManually(message) // Pattern 3
            } else {
                LiveUpdates.handleLiveUpdatePush(this, message) // Pattern 2
            }
            return
        }
        // endregion

        // region ===== Standard AJO push (auto display + tracking) =====
        if (MessagingService.handleRemoteMessage(this, message)) {
            return
        }
        // endregion

        // region ===== Non-AEP push =====
        // App-specific handling for any other push types goes here. Demo has nothing.
        // endregion
    }

    // ============================================================================
    // Pattern 3 (Manual) - parse, build, post, track everything yourself.
    // ============================================================================
    //
    // Layout mirrors the standard-push manual demo in the messaging sample's
    // NotificationService.kt: read fields off the parsed payload, set up tap and
    // dismiss PendingIntents wired through Messaging's tracker activity / receiver
    // so AJO tap + dismiss tracking flows automatically, then call the new
    // LiveUpdates.trackLiveUpdateEvent to fire the Live Update start/update/end
    // event and invoke any registered ILiveUpdateListener.
    private fun handleLiveUpdateManually(message: RemoteMessage) {
        // 1. Parse the envelope. Returns null when any required field is missing
        //    (notification_id, notification_channel_id, event_type, title).
        val payload = LiveUpdatePayload.parse(message)
        if (payload == null) {
            Log.w(TAG, "Live Update payload failed to parse; dropping.")
            return
        }

        // 2. Register the NotificationChannel from the envelope if it doesn't exist yet.
        //    Live Update promotion requires IMPORTANCE_HIGH; channel must exist before notify.
        ensureChannel(payload.channelId, "Live Updates")

        // 3. Build tap and dismiss PendingIntents. Both carry the AJO tracking extras
        //    injected by Messaging.addPushTrackingDetails; the receiving Activity (MainActivity)
        //    calls Messaging.handleNotificationResponse in onCreate / onNewIntent to fire
        //    tap tracking. Dismiss tracking fires automatically through the broadcast receiver.
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            message.messageId?.let { Messaging.addPushTrackingDetails(this, it, message.data) }
        }
        val tapPendingIntent = PendingIntent.getActivity(
            this,
            payload.notificationId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deleteIntent = Intent(applicationContext, NotificationInteractionReceiver::class.java).apply {
            message.messageId?.let { Messaging.addPushTrackingDetails(this, it, message.data) }
        }
        val deletePendingIntent = PendingIntent.getBroadcast(
            this,
            payload.notificationId.hashCode(),
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 4. Build the notification. Everything below is the customer's choice -
        //    the SDK does not impose a builder shape in manual mode.
        val builder = NotificationCompat.Builder(this, payload.channelId)
            .setSmallIcon(resolveSmallIcon())
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setStyle(buildStyleFromPayload(payload))
            .setOngoing(true)
            .setRequestPromotedOngoing(true) // API 36+ chip promotion (no-op below)
            .setPriority(mapPriority(payload.priority))
            .setContentIntent(tapPendingIntent)
            .setDeleteIntent(deletePendingIntent)
            .setAutoCancel(false) // chip is ongoing; do not auto-cancel on tap

        payload.criticalText?.let { builder.setShortCriticalText(it) }
        payload.whenMillis?.let { builder.setWhen(it).setShowWhen(true) }
        payload.dismissAfterSeconds?.takeIf { it > 0L }?.let {
            builder.setTimeoutAfter(it * 1000L)
        }

        // 5. Post the notification under notification_id.hashCode() so subsequent pushes
        //    with the same notification_id update this chip in place.
        NotificationManagerCompat.from(this)
            .notify(payload.notificationId.hashCode(), builder.build())

        // 6. Fire Live Update event tracking + invoke any registered ILiveUpdateListener.
        //    This is the manual-mode equivalent of what LiveUpdateHandlerImpl does
        //    automatically for Patterns 1 and 2 right after notify(). The SDK reads
        //    event_type from the envelope, dispatches the appropriate XDM event through
        //    Edge to AJO, and invokes onStart / onUpdate / onEnd on the listener.
        LiveUpdates.trackLiveUpdateEvent(this, message)
    }

    /**
     * Picks a [NotificationCompat.Style] for the chip based on the parsed envelope. This is
     * customer code in Pattern 3 - the SDK doesn't dictate the style. Here we mirror
     * [SampleLiveUpdateStyleProvider]: read `template_type` from the raw envelope and
     * `custom_key_journey_progress` from `content_state`.
     */
    private fun buildStyleFromPayload(payload: LiveUpdatePayload): NotificationCompat.Style {
        val templateType = payload.rawEnvelope.optString("template_type", "standard")
        val state = payload.contentState
        return when (templateType) {
            "progress" -> {
                val progress = state?.optInt("custom_key_journey_progress", 0) ?: 0
                NotificationCompat.ProgressStyle()
                    .setProgress(progress)
                    .setStyledByProgress(true)
            }
            "big_text" -> NotificationCompat.BigTextStyle().bigText(payload.body)
            else -> NotificationCompat.BigTextStyle().bigText(payload.body)
        }
    }

    private fun ensureChannel(channelId: String, channelName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(channelId) != null) return
        nm.createNotificationChannel(
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "AJO Live Update chips"
            }
        )
    }

    /** Prefer the `MobileCore`-configured icon; fall back to the app's launcher icon. */
    private fun resolveSmallIcon(): Int {
        val configured = MobileCore.getSmallIconResourceID()
        return if (configured > 0) configured else applicationInfo.icon
    }

    /** Maps the envelope's string priority to a `NotificationCompat.PRIORITY_*` int. */
    private fun mapPriority(priority: String?): Int = when (priority) {
        "PRIORITY_MAX" -> NotificationCompat.PRIORITY_MAX
        "PRIORITY_HIGH" -> NotificationCompat.PRIORITY_HIGH
        "PRIORITY_LOW" -> NotificationCompat.PRIORITY_LOW
        "PRIORITY_MIN" -> NotificationCompat.PRIORITY_MIN
        else -> NotificationCompat.PRIORITY_DEFAULT
    }

    private companion object {
        const val TAG = "SampleNotificationService"
    }
}
