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

import com.adobe.marketing.mobile.MobileCore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationHistoryManagerTest {

    // recordAndValidate dispatches the old_timestamp render error via MobileCore on a stale
    // rejection, so the static must be mocked to keep these plain (non-Robolectric) tests off
    // the real Event Hub.
    private lateinit var mobileCoreMock: MockedStatic<MobileCore>

    @Before
    fun setUp() {
        mobileCoreMock = mockStatic(MobileCore::class.java)
    }

    @After
    fun tearDown() {
        mobileCoreMock.close()
    }

    @Test
    fun `isTimestampFresh rejects a timestamp older than the 28-day TTL`() {
        val staleTimestamp = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) -
            TimeUnit.DAYS.toSeconds(29)
        val payload = LiveUpdatePayload.create(
            notificationId = "id1",
            channelId = "chan1",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "Title",
            timestamp = staleTimestamp
        )

        assertFalse(NotificationHistoryManager.isTimestampFresh(payload))
    }

    @Test
    fun `recordTimestamp fails open when the database is unavailable`() {
        val payload = LiveUpdatePayload.create(
            notificationId = "id2",
            channelId = "chan2",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "Title",
            timestamp = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        )

        assertTrue(NotificationHistoryManager.recordTimestamp(payload))
    }

    @Test
    fun `evictExpiredAsync swallows a database failure and leaves the executor usable`() {
        // No app context here, so NotificationHistoryDatabase.getInstance() throws inside the task.
        NotificationHistoryManager.evictExpiredAsync()

        val payload = LiveUpdatePayload.create(
            notificationId = "id5",
            channelId = "chan5",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "Title",
            timestamp = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        )
        // Runs after the failed eviction on the same executor and still fails open.
        assertTrue(NotificationHistoryManager.recordTimestamp(payload))
    }

    @Test
    fun `recordAndValidate short-circuits on a stale timestamp without recording it`() {
        val staleTimestamp = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) -
            TimeUnit.DAYS.toSeconds(29)
        val payload = LiveUpdatePayload.create(
            notificationId = "id3",
            channelId = "chan3",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "Title",
            timestamp = staleTimestamp
        )

        assertFalse(NotificationHistoryManager.recordAndValidate(payload))
        // The stale rejection dispatches the invalid_timestamp render error to the Event Hub.
        mobileCoreMock.verify { MobileCore.dispatchEvent(any()) }
    }

    @Test
    fun `recordAndValidate returns true for a fresh timestamp`() {
        val payload = LiveUpdatePayload.create(
            notificationId = "id4",
            channelId = "chan4",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "Title",
            timestamp = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        )

        assertTrue(NotificationHistoryManager.recordAndValidate(payload))
    }
}
