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
import com.adobe.marketing.mobile.services.ServiceProvider

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
        if (destinationUri.isNullOrEmpty()) {
            openApplication()
        } else {
            openUri(destinationUri)
        }
    }

    /**
     * Opens the host application. If an activity is currently in the foreground, resumes
     * it via a same-component intent so the user lands back where they were; otherwise
     * launches the default launcher activity. Mirrors Messaging's
     * `MessagingPushTrackerActivity.openApplication`.
     */
    private fun openApplication() {
        val currentActivity = ServiceProvider.getInstance().appContextService.currentActivity
        val launchIntent: Intent? = if (currentActivity != null) {
            Intent(currentActivity, currentActivity.javaClass)
        } else {
            Log.debug(TAG, TAG, "No active activity; opening launcher activity.")
            packageManager.getLaunchIntentForPackage(packageName)
        }
        if (launchIntent == null) {
            Log.warning(
                TAG, TAG,
                "Unable to create an intent to open the application from the Live Update interaction."
            )
            return
        }
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        startActivity(launchIntent)
    }

    /**
     * Opens the provided URI via [Intent.ACTION_VIEW]. Logs a warning and no-ops if no
     * activity on the device can handle the URI. Matches Messaging's `openUri` behaviour
     * exactly - no automatic fallback to the launcher activity so a misconfigured deeplink
     * surfaces clearly in the logs instead of silently coercing to a launcher open.
     */
    private fun openUri(uri: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
        } catch (e: ActivityNotFoundException) {
            Log.warning(
                TAG, TAG,
                "Unable to open the URI from the Live Update interaction. URI: $uri"
            )
        }
    }

    private companion object {
        const val TAG = "LiveUpdateTrackerActivity"
    }
}
