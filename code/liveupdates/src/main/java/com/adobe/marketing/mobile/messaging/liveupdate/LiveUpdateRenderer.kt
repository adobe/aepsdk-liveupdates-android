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
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.adobe.marketing.mobile.LiveUpdateEvent
import com.adobe.marketing.mobile.LiveUpdateHandler
import com.adobe.marketing.mobile.MessagingPushPayload
import com.adobe.marketing.mobile.services.Log

/**
 * Canonical [LiveUpdateHandler] implementation. Wraps an app-supplied
 * [LiveUpdateStyleProvider] and posts the Live Update notification.
 *
 * Wire at app startup:
 * ```
 * Messaging.setLiveUpdateHandler(LiveUpdateRenderer(MyStyleProvider()))
 * ```
 *
 * Returns `false` from [handleLiveUpdatePush] (which routes Messaging to its default path)
 * in these cases:
 *  - payload carries no [com.adobe.marketing.mobile.LiveUpdateEnvelope]
 *  - envelope has no id
 *  - style provider returned null
 *
 * On successful post the renderer also logs a warning if the notification will not be
 * promoted to a status-bar chip (channel importance too low, device API < 36, app lacks
 * promotion permission, or style is not promotion-eligible). It still posts in that case —
 * a non-promoted ongoing notification is a degradation, not a failure.
 */
class LiveUpdateRenderer(
    private val styleProvider: LiveUpdateStyleProvider
) : LiveUpdateHandler {

    override fun handleLiveUpdatePush(
        context: Context,
        builder: NotificationCompat.Builder,
        payload: MessagingPushPayload
    ): Boolean {
        val envelope = payload.liveUpdate ?: return false
        val id = envelope.id
        if (id.isEmpty()) {
            Log.warning(LOG_TAG, TAG, "Dropping Live Update: live_update_id is empty.")
            return false
        }

        val style = styleProvider.provideStyle(payload)
        if (style == null) {
            Log.debug(LOG_TAG, TAG, "Style provider returned null — falling back to default Messaging path.")
            return false
        }

        // Style: from the app.
        builder.setStyle(style)

        // Chrome — all parsed by the SDK from the envelope. None of this is "styling".
        builder.setOngoing(true)
        builder.setRequestPromotedOngoing(true)
        envelope.criticalText?.let { builder.setShortCriticalText(it) }

        // End-of-lifecycle auto-dismiss. dismissAfter is a relative duration in seconds
        // (clock-skew-immune by design — no device-clock comparison anywhere).
        if (envelope.event == LiveUpdateEvent.END) {
            envelope.dismissAfter?.let { seconds ->
                if (seconds > 0L) builder.setTimeoutAfter(seconds * 1000L)
            }
        }

        val notification = builder.build()

        // Pre-post promotion eligibility check — logs a clear reason when the notification
        // will degrade to a non-promoted ongoing instead of becoming a chip.
        checkPromotionEligibility(context, notification)?.let { reason ->
            Log.warning(
                LOG_TAG,
                TAG,
                "Live Update will post as a NORMAL ongoing notification (not promoted to chip). Reason: $reason"
            )
        }

        NotificationManagerCompat.from(context).notify(id.hashCode(), notification)
        return true
    }

    /**
     * Returns null when the [notification] meets all structural and runtime preconditions for
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
        const val TAG = "LiveUpdateRenderer"
        const val LOG_TAG = "LiveUpdateRenderer"
    }
}
