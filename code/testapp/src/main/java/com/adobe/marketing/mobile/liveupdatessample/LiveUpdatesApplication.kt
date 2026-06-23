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

import android.app.Application
import android.util.Log
import com.adobe.marketing.mobile.Assurance
import com.adobe.marketing.mobile.Edge
import com.adobe.marketing.mobile.Lifecycle
import com.adobe.marketing.mobile.LoggingMode
import com.adobe.marketing.mobile.Messaging
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.edge.identity.Identity
import com.adobe.marketing.mobile.messaging.liveupdate.ILiveUpdateListener
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdateHandlerImpl
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdatePayload
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdates

class LiveUpdatesApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        MobileCore.setApplication(this)
        MobileCore.setLogLevel(LoggingMode.VERBOSE)
        // Fallback small icon for ALL Messaging-built notifications. Android refuses to
        // post a notification without a small icon; the testapp has no mipmap, so we
        // point at a platform drawable. Replace with R.drawable.ic_notification in a real app.
        MobileCore.setSmallIconResourceID(android.R.drawable.ic_popup_reminder)

        // No need to register the Live Update NotificationChannel here. As of v1.1 the SDK
        // (LiveUpdateHandlerImpl) auto-creates the channel from the payload's channel_id
        // with IMPORTANCE_HIGH if it does not already exist - mirrors the Messaging SDK's
        // createChannelAndGetChannelID behaviour. If the app wants to pre-register the
        // channel with a custom name / sound / etc., do it here and the SDK will leave it
        // untouched.

        val extensions = listOf(
            Messaging.EXTENSION,
            Identity.EXTENSION,
            Lifecycle.EXTENSION,
            Edge.EXTENSION,
            Assurance.EXTENSION
        )
        MobileCore.registerExtensions(extensions) {
            // TODO: replace with real AJO config app id before testing real pushes.

           // "1b50a869c4a2/c48168263818/launch-f1bc3576d343"
             MobileCore.configureWithAppID("staging/1b50a869c4a2/c48168263818/launch-f1bc3576d343")
            MobileCore.lifecycleStart(null)
        }

        // Single line of Live Updates handler wiring (Pattern 1 - auto).
        Messaging.setLiveUpdateHandler(LiveUpdateHandlerImpl(SampleLiveUpdateStyleProvider()))

        // Optional: register a Live Update event listener so the app gets a callback when
        // each start / update / end push is processed. onLiveUpdateReceived is the generic
        // hook; the specific onStart / onUpdate / onEnd fires next based on event_type.
        LiveUpdates.setLiveUpdateListener(object : ILiveUpdateListener {
            override fun onLiveUpdateReceived(payload: LiveUpdatePayload) {
                Log.d(
                    TAG,
                    "Live Update received: id=${payload.notificationId} event=${payload.eventType}"
                )
            }

            override fun onStart(payload: LiveUpdatePayload) {
                Log.d(TAG, "Live Update START: id=${payload.notificationId} title='${payload.title}'")
            }

            override fun onUpdate(payload: LiveUpdatePayload) {
                Log.d(TAG, "Live Update UPDATE: id=${payload.notificationId} title='${payload.title}'")
            }

            override fun onEnd(payload: LiveUpdatePayload) {
                Log.d(TAG, "Live Update END: id=${payload.notificationId} (chip will dismiss soon)")
            }
        })
    }

    private companion object {
        const val TAG = "LiveUpdateSample"
    }
}
