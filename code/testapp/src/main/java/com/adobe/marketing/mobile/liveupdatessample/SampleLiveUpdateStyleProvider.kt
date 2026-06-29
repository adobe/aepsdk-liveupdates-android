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

import androidx.core.app.NotificationCompat
import com.adobe.marketing.mobile.messaging.liveupdate.ILiveUpdateStyleProvider
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdatePayload

/**
 * Sample [ILiveUpdateStyleProvider]. Reads a `custom_key_template_type` key from
 * `payload.contentState` to pick a Style. The SDK does not enforce any key naming or
 * location for the template marker - the customer chooses where to read it from.
 *
 * Supported template types in this sample:
 *  - `progress`  -> NotificationCompat.ProgressStyle (flight / delivery / journey demos)
 *  - `metric`    -> NotificationCompat.ProgressStyle as a promotion-eligible fallback for
 *                   live-score / dashboard scenarios. Will switch to
 *                   NotificationCompat.MetricStyle when AndroidX core-ktx 1.18+ ships
 *                   the compat wrapper.
 *  - `big_text`  -> NotificationCompat.BigTextStyle (long body content)
 *
 * Dynamic state comes from `payload.contentState` under app-defined `custom_key_*` fields.
 */
class SampleLiveUpdateStyleProvider : ILiveUpdateStyleProvider {

    override fun provideStyle(payload: LiveUpdatePayload): NotificationCompat.Style? {
        val state = payload.contentState
        val templateType = state?.optString("custom_key_template_type")
            ?.takeIf { it.isNotEmpty() }
            ?: "standard"

        return when (templateType) {
            "progress" -> {
                val progress = state?.optInt("custom_key_journey_progress", 0) ?: 0
                NotificationCompat.ProgressStyle()
                    .setProgress(progress)
                    .setStyledByProgress(true)
            }
            "metric" -> {
                // Football scoreboard demo. Until AndroidX NotificationCompat.MetricStyle
                // ships (expected in core-ktx 1.18+), we use ProgressStyle with the match
                // minute as the progress value so the chip remains promotion-eligible.
                // The score itself is carried in the chip's critical_text + title/body, which
                // the renderer applies from the envelope. The progress bar visualises how far
                // along the match is (0..100 percent of 90 minutes).
                val matchTimeStr = state?.optString("custom_key_match_time") ?: ""
                val matchMinute = parseMatchMinute(matchTimeStr)
                val matchProgress = ((matchMinute.coerceIn(0, 120) * 100) / 90).coerceIn(0, 100)
                NotificationCompat.ProgressStyle()
                    .setProgress(matchProgress)
                    .setStyledByProgress(true)
            }
            "big_text" -> NotificationCompat.BigTextStyle()
                .bigText(payload.body)
            // "standard" / "call" or any app-specific template: return null to drop the push.
            else -> null
        }
    }

    /**
     * Parses match-time strings like `"23'"`, `"45+2'"`, `"FT"`, `"HT"` into a minute count
     * for the progress bar. Returns 0 on unparseable input, 90 on FT, 45 on HT.
     */
    private fun parseMatchMinute(matchTime: String): Int {
        if (matchTime.isEmpty()) return 0
        return when (matchTime.uppercase()) {
            "FT" -> 90
            "HT" -> 45
            else -> {
                // Strip trailing apostrophe and split off injury-time suffix (e.g. "45+2'" -> 45).
                val cleaned = matchTime.removeSuffix("'").substringBefore('+').trim()
                cleaned.toIntOrNull() ?: 0
            }
        }
    }
}
