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
 * (notificationId, channelId) key, and records on acceptance; expired rows are evicted
 * asynchronously after posting (see [evictExpiredAsync]).
 */
internal object NotificationHistoryManager {
    private const val SELF_TAG = "NotificationHistoryManager"
    private val TTL_SECONDS = TimeUnit.DAYS.toSeconds(28)

    // single-thread executor guarantees DB work never runs on the caller's thread while
    private val dbExecutor = Executors.newSingleThreadExecutor()

    /**
     * @return `true` if [payload] is valid and was recorded; `false` if it was rejected
     * (already logged) and should be dropped by the caller.
     *
     * On rejection this also dispatches the matching Event Hub `renderError` diagnostic
     * (see [LiveUpdates.dispatchRenderErrorEvent]):
     *  - `invalid_timestamp` when the timestamp is older than the 28-day FCM delivery window.
     *  - `outdated_timestamp` when the timestamp is not newer than the last state already
     *    recorded for this (notificationId, channelId) - an out-of-order or duplicate update.
     */
    fun recordAndValidate(payload: LiveUpdatePayload): Boolean {
        if (!isTimestampFresh(payload)) {
            LiveUpdates.dispatchRenderErrorEvent(
                LiveUpdates.ERROR_SUBCATEGORY_INVALID_TIMESTAMP, payload
            )
            return false
        }
        if (!recordTimestamp(payload)) {
            LiveUpdates.dispatchRenderErrorEvent(
                LiveUpdates.ERROR_SUBCATEGORY_OUTDATED_TIMESTAMP, payload
            )
            return false
        }
        return true
    }

    /**
     * Pure staleness check: is [payload]'s timestamp within the 28-day FCM delivery window.
     * Does not touch the database and does not dispatch any event; [recordAndValidate] owns the
     * `old_timestamp` diagnostic for the rejection.
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
            return false
        }
        return true
    }

    /**
     * Persists [payload]'s timestamp if it's newer than the last recorded one for its
     * (notificationId, channelId) key. Eviction is not done here; see [evictExpiredAsync]. Runs on a
     * dedicated background thread; fails open (returns `true`) if the DB operation itself
     * throws, so a broken database never blocks a Live Update from rendering.
     *
     * @return `true` if recorded (or the DB failed open); `false` if rejected as a
     * regression/duplicate (already logged) and should be dropped by the caller.
     */
    internal fun recordTimestamp(payload: LiveUpdatePayload): Boolean {
        return try {
            dbExecutor.submit<Boolean> {
                val expiresAt = payload.timestamp + TTL_SECONDS
                val accepted = NotificationHistoryDatabase.getInstance().recordIfNewer(
                    payload.notificationId, payload.channelId, payload.timestamp, expiresAt
                )
                if (!accepted) {
                    Log.warning(
                        LiveUpdatesConstants.LOG_TAG,
                        SELF_TAG,
                        "Dropping Live Update id=${payload.notificationId}: timestamp " + "${payload.timestamp} is not newer than the last recorded timestamp " + "(older or a duplicate)."
                    )
                    // recordAndValidate dispatches the outdated_timestamp render error for this case.
                }
                accepted
            }.get()
        } catch (e: Exception) {
            Log.warning(
                LiveUpdatesConstants.LOG_TAG,
                SELF_TAG,
                "NotificationHistory DB operation failed; proceeding without history tracking: " + "${e.localizedMessage}"
            )
            true
        }
    }

    /**
     * Deletes expired history rows on the background executor without blocking the caller.
     * Meant to be called after the notification is posted so deletion adds no render latency.
     * Failures are logged and swallowed.
     */
    internal fun evictExpiredAsync() {
        val now = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        try {
            dbExecutor.submit {
                try {
                    NotificationHistoryDatabase.getInstance().deleteExpired(now)
                } catch (e: Exception) {
                    Log.warning(
                        LiveUpdatesConstants.LOG_TAG,
                        SELF_TAG,
                        "NotificationHistory eviction failed: ${e.localizedMessage}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.warning(
                LiveUpdatesConstants.LOG_TAG,
                SELF_TAG,
                "Could not schedule eviction: ${e.localizedMessage}"
            )
        }
    }
}
