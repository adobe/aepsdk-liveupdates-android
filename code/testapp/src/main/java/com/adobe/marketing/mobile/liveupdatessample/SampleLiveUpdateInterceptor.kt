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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.adobe.marketing.mobile.messaging.liveupdate.ILiveUpdateInterceptor
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdatePayload

/**
 * Sample [ILiveUpdateInterceptor] that suppresses Live Updates the user has already dismissed.
 *
 * Gated by the [DISCARD_DISMISSED_UPDATES] app feature flag. When on, an incoming Live Update
 * whose [LiveUpdatePayload.notificationId] is present in [DismissedLiveUpdateStore] is vetoed
 * (returns `false`, so the SDK drops it entirely - no chip, tracking, or listener callback),
 * and a toast is shown so the discard is visible while testing. When off, every Live Update
 * proceeds unchanged.
 *
 * Solves the "a dismissed activity re-appears when its next push arrives" case: the listener
 * records the id on dismiss, and this interceptor keeps subsequent pushes for that id off-screen.
 */
class SampleLiveUpdateInterceptor(
    context: Context,
    private val dismissedStore: DismissedLiveUpdateStore
) : ILiveUpdateInterceptor {

    private val appContext = context.applicationContext
    // shouldDisplayLiveUpdate runs on the FCM background thread; Toast must go to the main thread.
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun shouldDisplayLiveUpdate(payload: LiveUpdatePayload): Boolean {
        if (!DISCARD_DISMISSED_UPDATES) return true

        val id = payload.notificationId
        if (dismissedStore.isDismissed(id)) {
            Log.d(TAG, "Discarding previously-dismissed Live Update: id=$id")
            mainHandler.post {
                Toast.makeText(
                    appContext,
                    "Dismissed Live Update arrived (id=$id) - discarded.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return false
        }
        return true
    }

    companion object {
        /**
         * App feature flag. When true, Live Updates the user previously dismissed are not shown
         * again (and are not tracked); flip to false to let every Live Update through.
         */
        const val DISCARD_DISMISSED_UPDATES = true
        private const val TAG = "LiveUpdateSample"
    }
}
