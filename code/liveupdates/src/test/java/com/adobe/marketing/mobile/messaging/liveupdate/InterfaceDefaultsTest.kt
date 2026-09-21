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

import androidx.core.app.NotificationCompat
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ILiveUpdateListener] ships default empty-body methods; [ILiveUpdateInterceptor] and
 * [ILiveUpdateStyleProvider] are single-method (SAM) interfaces with no default bodies. These
 * tests simply invoke every default method (and instantiate the SAM interfaces) so the
 * generated default-method bytecode is exercised for coverage purposes.
 */
class InterfaceDefaultsTest {

    @Test
    fun `ILiveUpdateListener default methods are no-ops and do not throw`() {
        val listener = object : ILiveUpdateListener {}
        val payload = LiveUpdatePayload.create("id1", "chan", LiveUpdatePayload.EVENT_TYPE_START, "T", 1000L)

        listener.onLiveUpdateReceived(payload)
        listener.onStart(payload)
        listener.onUpdate(payload)
        listener.onEnd(payload)
        listener.onDismissed(payload)
    }

    @Test
    fun `ILiveUpdateInterceptor implementation is invoked`() {
        val interceptor = object : ILiveUpdateInterceptor {
            override fun shouldDisplayLiveUpdate(payload: LiveUpdatePayload) = true
        }
        val payload = LiveUpdatePayload.create("id1", "chan", LiveUpdatePayload.EVENT_TYPE_START, "T", 1000L)
        assertTrue(interceptor.shouldDisplayLiveUpdate(payload))
    }

    @Test
    fun `ILiveUpdateStyleProvider SAM implementation is invoked`() {
        val style = NotificationCompat.BigTextStyle()
        val provider = ILiveUpdateStyleProvider { style }
        val payload = LiveUpdatePayload.create("id1", "chan", LiveUpdatePayload.EVENT_TYPE_START, "T", 1000L)
        assertSame(style, provider.provideStyle(payload))
    }
}
