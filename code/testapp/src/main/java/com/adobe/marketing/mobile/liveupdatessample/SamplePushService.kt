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

import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.messaging.MessagingService
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Sample [FirebaseMessagingService] for apps that own their own FCM entry point instead of
 * relying on the Messaging SDK's built-in service. The three integration patterns supported
 * by the Live Updates SDK map onto this class as follows:
 *
 *  1. **Auto** - Register `com.adobe.marketing.mobile.messaging.MessagingService` in
 *     `AndroidManifest.xml` and do NOT register this class. The Messaging SDK owns FCM
 *     and dispatches Live Updates to the registered `ILiveUpdateHandler`.
 *
 *  2. **Mixed** (this class as written) - Register this service in place of Messaging's
 *     default. `MessagingService.handleRemoteMessage` still routes AEP pushes - both
 *     Live Updates and standard AJO push - to the SDK. Add app-specific handling for
 *     non-AEP pushes below the `if (...) return` line.
 *
 *  3. **Manual** - Register this service, then replace the body of `onMessageReceived`
 *     with app-controlled rendering: check `LiveUpdatePayload.isLiveUpdate(message)`,
 *     parse with `LiveUpdatePayload.parse(message)`, build the notification directly,
 *     and call `LiveUpdates.trackLiveUpdateEvent(context, message)` to fire AJO push
 *     tracking and invoke any registered `ILiveUpdateListener`. In manual mode, do not
 *     call `Messaging.setLiveUpdateHandler` - the SDK's renderer is bypassed.
 *
 * This class is not registered in the sample app's `AndroidManifest.xml`; the sample
 * runs pattern 1 (auto) by default. Copy this file into your own project and update the
 * manifest to switch to pattern 2 or pattern 3.
 */
class SamplePushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        MobileCore.setPushIdentifier(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        // Returns true if this was an AEP push (Live Update or standard AJO push) and
        // has been handled by the Messaging SDK. Live Updates are dispatched to the
        // registered ILiveUpdateHandler for rendering.
        if (MessagingService.handleRemoteMessage(this, message)) return

        // Non-AEP pushes: implement app-specific handling below.
    }
}
