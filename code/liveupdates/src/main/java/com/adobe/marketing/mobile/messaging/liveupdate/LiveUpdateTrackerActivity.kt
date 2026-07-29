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

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Transparent, single-instance activity that intercepts a Live Update chip tap so tracking
 * fires and the app's `onClick` listener is invoked. Not intended to be invoked directly by
 * application code - the SDK's [LiveUpdateHandlerImpl] targets it from the chip's content
 * PendingIntent.
 *
 * Behaviour:
 *  1. Fires the `liveUpdateTracking.applicationOpened` tracking event via
 *     [LiveUpdates.handleNotificationResponse].
 *  2. Invokes [ILiveUpdateListener.onClick] via [LiveUpdates.notifyClicked] so the host app
 *     can react (e.g. open a screen). Opening the app is the app's responsibility - the SDK
 *     does not launch any destination (no deep-link / web-URL support in this version).
 *  3. Calls [finish] so the tracker never remains on the back stack.
 */
class LiveUpdateTrackerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        processIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        processIntent(intent)
        finish()
    }

    private fun processIntent(incoming: Intent?) {
        if (incoming == null) return

        // Chip body tap: fire the applicationOpened lifecycle tracking event.
        LiveUpdates.handleNotificationResponse(
            intent = incoming,
            applicationOpened = true,
            customActionId = null
        )

        // Hand the tap to the app's onClick listener. Opening a screen is the app's call.
        LiveUpdates.notifyClicked(incoming)
    }
}
