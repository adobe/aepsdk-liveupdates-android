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
    private val TTL_MILLIS = TimeUnit.DAYS.toMillis(28)

    // single-thread executor guarantees DB work never runs on the caller's thread while
    private val dbExecutor = Executors.newSingleThreadExecutor()

    /**
     * @return `true` if [payload] is valid and was recorded; `false` if it was rejected
     * (already logged) and should be dropped by the caller.
     */
    fun recordAndValidate(payload: LiveUpdatePayload): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - TTL_MILLIS

        if (now - payload.timestamp > TTL_MILLIS) {
            Log.warning(
                LiveUpdatesConstants.LOG_TAG,
                SELF_TAG,
                "Dropping Live Update id=${payload.notificationId}: timestamp is older than " +
                    "the 28-day FCM delivery window"
            )
            // TODO: fire an XDM error event for this rejection
            return false
        }

        return try {
            dbExecutor.submit<Boolean> {
                val accepted = NotificationHistoryDatabase.getInstance().recordIfNewer(
                    payload.notificationId,
                    payload.channelId,
                    payload.timestamp,
                    cutoff
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
