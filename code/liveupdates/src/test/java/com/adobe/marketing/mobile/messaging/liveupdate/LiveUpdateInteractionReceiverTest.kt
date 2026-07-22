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
import android.content.Intent
import com.adobe.marketing.mobile.Event
import com.adobe.marketing.mobile.MobileCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`

class LiveUpdateInteractionReceiverTest {

    private lateinit var mobileCoreMock: MockedStatic<MobileCore>
    private val receiver = LiveUpdateInteractionReceiver()

    @Before
    fun setUp() {
        mobileCoreMock = mockStatic(MobileCore::class.java)
        LiveUpdates.setLiveUpdateListener(null)
    }

    @After
    fun tearDown() {
        LiveUpdates.setLiveUpdateListener(null)
        mobileCoreMock.close()
    }

    @Test
    fun `onReceive is a no-op for a null intent`() {
        receiver.onReceive(mock(Context::class.java), null)
        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, never())
    }

    @Test
    fun `onReceive is a no-op for a non-dismiss action`() {
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn("some.other.action")
        receiver.onReceive(mock(Context::class.java), intent)
        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, never())
    }

    @Test
    fun `onReceive on ACTION_DISMISS dispatches tracking and notifies listener`() {
        var dismissedPayload: LiveUpdatePayload? = null
        LiveUpdates.setLiveUpdateListener(object : ILiveUpdateListener {
            override fun onDismissed(payload: LiveUpdatePayload) {
                dismissedPayload = payload
            }
        })

        val payload = LiveUpdatePayload.create("id1", "chan", LiveUpdatePayload.EVENT_TYPE_END, "T")
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(LiveUpdateInteractionReceiver.ACTION_DISMISS)
        `when`(intent.getStringExtra(LiveUpdates.EXTRA_NOTIFICATION_ID)).thenReturn("id1")
        `when`(intent.getStringExtra(LiveUpdates.EXTRA_PAYLOAD)).thenReturn(payload.toEnvelopeJson())

        receiver.onReceive(mock(Context::class.java), intent)

        // Dismiss tracking was dispatched with customActionId = "Dismiss".
        val captor = ArgumentCaptor.forClass(Event::class.java)
        mobileCoreMock.verify({ MobileCore.dispatchEvent(captor.capture()) })
        @Suppress("UNCHECKED_CAST")
        val xdm = captor.value.eventData!!["xdm"] as Map<String, Any?>
        assertEquals("liveUpdateTracking.customAction", xdm["eventType"])

        // Listener was notified with the re-hydrated payload.
        assertNotNull(dismissedPayload)
        assertEquals("id1", dismissedPayload!!.notificationId)
    }

    @Test
    fun `onReceive on ACTION_DISMISS without notification id extra skips tracking dispatch`() {
        val intent = mock(Intent::class.java)
        `when`(intent.action).thenReturn(LiveUpdateInteractionReceiver.ACTION_DISMISS)
        `when`(intent.getStringExtra(LiveUpdates.EXTRA_NOTIFICATION_ID)).thenReturn(null)

        receiver.onReceive(mock(Context::class.java), intent)

        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, never())
    }
}
