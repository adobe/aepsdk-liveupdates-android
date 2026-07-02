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
import com.adobe.marketing.mobile.edge.identity.AuthenticatedState
import com.adobe.marketing.mobile.edge.identity.Identity
import com.adobe.marketing.mobile.edge.identity.IdentityItem
import com.adobe.marketing.mobile.edge.identity.IdentityMap
import com.adobe.marketing.mobile.messaging.liveupdate.ILiveUpdateListener
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdateHandlerImpl
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdatePayload
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdates

class LiveUpdatesApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        MobileCore.setApplication(this)
        MobileCore.setLogLevel(LoggingMode.VERBOSE)
        // The platform requires every notification to declare a small icon. Replace this
        // placeholder drawable with the app's own resource (for example R.drawable.ic_notification).
        MobileCore.setSmallIconResourceID(android.R.drawable.ic_popup_reminder)

        val extensions = listOf(
            Messaging.EXTENSION,
            Identity.EXTENSION,
            Lifecycle.EXTENSION,
            Edge.EXTENSION,
            Assurance.EXTENSION
        )
        MobileCore.registerExtensions(extensions) {
            // TODO: replace with the environment file id from your Adobe Data Collection
            // (Launch) property before running the sample app against real infrastructure.
            MobileCore.configureWithAppID("YOUR_ENVIRONMENT_FILE_ID")
            MobileCore.lifecycleStart(null)

            // Primary identity demonstration. AJO uses this to correlate server-side
            // reporting and inbound campaigns. Replace the placeholder address with the
            // identifier your app authenticates the user with.
            val identityMap = IdentityMap().apply {
                addItem(
                    IdentityItem("user@example.com", AuthenticatedState.AUTHENTICATED, true),
                    "Email"
                )
            }
            Identity.updateIdentities(identityMap)
        }

        // Auto-mode integration: register a LiveUpdateHandlerImpl with the app's
        // ILiveUpdateStyleProvider. The SDK takes over rendering on every incoming push
        // whose data map carries `adb_liveupdate_data`.
        Messaging.setLiveUpdateHandler(LiveUpdateHandlerImpl(SampleLiveUpdateStyleProvider(applicationContext)))

        // Optional: react to Live Update lifecycle events from the app side. The generic
        // onLiveUpdateReceived fires for every push; onStart / onUpdate / onEnd fire next
        // based on the envelope's event_type.
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
