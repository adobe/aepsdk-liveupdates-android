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

import android.os.Build
import androidx.core.app.NotificationCompat
import com.adobe.marketing.mobile.messaging.liveupdate.ILiveUpdateStyleProvider
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdatePayload

/**
 * Sample [ILiveUpdateStyleProvider]. Reads `custom_key_template_type` from
 * `payload.contentState` and returns a matching [NotificationCompat.Style].
 *
 * Supported template types:
 *  - `progress` - [NotificationCompat.ProgressStyle] for journey / delivery flows.
 *  - `metric`   - platform [android.app.Notification.MetricStyle] bridged via
 *                 [MetricStyleCompat] on API 37+; [NotificationCompat.ProgressStyle]
 *                 fallback (with match minute as progress) on older devices.
 *  - `big_text` - [NotificationCompat.BigTextStyle].
 *
 * Any other template value causes the provider to return `null`, which the SDK
 * treats as "drop this push".
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
                if (Build.VERSION.SDK_INT >= 37) {
                    val home = state?.optString("custom_key_home_team", "Home") ?: "Home"
                    val away = state?.optString("custom_key_away_team", "Away") ?: "Away"
                    val homeScore = state?.optInt("custom_key_home_score", 0) ?: 0
                    val awayScore = state?.optInt("custom_key_away_score", 0) ?: 0
                    val matchTime = state?.optString("custom_key_match_time", "") ?: ""
                    MetricStyleCompat(
                        homeTeam = home,
                        awayTeam = away,
                        homeScore = homeScore,
                        awayScore = awayScore,
                        matchTime = matchTime,
                        criticalMetricIndex = 0
                    )
                } else {
                    val matchTimeStr = state?.optString("custom_key_match_time") ?: ""
                    val matchMinute = parseMatchMinute(matchTimeStr)
                    val matchProgress = ((matchMinute.coerceIn(0, 120) * 100) / 90).coerceIn(0, 100)
                    NotificationCompat.ProgressStyle()
                        .setProgress(matchProgress)
                        .setStyledByProgress(true)
                }
            }
            "big_text" -> NotificationCompat.BigTextStyle()
                .bigText(payload.body)
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
