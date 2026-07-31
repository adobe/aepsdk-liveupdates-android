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

import android.content.Context
import com.adobe.marketing.mobile.ILiveUpdateHandler
import com.adobe.marketing.mobile.messaging.MessagingConstants
import com.adobe.marketing.mobile.services.notification.LiveActivityRenderer
import com.google.firebase.messaging.RemoteMessage

/**
 * Adapts the existing [ILiveUpdateHandler] rendering path to Core's host-neutral
 * [LiveActivityRenderer] port, so a Live Update handler can be discovered and invoked by
 * any host (not only the Messaging extension) via [com.adobe.marketing.mobile.services.ServiceRegistry].
 */
class LiveActivityRendererAdapter(private val handler: ILiveUpdateHandler) : LiveActivityRenderer {

    override fun canRender(data: Map<String, String>): Boolean =
        data.containsKey(MessagingConstants.Push.PayloadKeys.LIVE_UPDATE_DATA)

    override fun render(context: Context, data: Map<String, String>): Boolean = try {
        val builder = RemoteMessage.Builder("liveupdates@adobe.local")
        for ((k, v) in data) builder.addData(k, v)
        handler.handleLiveUpdatePush(context, builder.build())
        true
    } catch (t: Throwable) {
        false
    }
}
