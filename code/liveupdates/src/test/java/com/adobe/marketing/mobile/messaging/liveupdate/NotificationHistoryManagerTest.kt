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

import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationHistoryManagerTest {

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
