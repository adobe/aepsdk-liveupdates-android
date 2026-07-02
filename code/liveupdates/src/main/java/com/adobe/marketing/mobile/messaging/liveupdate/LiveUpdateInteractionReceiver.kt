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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Broadcast receiver that fires dismiss tracking when the user swipes a Live Update chip
 * away. Attached as the `deleteIntent` target on every Live Update notification the SDK
 * builds. Registered in the SDK's own `AndroidManifest.xml`, so the host application
 * does not need to declare anything.
 *
 * Delegates to [LiveUpdates.handleNotificationResponse] with `applicationOpened = false`
 * and `customActionId = ` [LiveUpdates.ACTION_ID_DISMISS], which lands as
 * `liveUpdateTracking.customAction` with `pushNotificationTracking.customAction.actionID = "Dismiss"`
 * in the outbound XDM.
 */
class LiveUpdateInteractionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return
        if (intent.action != ACTION_DISMISS) return
        LiveUpdates.handleNotificationResponse(
            intent = intent,
            applicationOpened = false,
            customActionId = LiveUpdates.ACTION_ID_DISMISS
        )
    }

    companion object {
        /** Intent action string used for the dismiss broadcast. */
        const val ACTION_DISMISS = "com.adobe.marketing.mobile.messaging.liveupdate.action.DISMISS"
    }
}
