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

import android.content.Context

/**
 * App-level store of Live Update notification ids the user has dismissed. Backed by
 * SharedPreferences so it survives process death: a dismiss and a later re-arrival of the
 * same activity can span separate process lifetimes (the dismiss broadcast may itself
 * cold-start the app). Consulted by [SampleLiveUpdateInterceptor] to veto re-showing a chip
 * the user already swiped away.
 */
class DismissedLiveUpdateStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Records [notificationId] as dismissed by the user. */
    fun markDismissed(notificationId: String) {
        if (notificationId.isEmpty()) return
        // Copy before mutating - the Set returned by getStringSet must not be edited in place.
        val updated = HashSet(current())
        updated.add(notificationId)
        prefs.edit().putStringSet(KEY_DISMISSED_IDS, updated).apply()
    }

    /** True if [notificationId] was previously dismissed by the user. */
    fun isDismissed(notificationId: String): Boolean =
        notificationId.isNotEmpty() && current().contains(notificationId)

    /** Clears all recorded dismissals (useful for a fresh test run). */
    fun clear() {
        prefs.edit().remove(KEY_DISMISSED_IDS).apply()
    }

    private fun current(): Set<String> =
        prefs.getStringSet(KEY_DISMISSED_IDS, emptySet()) ?: emptySet()

    private companion object {
        const val PREFS_NAME = "live_update_dismissed"
        const val KEY_DISMISSED_IDS = "dismissed_notification_ids"
    }
}
