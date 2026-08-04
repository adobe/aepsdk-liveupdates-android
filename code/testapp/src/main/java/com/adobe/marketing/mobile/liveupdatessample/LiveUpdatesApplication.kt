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
import android.content.Intent
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
import com.google.firebase.messaging.FirebaseMessaging

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
            Assurance.startSession("lutest://app?adb_validation_sessionid=06c29485-8f80-4820-80de-1c4449de40fe")
        }

        // Auto-mode integration: register with the app's ILiveUpdateStyleProvider via the
        // Extensible Plugins registry. The SDK takes over rendering on every incoming push
        // whose data map carries `adb_liveupdate_data`.
        LiveUpdates.register(SampleLiveUpdateStyleProvider(applicationContext))

        // Interceptor demo: suppress Live Updates the user already dismissed. The store records
        // dismissed ids (see onDismissed below); the interceptor vetoes any incoming Live Update
        // whose id is in that set. Gated by SampleLiveUpdateInterceptor.DISCARD_DISMISSED_UPDATES.
       /* val dismissedStore = DismissedLiveUpdateStore(applicationContext)
        LiveUpdates.setLiveUpdateInterceptor(
            SampleLiveUpdateInterceptor(applicationContext, dismissedStore)
        )*/

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
                // On start, subscribe this device to the Live Update's topic so future
                // broadcast pushes for the same activity reach us. Topic subscribe /
                // unsubscribe is an application-side responsibility; the SDK exposes
                // tracking dispatch (trackTopicSubscribed) so the subscribe event lands in
                // AJO reporting alongside the lifecycle events. Fired only on Firebase
                // success so reporting counts reflect real server-side subscription state.
                val topic = payload.topicName ?: return
                FirebaseMessaging.getInstance().subscribeToTopic(topic)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            // Pass the full payload so the subscribe event correlates to the
                            // originating campaign / journey via the push's _xdm.
                            LiveUpdates.trackTopicSubscribed(topic, payload)
                            Log.d(TAG, "Subscribed to topic '$topic' (triggered by Live Update start).")
                        } else {
                            Log.w(TAG, "subscribeToTopic($topic) failed: ${task.exception?.localizedMessage}")
                        }
                    }
            }

            override fun onUpdate(payload: LiveUpdatePayload) {
                Log.d(TAG, "Live Update UPDATE: id=${payload.notificationId} title='${payload.title}'")
            }

            override fun onEnd(payload: LiveUpdatePayload) {
                Log.d(TAG, "Live Update END: id=${payload.notificationId} (chip will dismiss soon)")
            }

            override fun onDismissed(payload: LiveUpdatePayload) {
                Log.d(
                    TAG,
                    "Live Update DISMISSED: id=${payload.notificationId} title='${payload.title}' " +
                        "event=${payload.eventType}"
                )
                // Remember the dismissal so the interceptor keeps this activity's future pushes
                // off-screen. Gated by the same feature flag as the interceptor.
                if (SampleLiveUpdateInterceptor.DISCARD_DISMISSED_UPDATES) {
                    dismissedStore.markDismissed(payload.notificationId)
                }
                unsubscribeFromTopic(payload)
            }

            override fun onClick(payload: LiveUpdatePayload) {
                Log.d(TAG, "Live Update CLICKED: id=${payload.notificationId} - opening the app.")
                // The SDK fires applicationOpened tracking and hands the tap here; opening a
                // screen is the app's responsibility. Launch MainActivity from the application
                // context (NEW_TASK, since there is no activity in the back stack).
                val intent = Intent(applicationContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(intent)
            }
        })
    }

    private fun unsubscribeFromTopic(payload: LiveUpdatePayload) {
        val topic = payload.topicName ?: return
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    // Pass the full payload so the unsubscribe event correlates to the
                    // originating campaign / journey via the push's _xdm.
                    LiveUpdates.trackTopicUnsubscribed(topic, payload)
                    Log.d(TAG, "Unsubscribed from topic '$topic' (triggered by Live Update end).")
                } else {
                    Log.w(TAG, "unsubscribeFromTopic($topic) failed: ${task.exception?.localizedMessage}")
                }
            }
    }
    private companion object {
        const val TAG = "LiveUpdateSample"
    }
}
