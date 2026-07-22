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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.messaging.MessagingService
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdatePayload
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdates
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Sample [FirebaseMessagingService] for apps that own their own FCM entry point instead of
 * relying on the Messaging SDK's built-in service. The three integration patterns supported
 * by the Live Updates SDK map onto this class as follows:
 *
 *  1. **Auto** - Register `com.adobe.marketing.mobile.messaging.MessagingService` in
 *     `AndroidManifest.xml` and do NOT register this class. The Messaging SDK owns FCM
 *     and dispatches Live Updates to the registered `ILiveUpdateHandler`.
 *
 *  2. **Mixed** (this class with [FULL_MANUAL_MODE] `= false`, the default) - Register this
 *     service in place of Messaging's default. `MessagingService.handleRemoteMessage` still
 *     routes AEP pushes - both Live Updates and standard AJO push - to the SDK. Add
 *     app-specific handling for non-AEP pushes below the `if (...) return` line.
 *
 *  3. **Manual** (this class with [FULL_MANUAL_MODE] `= true`) - Register this service and
 *     do NOT call `Messaging.setLiveUpdateHandler` at startup. Live Update pushes are
 *     parsed and rendered by [renderLiveUpdateManually] below. The service still calls
 *     [LiveUpdates.trackLiveUpdateEvent] so the receive lifecycle event flows to Edge with
 *     the correct XDM shape, and it uses [LiveUpdates.addPushTrackingDetails] on its own
 *     PendingIntents so tap / action / dismiss tracking works exactly like auto mode.
 *
 * This class is not registered in the sample app's `AndroidManifest.xml`; the sample runs
 * pattern 1 (auto) by default. Copy this file into your own project and update the
 * manifest to switch to pattern 2 or pattern 3.
 */
class SamplePushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        MobileCore.setPushIdentifier(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        if (FULL_MANUAL_MODE && LiveUpdatePayload.isLiveUpdate(message)) {
            val payload = LiveUpdatePayload.parse(message) ?: return
            renderLiveUpdateManually(this, payload, message)
            // Dispatches the receive lifecycle event (start/update/end) and invokes any
            // registered ILiveUpdateListener. Interaction tracking (tap / action / dismiss)
            // is wired via the PendingIntents built in renderLiveUpdateManually below.
            LiveUpdates.trackLiveUpdateEvent(this, message)
            return
        }

        // Mixed-mode path. Returns true if this was an AEP push (Live Update or standard
        // AJO push) and has been handled by the Messaging SDK.
        if (MessagingService.handleRemoteMessage(this, message)) return

        // Non-AEP pushes: implement app-specific handling below.
    }

    // ==============================================================================
    // Full manual mode - shown as a reference for apps that need complete control
    // over the notification chrome (icons, actions, styles, PendingIntents).
    // ==============================================================================

    /**
     * Builds and posts a fully custom [android.app.Notification] for a Live Update payload.
     * Interaction tracking is wired via [LiveUpdates.addPushTrackingDetails], so
     * [LiveUpdates.handleNotificationResponse] fires with the same XDM shape auto mode
     * produces when the app's target activity handles the intent.
     */
    private fun renderLiveUpdateManually(
        context: Context,
        payload: LiveUpdatePayload,
        message: RemoteMessage
    ) {
        ensureChannelExists(context, payload.channelId)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            LiveUpdates.addPushTrackingDetails(this, message)
        }
        val tapPi = PendingIntent.getActivity(
            context,
            payload.notificationId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, payload.channelId)
            .setSmallIcon(resolveSmallIcon(context))
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setOngoing(true)
            .setContentIntent(tapPi)
        payload.dismissAfterSeconds?.takeIf { it > 0L }?.let {
            builder.setTimeoutAfter(it * 1000L)
        }

        NotificationManagerCompat.from(context)
            .notify(payload.notificationId.hashCode(), builder.build())
    }

    private fun ensureChannelExists(context: Context, channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(channelId) != null) return
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Live Updates", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun resolveSmallIcon(context: Context): Int {
        val configured = MobileCore.getSmallIconResourceID()
        return if (configured > 0) configured else context.applicationInfo.icon
    }

    companion object {
        /**
         * Toggle full manual mode. When `true`, this service parses the Live Update
         * payload and builds the notification itself; when `false` (default), it hands
         * off to `MessagingService.handleRemoteMessage`, which routes to the registered
         * `ILiveUpdateHandler`.
         */
        const val FULL_MANUAL_MODE: Boolean = false
    }
}
