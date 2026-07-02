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
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.adobe.marketing.mobile.services.Log

/**
 * Transparent, single-instance activity that intercepts Live Update chip taps and
 * action-button clicks so tracking fires before the actual destination is launched.
 * Not intended to be invoked directly by application code - the SDK's
 * [LiveUpdateHandlerImpl] targets it when it constructs the notification's PendingIntents.
 *
 * Behaviour:
 *  1. Reads the Live Update tracking extras placed on the incoming intent by
 *     [LiveUpdates.addPushTrackingDetails].
 *  2. Delegates to [LiveUpdates.handleNotificationResponse] with the derived
 *     `applicationOpened` and `customActionId` values (chip tap has no actionId; action
 *     buttons carry the button label).
 *  3. Launches the destination: the URI carried on the intent's
 *     [LiveUpdates.EXTRA_ACTION_URI] if present, otherwise the host app's default launcher
 *     activity via [android.content.pm.PackageManager.getLaunchIntentForPackage].
 *  4. Calls [finish] so the tracker never remains on the back stack.
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

        val actionId = incoming.getStringExtra(LiveUpdates.EXTRA_ACTION_ID)
        val applicationOpened = actionId.isNullOrEmpty()

        LiveUpdates.handleNotificationResponse(
            intent = incoming,
            applicationOpened = applicationOpened,
            customActionId = actionId
        )

        launchDestination(incoming)
    }

    private fun launchDestination(incoming: Intent) {
        val destinationUri = incoming.getStringExtra(LiveUpdates.EXTRA_ACTION_URI)
        val launchIntent: Intent? = if (!destinationUri.isNullOrEmpty()) {
            Intent(Intent.ACTION_VIEW, Uri.parse(destinationUri)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        } else {
            packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }

        if (launchIntent == null) {
            Log.debug(
                TAG, TAG,
                "No destination to launch for Live Update interaction (no action_uri and no launcher activity)."
            )
            return
        }
        try {
            startActivity(launchIntent)
        } catch (e: ActivityNotFoundException) {
            Log.warning(
                TAG, TAG,
                "Unable to launch destination for Live Update interaction: ${e.localizedMessage}"
            )
        }
    }

    private companion object {
        const val TAG = "LiveUpdateTrackerActivity"
    }
}
