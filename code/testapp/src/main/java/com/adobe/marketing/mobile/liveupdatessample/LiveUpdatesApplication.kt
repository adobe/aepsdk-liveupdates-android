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
import com.adobe.marketing.mobile.Assurance
import com.adobe.marketing.mobile.Edge
import com.adobe.marketing.mobile.Lifecycle
import com.adobe.marketing.mobile.LoggingMode
import com.adobe.marketing.mobile.Messaging
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.edge.identity.Identity
import com.adobe.marketing.mobile.messaging.liveupdate.LiveUpdateRenderer

class LiveUpdatesApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        MobileCore.setApplication(this)
        MobileCore.setLogLevel(LoggingMode.VERBOSE)
        // Fallback small icon for ALL Messaging-built notifications. Android refuses to
        // post a notification without a small icon; the testapp has no mipmap, so we
        // point at a platform drawable. Replace with R.drawable.ic_notification in a real app.
        MobileCore.setSmallIconResourceID(android.R.drawable.ic_popup_reminder)

        val extensions = listOf(
            Messaging.EXTENSION,
            Identity.EXTENSION,
            Lifecycle.EXTENSION,
            Edge.EXTENSION,
            Assurance.EXTENSION
        )
        MobileCore.registerExtensions(extensions) {
            // TODO: replace with real AJO config app id before testing real pushes.
            // MobileCore.configureWithAppID("YOUR_APP_ID")
            MobileCore.lifecycleStart(null)
        }

        // Single line of Live Updates wiring — replaces the previous
        // LiveUpdates.setApplication / LiveUpdates.registerStyleProvider pair.
        Messaging.setLiveUpdateHandler(LiveUpdateRenderer(SampleLiveUpdateStyleProvider()))
    }
}
