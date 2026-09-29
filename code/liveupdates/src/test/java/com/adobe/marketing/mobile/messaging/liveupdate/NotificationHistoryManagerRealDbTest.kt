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

import com.adobe.marketing.mobile.Event
import com.adobe.marketing.mobile.MobileCore
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises [NotificationHistoryManager] against a real [NotificationHistoryDatabase] instance
 * (via a real ApplicationContext), unlike [NotificationHistoryManagerTest] which runs without an
 * Android context and only ever hits the fail-open path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationHistoryManagerRealDbTest {

    @Before
    fun setUp() {
        MobileCore.setApplication(RuntimeEnvironment.getApplication())
    }

    @Test
    fun `recordAndValidate accepts a fresh id then rejects a duplicate delivery`() {
        val now = System.currentTimeMillis() / 1000
        val payload = LiveUpdatePayload.create(
            notificationId = "real-db-id",
            channelId = "real-db-chan",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "Title",
            timestamp = now
        )

        assertTrue(NotificationHistoryManager.recordAndValidate(payload))
        assertFalse(NotificationHistoryManager.recordAndValidate(payload))
    }

    @Test
    fun `recordAndValidate rejects an out-of-order update and reports outdated_timestamp`() {
        val now = System.currentTimeMillis() / 1000
        val newer = LiveUpdatePayload.create(
            notificationId = "ooo-id",
            channelId = "ooo-chan",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "Title",
            timestamp = now
        )
        val older = LiveUpdatePayload.create(
            notificationId = "ooo-id",
            channelId = "ooo-chan",
            eventType = LiveUpdatePayload.EVENT_TYPE_UPDATE,
            title = "Title",
            timestamp = now - 100
        )

        // First (newer) delivery is recorded; no error dispatched.
        assertTrue(NotificationHistoryManager.recordAndValidate(newer))

        // The older, out-of-order delivery is rejected and reports the outdated_timestamp error.
        // The app context set in setUp() already populated ServiceProvider, so the real DB keeps
        // working while MobileCore is mocked here to capture the dispatch.
        mockStatic(MobileCore::class.java).use { mobileCoreMock ->
            assertFalse(NotificationHistoryManager.recordAndValidate(older))
            mobileCoreMock.verify { MobileCore.dispatchEvent(any()) }
        }
    }

    @Test
    fun `local start is caught up on the next backend event exactly once`() {
        val ctx = RuntimeEnvironment.getApplication()
        val now = System.currentTimeMillis() / 1000
        val local = LiveUpdatePayload.create(
            notificationId = "lc-id", channelId = "lc-chan",
            eventType = LiveUpdatePayload.EVENT_TYPE_LOCAL_START, title = "Local", timestamp = now
        )
        val update = LiveUpdatePayload.create(
            notificationId = "lc-id", channelId = "lc-chan",
            eventType = LiveUpdatePayload.EVENT_TYPE_UPDATE, title = "Update", timestamp = now + 10,
            xdm = JSONObject().put("mixins", JSONObject().put("campaignMarker", "camp-123"))
        )

        // 1) Local start: registers for catch-up, dispatches NO tracking event.
        mockStatic(MobileCore::class.java).use { m ->
            LiveUpdates.dispatchLiveUpdateEventTracking(ctx, local)
            m.verify({ MobileCore.dispatchEvent(any()) }, never())
        }

        // 2) First backend update: fires the update received AND the localstart catch-up (2 events).
        mockStatic(MobileCore::class.java).use { m ->
            LiveUpdates.dispatchLiveUpdateEventTracking(ctx, update)
            val captor = ArgumentCaptor.forClass(Event::class.java)
            m.verify({ MobileCore.dispatchEvent(captor.capture()) }, times(2))
            val liveActivityEvents = captor.allValues.map { liveActivityEventOf(it) }
            assertTrue(liveActivityEvents.contains("liveupdate_localstart"))
            assertTrue(liveActivityEvents.contains("liveupdate_update"))
            // The catch-up carries the update's _xdm (copied, mixins flattened to root),
            // correlating the start to the campaign.
            val catchUp = captor.allValues.first { liveActivityEventOf(it) == "liveupdate_localstart" }
            assertEquals("camp-123", xdmOf(catchUp)["campaignMarker"])
        }

        // 3) Second backend update for the same id+channel: catch-up already consumed -> 1 event.
        mockStatic(MobileCore::class.java).use { m ->
            LiveUpdates.dispatchLiveUpdateEventTracking(ctx, update)
            m.verify({ MobileCore.dispatchEvent(any()) }, times(1))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun xdmOf(event: Event): Map<String, Any?> =
        event.eventData!!["xdm"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun liveActivityEventOf(event: Event): String? {
        val exp = xdmOf(event)["_experience"] as? Map<String, Any?> ?: return null
        val cjm = exp["customerJourneyManagement"] as? Map<String, Any?> ?: return null
        val pcc = cjm["pushChannelContext"] as? Map<String, Any?> ?: return null
        val la = pcc["liveActivity"] as? Map<String, Any?> ?: return null
        return la["event"] as? String
    }
}
