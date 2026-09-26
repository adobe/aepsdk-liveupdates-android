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

import android.content.Intent
import com.adobe.marketing.mobile.Event
import com.adobe.marketing.mobile.MobileCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.times
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiveUpdateTrackerActivityTest {

    private lateinit var mobileCoreMock: MockedStatic<MobileCore>

    @Before
    fun setUp() {
        mobileCoreMock = mockStatic(MobileCore::class.java)
    }

    @After
    fun tearDown() {
        mobileCoreMock.close()
        LiveUpdates.setLiveUpdateListener(null)
    }

    private fun tapIntent(withPayload: Boolean = false): Intent {
        val intent = Intent(RuntimeEnvironment.getApplication(), LiveUpdateTrackerActivity::class.java)
        intent.putExtra(LiveUpdates.EXTRA_NOTIFICATION_ID, "id1")
        intent.putExtra(LiveUpdates.EXTRA_EVENT_TYPE, LiveUpdatePayload.EVENT_TYPE_START)
        if (withPayload) {
            val payload = LiveUpdatePayload.create("id1", "chan", LiveUpdatePayload.EVENT_TYPE_START, "T", 1000L)
            intent.putExtra(LiveUpdates.EXTRA_PAYLOAD, payload.toEnvelopeJson())
        }
        return intent
    }

    private fun capturedXdm(): Map<String, Any?> {
        val captor = ArgumentCaptor.forClass(Event::class.java)
        mobileCoreMock.verify({ MobileCore.dispatchEvent(captor.capture()) })
        @Suppress("UNCHECKED_CAST")
        return captor.value.eventData!!["xdm"] as Map<String, Any?>
    }

    @Test
    fun `chip tap dispatches applicationOpened tracking, launches nothing, and finishes`() {
        val activity = Robolectric.buildActivity(LiveUpdateTrackerActivity::class.java, tapIntent())
            .create().get()

        assertEquals("liveUpdateTracking.applicationOpened", capturedXdm()["eventType"])
        // The SDK does not launch any destination; opening the app is the app's responsibility
        // (handled from ILiveUpdateListener.onClick).
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `chip tap invokes the registered onClick listener with the re-hydrated payload`() {
        var clicked: LiveUpdatePayload? = null
        LiveUpdates.setLiveUpdateListener(object : ILiveUpdateListener {
            override fun onClick(payload: LiveUpdatePayload) {
                clicked = payload
            }
        })

        Robolectric.buildActivity(LiveUpdateTrackerActivity::class.java, tapIntent(withPayload = true))
            .create()

        assertEquals("id1", clicked?.notificationId)
    }

    @Test
    fun `onNewIntent re-processes the incoming intent and dispatches tracking again`() {
        val controller = Robolectric.buildActivity(LiveUpdateTrackerActivity::class.java, tapIntent())
            .create()
        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, times(1))

        controller.newIntent(tapIntent())

        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, times(2))
    }
}
