package com.adobe.marketing.mobile.messaging.liveupdate

import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse

class NotificationManagerTest {

    @Test
    fun `recordAndValidate rejects a timestamp older than the 28-day TTL withouttouching the database`() {
        val staleTimestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(29)
        val payload = LiveUpdatePayload.create(
            notificationId = "id1",
            channelId = "chan1",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "Title",
            timestamp = staleTimestamp
        )

        assertFalse(NotificationHistoryManager.recordAndValidate(payload))
    }
}