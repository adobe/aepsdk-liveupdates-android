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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.adobe.marketing.mobile.Messaging
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.messaging.MessagingService
import com.adobe.marketing.mobile.messaging.NotificationInteractionReceiver
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdatePayload
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdates
import com.google.firebase.messaging.FirebaseMessagingService

// (LiveUpdates.trackLiveUpdateEvent stays imported for the Pattern 3 football branch.)
import com.google.firebase.messaging.RemoteMessage

/**
 * Reference implementation of the three integration patterns for AEP push handling when
 * the app owns its own [FirebaseMessagingService].
 *
 * Routing in this sample:
 *  - **Football scoreboard** (`content_state.custom_key_template_type == "metric"`):
 *    Pattern 3 (Manual) using platform `Notification.MetricStyle` accessed via reflection
 *    (the class is new in Android 17 / API 37 and not yet in androidx.core nor in the
 *    AGP 8.9.1-supported compileSdk; reflection is the pragmatic workaround until either
 *    AGP bumps to support compileSdk 37 or AndroidX ships a NotificationCompat.MetricStyle
 *    backport). Runtime gate: API 37+ only.
 *  - **Flight / journey** (`template_type == "progress"`) and anything else: Pattern 2
 *    (Mixed) - `LiveUpdates.handleLiveUpdatePush(...)` lets the SDK render via
 *    `NotificationCompat.ProgressStyle`.
 *  - **Standard AJO push** (non-Live-Update): `MessagingService.handleRemoteMessage`.
 *
 * Registered in the AndroidManifest as the FCM intake, replacing Messaging's auto-service
 * so the metric routing branch can fire.
 */
class SampleNotificationService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        MobileCore.setPushIdentifier(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // Peel off the football MetricStyle case BEFORE the standard AJO routing - this is
        // the only Live Update flow that needs Pattern 3 (manual) because platform
        // MetricStyle isn't accessible through the SDK's NotificationCompat-based renderer
        // yet. Everything else falls through to MessagingService.handleRemoteMessage below.
        if (LiveUpdatePayload.isLiveUpdate(message)) {
            val payload = LiveUpdatePayload.parse(message)
            val templateType =
                payload?.contentState?.optString("custom_key_template_type", "") ?: ""
            if (templateType == "metric" && Build.VERSION.SDK_INT >= 37 && payload != null) {
                handleFootballMetricStyle(payload, message)
                return
            }
        }

        // Single entry point covers both Live Updates (auto-dispatched to the registered
        // ILiveUpdateHandler when adb_liveupdate_data is present) AND standard AJO push.
        // No need for a separate LiveUpdates.handleLiveUpdatePush call.
        if (MessagingService.handleRemoteMessage(this, message)) return

        // Non-AEP pushes would be handled here.
    }

    // ============================================================================
    // Pattern 3 (Manual) - football scoreboard via platform Notification.MetricStyle.
    // ============================================================================
    //
    // MetricStyle landed in Android 17 (API 37) but is not yet in AndroidX's
    // NotificationCompat. To avoid bumping compileSdk past what AGP 8.9.1 understands,
    // we reach for the platform classes by name via reflection. Only fires on API 37+
    // (gated in onMessageReceived above).
    private fun handleFootballMetricStyle(payload: LiveUpdatePayload, message: RemoteMessage) {
        val state = payload.contentState ?: run {
            Log.w(TAG, "Football MetricStyle push has no content_state; dropping.")
            return
        }
        val homeTeam = state.optString("custom_key_home_team", "Home")
        val awayTeam = state.optString("custom_key_away_team", "Away")
        val homeScore = state.optInt("custom_key_home_score", 0)
        val awayScore = state.optInt("custom_key_away_score", 0)
        val matchTime = state.optString("custom_key_match_time", "")

        ensureMetricChannel(payload.channelId)

        val tapPi = buildTapPendingIntent(payload, message)
        val dismissPi = buildDismissPendingIntent(payload, message)

        val metricStyle = buildMetricStyleViaReflection(
            homeTeam = homeTeam,
            awayTeam = awayTeam,
            homeScore = homeScore,
            awayScore = awayScore,
            matchTime = matchTime
        )

        if (metricStyle == null) {
            Log.w(TAG, "MetricStyle unavailable on this device (need API 37+); dropping football chip.")
            return
        }

        @Suppress("DEPRECATION")
        val builder = Notification.Builder(this, payload.channelId)
            .setSmallIcon(resolveSmallIcon())
            .setContentTitle("$homeTeam vs $awayTeam")
            .setContentText("$homeScore - $awayScore   ${matchTime.ifEmpty { "live" }}")
            .setStyle(metricStyle)
            .setOngoing(true)
            .setColorized(true)
            .setColor(LIVERPOOL_RED)
            .setContentIntent(tapPi)
            .setDeleteIntent(dismissPi)
            .setOnlyAlertOnce(true)

        // setRequestPromotedOngoing was added in API 36 - safe to call via reflection
        // to avoid a compile-time NoSuchMethodError if compileSdk is below that.
        runCatching {
            Notification.Builder::class.java
                .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
        }

        payload.criticalText?.let { critical ->
            runCatching {
                Notification.Builder::class.java
                    .getMethod("setShortCriticalText", CharSequence::class.java)
                    .invoke(builder, critical)
            }
        }
        payload.whenMillis?.let { builder.setWhen(it).setShowWhen(true) }
        payload.dismissAfterSeconds?.takeIf { it > 0L }?.let {
            builder.setTimeoutAfter(it * 1000L)
        }

        getSystemService(NotificationManager::class.java)
            ?.notify(payload.notificationId.hashCode(), builder.build())

        LiveUpdates.trackLiveUpdateEvent(this, message)
    }

    /**
     * Builds a platform `Notification.MetricStyle` (API 37+) via reflection. Returns null
     * if any of the required classes are absent from this device's runtime (i.e. API < 37
     * or a stripped runtime). Three metrics are added: home score, away score, and match
     * time text. `setCriticalMetric(0)` marks the home score as the chip-visible headline.
     */
    private fun buildMetricStyleViaReflection(
        homeTeam: String,
        awayTeam: String,
        homeScore: Int,
        awayScore: Int,
        matchTime: String
    ): Notification.Style? {
        return try {
            val metricStyleClass = Class.forName("android.app.Notification\$MetricStyle")
            val metricClass = Class.forName("android.app.Notification\$Metric")
            val fixedIntClass = Class.forName("android.app.Notification\$Metric\$FixedInt")
            val fixedTextClass = Class.forName("android.app.Notification\$Metric\$FixedText")
            val metricValueClass = Class.forName("android.app.Notification\$Metric\$MetricValue")

            // FixedInt(int value)
            val fixedIntCtor = fixedIntClass.getConstructor(Int::class.javaPrimitiveType)
            // FixedText(CharSequence value)
            val fixedTextCtor = fixedTextClass.getConstructor(CharSequence::class.java)
            // Metric(MetricValue value, CharSequence label)
            val metricCtor = metricClass.getConstructor(metricValueClass, CharSequence::class.java)

            val homeMetric = metricCtor.newInstance(fixedIntCtor.newInstance(homeScore), homeTeam as CharSequence)
            val awayMetric = metricCtor.newInstance(fixedIntCtor.newInstance(awayScore), awayTeam as CharSequence)
            val timeMetric = metricCtor.newInstance(fixedTextCtor.newInstance(matchTime as CharSequence), "Time" as CharSequence)

            val style = metricStyleClass.getConstructor().newInstance()
            val addMetric = metricStyleClass.getMethod("addMetric", metricClass)
            addMetric.invoke(style, homeMetric)
            addMetric.invoke(style, awayMetric)
            addMetric.invoke(style, timeMetric)

            // setCriticalMetric(int index) - home score is the chip headline metric
            metricStyleClass.getMethod("setCriticalMetric", Int::class.javaPrimitiveType)
                .invoke(style, 0)

            style as Notification.Style
        } catch (e: Exception) {
            Log.w(TAG, "Failed to build Notification.MetricStyle via reflection: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun buildTapPendingIntent(payload: LiveUpdatePayload, message: RemoteMessage): PendingIntent {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            message.messageId?.let { Messaging.addPushTrackingDetails(this, it, message.data) }
        }
        return PendingIntent.getActivity(
            this, payload.notificationId.hashCode(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildDismissPendingIntent(payload: LiveUpdatePayload, message: RemoteMessage): PendingIntent {
        val dismissIntent = Intent(applicationContext, NotificationInteractionReceiver::class.java).apply {
            message.messageId?.let { Messaging.addPushTrackingDetails(this, it, message.data) }
        }
        return PendingIntent.getBroadcast(
            this, payload.notificationId.hashCode(), dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureMetricChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(channelId) != null) return
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Live Updates", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Status-bar chips for AJO Live Updates"
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun resolveSmallIcon(): Int {
        val configured = MobileCore.getSmallIconResourceID()
        return if (configured > 0) configured else applicationInfo.icon
    }

    private companion object {
        const val TAG = "SampleNotificationService"
        const val LIVERPOOL_RED: Int = 0xFFC8102E.toInt()
    }
}
