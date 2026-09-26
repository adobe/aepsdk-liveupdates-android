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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
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
}
