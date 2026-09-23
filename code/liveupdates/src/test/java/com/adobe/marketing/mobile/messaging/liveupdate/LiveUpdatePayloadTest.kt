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

import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LiveUpdatePayloadTest {

    // ---------- isLiveUpdate ----------

    @Test
    fun `isLiveUpdate returns true when adb_liveupdate_data key present`() {
        val message = mock(RemoteMessage::class.java)
        `when`(message.data).thenReturn(mapOf("adb_liveupdate_data" to "{}"))
        assertTrue(LiveUpdatePayload.isLiveUpdate(message))
    }

    @Test
    fun `isLiveUpdate returns false when key absent`() {
        val message = mock(RemoteMessage::class.java)
        `when`(message.data).thenReturn(mapOf("some_other_key" to "value"))
        assertFalse(LiveUpdatePayload.isLiveUpdate(message))
    }

    // ---------- parse(RemoteMessage) ----------

    @Test
    fun `parse returns null when envelope key missing`() {
        val message = mock(RemoteMessage::class.java)
        `when`(message.data).thenReturn(emptyMap())
        assertNull(LiveUpdatePayload.parse(message))
    }

    @Test
    fun `parse returns null when envelope value empty`() {
        val message = mock(RemoteMessage::class.java)
        `when`(message.data).thenReturn(mapOf("adb_liveupdate_data" to ""))
        assertNull(LiveUpdatePayload.parse(message))
    }

    @Test
    fun `parse returns null on malformed json`() {
        val message = mock(RemoteMessage::class.java)
        `when`(message.data).thenReturn(mapOf("adb_liveupdate_data" to "{not-json"))
        assertNull(LiveUpdatePayload.parse(message))
    }

    @Test
    fun `parse returns null when notification_id missing`() {
        val envelope = JSONObject()
            .put("notification_channel_id", "chan")
            .put("event_type", "start")
            .put("title", "Title")
        val message = remoteMessageWith(envelope.toString(), null)
        assertNull(LiveUpdatePayload.parse(message))
    }

    @Test
    fun `parse returns null when channel_id missing`() {
        val envelope = JSONObject()
            .put("notification_id", "id1")
            .put("event_type", "start")
            .put("title", "Title")
        val message = remoteMessageWith(envelope.toString(), null)
        assertNull(LiveUpdatePayload.parse(message))
    }

    @Test
    fun `parse returns null when event_type missing`() {
        val envelope = JSONObject()
            .put("notification_id", "id1")
            .put("notification_channel_id", "chan")
            .put("title", "Title")
        val message = remoteMessageWith(envelope.toString(), null)
        assertNull(LiveUpdatePayload.parse(message))
    }

    @Test
    fun `parse returns null when title missing`() {
        val envelope = JSONObject()
            .put("notification_id", "id1")
            .put("notification_channel_id", "chan")
            .put("event_type", "start")
        val message = remoteMessageWith(envelope.toString(), null)
        assertNull(LiveUpdatePayload.parse(message))
    }

    @Test
    fun `parse returns null when required field is empty string`() {
        val envelope = JSONObject()
            .put("notification_id", "")
            .put("notification_channel_id", "chan")
            .put("event_type", "start")
            .put("title", "Title")
        val message = remoteMessageWith(envelope.toString(), null)
        assertNull(LiveUpdatePayload.parse(message))
    }

    @Test
    fun `parse succeeds with only required fields`() {
        val envelope = JSONObject()
            .put("notification_id", "id1")
            .put("notification_channel_id", "chan")
            .put("event_type", "start")
            .put("title", "Title")
            .put("timestamp", 1000L)
        val message = remoteMessageWith(envelope.toString(), null)
        val payload = LiveUpdatePayload.parse(message)
        assertNotNull(payload)
        assertEquals("id1", payload!!.notificationId)
        assertEquals("chan", payload.channelId)
        assertEquals("start", payload.eventType)
        assertEquals("Title", payload.title)
        // envelope carries `timestamp` in epoch seconds; used as-is, no conversion
        assertEquals(1000L, payload.timestamp)
        assertNull(payload.priority)
        assertNull(payload.body)
        assertNull(payload.criticalText)
        assertNull(payload.whenSeconds)
        assertNull(payload.dismissAfterSeconds)
        assertNull(payload.contentState)
        assertNull(payload.topicName)
        assertNull(payload.smallIcon)
        assertNull(payload.xdm)
    }

    @Test
    fun `parse populates all optional fields`() {
        val contentState = JSONObject().put("custom_key_progress", 42)
        val envelope = JSONObject()
            .put("notification_id", "id1")
            .put("notification_channel_id", "chan")
            .put("event_type", "update")
            .put("title", "Title")
            .put("timestamp", 2000L)
            .put("priority", "PRIORITY_HIGH")
            .put("body", "Body text")
            .put("critical_text", "Critical!")
            .put("when", 12345L)
            .put("dismiss_after", 60L)
            .put("content_state", contentState)
            .put("topic_name", "topic1")
            .put("small_icon", "ic_flight")
        val xdmRaw = JSONObject().put("messageExecutionID", "exec1").toString()
        val message = remoteMessageWith(envelope.toString(), xdmRaw)

        val payload = LiveUpdatePayload.parse(message)
        assertNotNull(payload)
        payload!!
        // `timestamp` and `when` are carried in epoch seconds, used as-is; conversion to
        // millis only happens at the Android API boundary (NotificationCompat.Builder.setWhen()).
        assertEquals(2000L, payload.timestamp)
        assertEquals("PRIORITY_HIGH", payload.priority)
        assertEquals("Body text", payload.body)
        assertEquals("Critical!", payload.criticalText)
        assertEquals(12345L, payload.whenSeconds)
        assertEquals(60L, payload.dismissAfterSeconds)
        assertEquals(42, payload.contentState?.optInt("custom_key_progress"))
        assertEquals("topic1", payload.topicName)
        assertEquals("ic_flight", payload.smallIcon)
        assertNotNull(payload.xdm)
        assertEquals("exec1", payload.xdm?.optString("messageExecutionID"))
    }

    @Test
    fun `parse treats malformed xdm as null without failing the whole parse`() {
        val envelope = JSONObject()
            .put("notification_id", "id1")
            .put("notification_channel_id", "chan")
            .put("event_type", "start")
            .put("title", "Title")
            .put("timestamp", 1000L)
        val message = remoteMessageWith(envelope.toString(), "{not-valid-json")
        val payload = LiveUpdatePayload.parse(message)
        assertNotNull(payload)
        assertNull(payload!!.xdm)
    }

    @Test
    fun `parse treats empty xdm string as null`() {
        val envelope = JSONObject()
            .put("notification_id", "id1")
            .put("notification_channel_id", "chan")
            .put("event_type", "start")
            .put("title", "Title")
            .put("timestamp", 1000L)
        val message = remoteMessageWith(envelope.toString(), "")
        val payload = LiveUpdatePayload.parse(message)
        assertNotNull(payload)
        assertNull(payload!!.xdm)
    }

    // ---------- create ----------

    @Test
    fun `create with only required fields defaults optional fields to null`() {
        val payload = LiveUpdatePayload.create(
            notificationId = "id1",
            channelId = "chan",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "Title",
            timestamp = 1000L
        )
        assertEquals("id1", payload.notificationId)
        assertEquals("chan", payload.channelId)
        assertEquals(LiveUpdatePayload.EVENT_TYPE_START, payload.eventType)
        assertEquals("Title", payload.title)
        assertEquals(1000L, payload.timestamp)
        assertNull(payload.priority)
        assertNull(payload.body)
        assertNull(payload.xdm)
    }

    @Test
    fun `create with all fields populates every property`() {
        val contentState = JSONObject().put("k", "v")
        val xdm = JSONObject().put("campaignID", "c1")
        val payload = LiveUpdatePayload.create(
            notificationId = "id2",
            channelId = "chan2",
            eventType = LiveUpdatePayload.EVENT_TYPE_END,
            title = "Title2",
            timestamp = 2000L,
            priority = "PRIORITY_MAX",
            body = "Body2",
            criticalText = "Crit2",
            whenSeconds = 999L,
            dismissAfterSeconds = 30L,
            contentState = contentState,
            topicName = "topicX",
            smallIcon = "ic_x",
            xdm = xdm
        )
        assertEquals(2000L, payload.timestamp)
        assertEquals("PRIORITY_MAX", payload.priority)
        assertEquals("Body2", payload.body)
        assertEquals("Crit2", payload.criticalText)
        assertEquals(999L, payload.whenSeconds)
        assertEquals(30L, payload.dismissAfterSeconds)
        assertEquals("v", payload.contentState?.optString("k"))
        assertEquals("topicX", payload.topicName)
        assertEquals("ic_x", payload.smallIcon)
        assertEquals("c1", payload.xdm?.optString("campaignID"))
    }

    // ---------- toEnvelopeJson / fromEnvelopeJson round trip ----------

    @Test
    fun `round trip create toEnvelopeJson fromEnvelopeJson yields equal fields`() {
        val contentState = JSONObject().put("k", "v")
        val original = LiveUpdatePayload.create(
            notificationId = "id3",
            channelId = "chan3",
            eventType = LiveUpdatePayload.EVENT_TYPE_UPDATE,
            title = "Title3",
            timestamp = 3000L,
            priority = "PRIORITY_LOW",
            body = "Body3",
            criticalText = "Crit3",
            whenSeconds = 111_000L,
            dismissAfterSeconds = 10L,
            contentState = contentState,
            topicName = "topicY",
            smallIcon = "ic_y"
        )

        val envelopeJson = original.toEnvelopeJson()
        val rehydrated = LiveUpdatePayload.fromEnvelopeJson(envelopeJson, null)

        assertNotNull(rehydrated)
        rehydrated!!
        assertEquals(original.notificationId, rehydrated.notificationId)
        assertEquals(original.channelId, rehydrated.channelId)
        assertEquals(original.eventType, rehydrated.eventType)
        assertEquals(original.title, rehydrated.title)
        assertEquals(original.timestamp, rehydrated.timestamp)
        assertEquals(original.priority, rehydrated.priority)
        assertEquals(original.body, rehydrated.body)
        assertEquals(original.criticalText, rehydrated.criticalText)
        assertEquals(original.whenSeconds, rehydrated.whenSeconds)
        assertEquals(original.dismissAfterSeconds, rehydrated.dismissAfterSeconds)
        assertEquals(original.contentState.toString(), rehydrated.contentState.toString())
        assertEquals(original.topicName, rehydrated.topicName)
        assertEquals(original.smallIcon, rehydrated.smallIcon)
    }

    @Test
    fun `round trip carries xdm separately via fromEnvelopeJson`() {
        val original = LiveUpdatePayload.create(
            notificationId = "id4",
            channelId = "chan4",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "Title4",
            timestamp = 4000L
        )
        val envelopeJson = original.toEnvelopeJson()
        val xdmRaw = JSONObject().put("campaignID", "camp1").toString()
        val rehydrated = LiveUpdatePayload.fromEnvelopeJson(envelopeJson, xdmRaw)
        assertNotNull(rehydrated)
        assertEquals("camp1", rehydrated!!.xdm?.optString("campaignID"))
    }

    @Test
    fun `toEnvelopeJson omits null optional fields`() {
        val payload = LiveUpdatePayload.create(
            notificationId = "id5",
            channelId = "chan5",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "Title5",
            timestamp = 5000L
        )
        val json = JSONObject(payload.toEnvelopeJson())
        // timestamp round-trips through the envelope unchanged, no conversion
        assertEquals(5000L, json.optLong("timestamp"))
        assertFalse(json.has("priority"))
        assertFalse(json.has("body"))
        assertFalse(json.has("critical_text"))
        assertFalse(json.has("when"))
        assertFalse(json.has("dismiss_after"))
        assertFalse(json.has("content_state"))
        assertFalse(json.has("topic_name"))
        assertFalse(json.has("small_icon"))
        assertEquals("id5", json.optString("notification_id"))
    }

    @Test
    fun `fromEnvelopeJson returns null on malformed json`() {
        assertNull(LiveUpdatePayload.fromEnvelopeJson("{not-json", null))
    }

    @Test
    fun `fromEnvelopeJson returns null when required field missing`() {
        val json = JSONObject().put("notification_id", "id1").toString()
        assertNull(LiveUpdatePayload.fromEnvelopeJson(json, null))
    }

    @Test
    fun `parse returns null when timestamp is implausibly large (likely millis sent by mistake)`() {
        val envelope = JSONObject()
            .put("notification_id", "id1")
            .put("notification_channel_id", "chan")
            .put("event_type", "start")
            .put("title", "Title")
            .put("timestamp", 1758100000000L)
        val message = remoteMessageWith(envelope.toString(), null)
        assertNull(LiveUpdatePayload.parse(message))
    }

    @Test
    fun `parse ignores an implausible when value but still posts the payload`() {
        val envelope = JSONObject()
            .put("notification_id", "id1")
            .put("notification_channel_id", "chan")
            .put("event_type", "start")
            .put("title", "Title")
            .put("timestamp", 1000L)
            .put("when", 1758100000000L) // millis sent by mistake
        val message = remoteMessageWith(envelope.toString(), null)

        val payload = LiveUpdatePayload.parse(message)

        assertNotNull(payload)
        assertNull(payload!!.whenSeconds)
    }

    private fun remoteMessageWith(envelopeJson: String, xdmRaw: String?): RemoteMessage {
        val message = mock(RemoteMessage::class.java)
        val data = mutableMapOf("adb_liveupdate_data" to envelopeJson)
        if (xdmRaw != null) {
            data["_xdm"] = xdmRaw
        }
        `when`(message.data).thenReturn(data)
        return message
    }
}
