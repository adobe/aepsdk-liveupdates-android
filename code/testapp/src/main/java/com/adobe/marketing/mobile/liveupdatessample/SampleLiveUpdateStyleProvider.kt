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
import com.adobe.marketing.mobile.LiveUpdateTemplateType
import com.adobe.marketing.mobile.MessagingPushPayload
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdateStyleProvider

/**
 * Sample [LiveUpdateStyleProvider]. Switches on the envelope's template_type (Android Style
 * class name) and constructs the matching [NotificationCompat.Style] from `contentState`.
 *
 * The domain (airplane vs food vs sport) is opaque to the SDK — the app either infers from
 * `contentState` field shape or reads an explicit `domain` key inside `contentState` if it
 * cares to differentiate.
 */
class SampleLiveUpdateStyleProvider : LiveUpdateStyleProvider {

    override fun provideStyle(payload: MessagingPushPayload): NotificationCompat.Style? {
        val envelope = payload.liveUpdate ?: return null
        val state = envelope.contentState

        return when (envelope.templateType) {
            LiveUpdateTemplateType.PROGRESS -> {
                val progress = state?.optDouble("journeyProgress", 0.0)?.toInt() ?: 0
                NotificationCompat.ProgressStyle()
                    .setProgress(progress)
                    .setStyledByProgress(true)
            }
            LiveUpdateTemplateType.BIG_TEXT -> NotificationCompat.BigTextStyle()
                .bigText(payload.body)
            LiveUpdateTemplateType.STANDARD -> null
            // CALL and METRIC samples are out of scope for the demo
            else -> null
        }
    }
}
