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

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Manages the lifecycle of a Live Update notification — channel setup, initial post,
 * periodic progress updates, and teardown.
 *
 * Construct one instance per Live Update session and call [start]. The instance
 * drives a Handler loop on the main thread that bumps the progress bar every
 * [UPDATE_INTERVAL_MS] milliseconds until it reaches [PROGRESS_MAX]. Call [stop]
 * to cancel early.
 *
 * @param context  Application or Activity context used for channel creation and posting.
 * @param onComplete  Optional callback invoked on the main thread when progress reaches 100.
 */
class LiveUpdateNotificationManager(
    private val context: Context,
    private val onComplete: (() -> Unit)? = null
) {

    companion object {
        private const val CHANNEL_ID = "live_updates_channel"
        private const val NOTIFICATION_ID = 1001
        private const val UPDATE_INTERVAL_MS = 10_000L
        private const val PROGRESS_STEP = 10
        private const val PROGRESS_MAX = 100
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentProgress = 0

    private val updateRunnable = object : Runnable {
        override fun run() {
            currentProgress = (currentProgress + PROGRESS_STEP).coerceAtMost(PROGRESS_MAX)
            postNotification()
            if (currentProgress < PROGRESS_MAX) {
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            } else {
                onComplete?.invoke()
            }
        }
    }

    init {
        createNotificationChannel()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Posts the initial notification at progress 0 and starts the update loop.
     * No-op if [POST_NOTIFICATIONS] permission has not been granted.
     */
    fun start() {
        currentProgress = 0
        postNotification()
        handler.postDelayed(updateRunnable, UPDATE_INTERVAL_MS)
    }

    /**
     * Cancels any pending updates. Does NOT dismiss the notification so the last
     * progress value remains visible to the user.
     */
    fun stop() {
        handler.removeCallbacks(updateRunnable)
    }

    // ── Channel ───────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Live Updates",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Ongoing Live Update progress notifications"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    // ── Notification posting ──────────────────────────────────────────────────

    private fun postNotification() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        // NotificationCompat.ProgressStyle is new in androidx.core 1.17.0.
        // When no Segments are added, progressMax defaults to 100.
        val progressStyle = NotificationCompat.ProgressStyle()
            .setProgress(currentProgress)
            .setStyledByProgress(true)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Live Update Demo")
            .setContentText("Progress: $currentProgress / $PROGRESS_MAX")
            .setStyle(progressStyle)
            .setOngoing(true)
            .setRequestPromotedOngoing(true)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
