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

import androidx.core.app.NotificationCompat
import com.adobe.marketing.mobile.MessagingPushPayload

/**
 * App-side hook for Live Update visual styling. Returns the [NotificationCompat.Style] to
 * apply to this push — `ProgressStyle` on API 36+, `MetricStyle` on API 37+, or any future
 * promotion-eligible style. Returning `null` causes [LiveUpdateRenderer] to decline the push;
 * Messaging then renders it via its default path.
 *
 * The provider reads everything it needs from the payload directly — template type via
 * `payload.liveUpdate?.templateType`, dynamic state via `payload.liveUpdate?.contentState`,
 * etc. The SDK does not pre-parse or pre-route this for the provider.
 *
 * Invoked on the FCM background thread; do not perform long-running work.
 */
fun interface LiveUpdateStyleProvider {
    fun provideStyle(payload: MessagingPushPayload): NotificationCompat.Style?
}
