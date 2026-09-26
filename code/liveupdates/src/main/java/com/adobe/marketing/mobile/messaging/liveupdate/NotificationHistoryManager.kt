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

import com.adobe.marketing.mobile.services.Log
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Validates an incoming [LiveUpdatePayload]'s timestamp against the tracked history for its
 * (notificationId, channelId) key, and records/evicts on acceptance.
 */
internal object NotificationHistoryManager {
    private const val SELF_TAG = "NotificationHistoryManager"
    private val TTL_SECONDS = TimeUnit.DAYS.toSeconds(28)

    // single-thread executor guarantees DB work never runs on the caller's thread while
    private val dbExecutor = Executors.newSingleThreadExecutor()

    /**
     * @return `true` if [payload] is valid and was recorded; `false` if it was rejected
     * (already logged) and should be dropped by the caller.
     */
    fun recordAndValidate(payload: LiveUpdatePayload): Boolean =
        isTimestampFresh(payload) && recordTimestamp(payload)

    /**
     * Pure staleness check: is [payload]'s timestamp within the 28-day FCM delivery window.
     * Does not touch the database.
     *
     * @return `true` if the timestamp is fresh enough to consider; `false` if it's already
     * outside the window (already logged) and should be dropped by the caller.
     */
    internal fun isTimestampFresh(payload: LiveUpdatePayload): Boolean {
        val now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        if (now - payload.timestamp > TTL_SECONDS) {
            Log.warning(
                LiveUpdatesConstants.LOG_TAG,
                SELF_TAG,
                "Dropping Live Update id=${payload.notificationId}: timestamp is older than the 28-day FCM delivery window"
            )
            // TODO: fire an XDM error event for this rejection
            return false
        }
        return true
    }

    /**
     * Persists [payload]'s timestamp if it's newer than the last recorded one for its
     * (notificationId, channelId) key, evicting expired rows alongside the write. Runs on a
     * dedicated background thread; fails open (returns `true`) if the DB operation itself
     * throws, so a broken database never blocks a Live Update from rendering.
     *
     * @return `true` if recorded (or the DB failed open); `false` if rejected as a
     * regression/duplicate (already logged) and should be dropped by the caller.
     */
    internal fun recordTimestamp(payload: LiveUpdatePayload): Boolean {
        val now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        return try {
            dbExecutor.submit<Boolean> {
                val expiresAt = payload.timestamp + TTL_SECONDS
                val accepted = NotificationHistoryDatabase.getInstance().recordIfNewer(
                    payload.notificationId,
                    payload.channelId,
                    payload.timestamp,
                    expiresAt,
                    now
                )
                if (!accepted) {
                    Log.warning(
                        LiveUpdatesConstants.LOG_TAG,
                        SELF_TAG,
                        "Dropping Live Update id=${payload.notificationId}: timestamp " +
                            "${payload.timestamp} is not newer than the last recorded timestamp " +
                            "(older or a duplicate)."
                    )
                    // TODO: fire an XDM error event for this rejection once defined
                }
                accepted
            }.get()
        } catch (e: Exception) {
            Log.warning(
                LiveUpdatesConstants.LOG_TAG,
                SELF_TAG,
                "NotificationHistory DB operation failed; proceeding without history tracking: " +
                    "${e.localizedMessage}"
            )
            true
        }
    }
}
