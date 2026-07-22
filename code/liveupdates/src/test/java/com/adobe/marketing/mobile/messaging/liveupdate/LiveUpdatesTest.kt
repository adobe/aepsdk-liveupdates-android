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
import com.adobe.marketing.mobile.ILiveUpdateHandler
import com.adobe.marketing.mobile.Messaging
import com.adobe.marketing.mobile.MobileCore
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.MockedStatic
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class LiveUpdatesTest {

    private lateinit var mobileCoreMock: MockedStatic<MobileCore>

    @Before
    fun setUp() {
        mobileCoreMock = mockStatic(MobileCore::class.java)
        LiveUpdates.setLiveUpdateListener(null)
        LiveUpdates.setLiveUpdateInterceptor(null)
        setCachedDatasetId(null)
    }

    @After
    fun tearDown() {
        LiveUpdates.setLiveUpdateListener(null)
        LiveUpdates.setLiveUpdateInterceptor(null)
        setCachedDatasetId(null)
        mobileCoreMock.close()
    }

    // =====================================================================
    // extensionVersion
    // =====================================================================

    @Test
    fun `extensionVersion returns the SDK version`() {
        assertEquals("1.0.0", LiveUpdates.extensionVersion())
    }

    // =====================================================================
    // Listener registration
    // =====================================================================

    @Test
    fun `setLiveUpdateListener and getLiveUpdateListener round trip`() {
        val listener = object : ILiveUpdateListener {}
        LiveUpdates.setLiveUpdateListener(listener)
        assertSame(listener, LiveUpdates.getLiveUpdateListener())
        LiveUpdates.setLiveUpdateListener(null)
        assertNull(LiveUpdates.getLiveUpdateListener())
    }

    @Test
    fun `invokeListener fires onLiveUpdateReceived then onStart`() {
        val calls = mutableListOf<String>()
        LiveUpdates.setLiveUpdateListener(recordingListener(calls))
        LiveUpdates.invokeListener(payload(eventType = LiveUpdatePayload.EVENT_TYPE_START))
        assertEquals(listOf("received", "start"), calls)
    }

    @Test
    fun `invokeListener fires onLiveUpdateReceived then onUpdate`() {
        val calls = mutableListOf<String>()
        LiveUpdates.setLiveUpdateListener(recordingListener(calls))
        LiveUpdates.invokeListener(payload(eventType = LiveUpdatePayload.EVENT_TYPE_UPDATE))
        assertEquals(listOf("received", "update"), calls)
    }

    @Test
    fun `invokeListener fires onLiveUpdateReceived then onEnd`() {
        val calls = mutableListOf<String>()
        LiveUpdates.setLiveUpdateListener(recordingListener(calls))
        LiveUpdates.invokeListener(payload(eventType = LiveUpdatePayload.EVENT_TYPE_END))
        assertEquals(listOf("received", "end"), calls)
    }

    @Test
    fun `invokeListener with non-canonical event_type only fires onLiveUpdateReceived`() {
        val calls = mutableListOf<String>()
        LiveUpdates.setLiveUpdateListener(recordingListener(calls))
        LiveUpdates.invokeListener(payload(eventType = "something_else"))
        assertEquals(listOf("received"), calls)
    }

    @Test
    fun `invokeListener is a no-op when no listener is registered`() {
        // Should not throw.
        LiveUpdates.invokeListener(payload(eventType = LiveUpdatePayload.EVENT_TYPE_START))
    }

    @Test
    fun `invokeListener catches listener exceptions`() {
        LiveUpdates.setLiveUpdateListener(object : ILiveUpdateListener {
            override fun onLiveUpdateReceived(payload: LiveUpdatePayload) {
                throw RuntimeException("boom")
            }
        })
        // Should not propagate.
        LiveUpdates.invokeListener(payload(eventType = LiveUpdatePayload.EVENT_TYPE_START))
    }

    // =====================================================================
    // Interceptor registration / shouldDisplay
    // =====================================================================

    @Test
    fun `setLiveUpdateInterceptor and getLiveUpdateInterceptor round trip`() {
        val interceptor = interceptorReturning(true)
        LiveUpdates.setLiveUpdateInterceptor(interceptor)
        assertSame(interceptor, LiveUpdates.getLiveUpdateInterceptor())
        LiveUpdates.setLiveUpdateInterceptor(null)
        assertNull(LiveUpdates.getLiveUpdateInterceptor())
    }

    @Test
    fun `shouldDisplay returns true when no interceptor registered`() {
        assertTrue(LiveUpdates.shouldDisplay(payload()))
    }

    @Test
    fun `shouldDisplay respects interceptor returning true`() {
        LiveUpdates.setLiveUpdateInterceptor(interceptorReturning(true))
        assertTrue(LiveUpdates.shouldDisplay(payload()))
    }

    @Test
    fun `shouldDisplay respects interceptor returning false`() {
        LiveUpdates.setLiveUpdateInterceptor(interceptorReturning(false))
        assertFalse(LiveUpdates.shouldDisplay(payload()))
    }

    @Test
    fun `shouldDisplay fails open to true when interceptor throws`() {
        LiveUpdates.setLiveUpdateInterceptor(
            object : ILiveUpdateInterceptor {
                override fun shouldDisplayLiveUpdate(payload: LiveUpdatePayload): Boolean =
                    throw RuntimeException("boom")
            }
        )
        assertTrue(LiveUpdates.shouldDisplay(payload()))
    }

    // =====================================================================
    // trackLiveUpdateEvent (Pattern 3 manual)
    // =====================================================================

    @Test
    fun `trackLiveUpdateEvent no-ops when payload fails to parse`() {
        val message = mock(RemoteMessage::class.java)
        `when`(message.data).thenReturn(emptyMap())
        LiveUpdates.trackLiveUpdateEvent(mock(Context::class.java), message)
        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, never())
    }

    @Test
    fun `trackLiveUpdateEvent dispatches tracking and invokes listener on success`() {
        val calls = mutableListOf<String>()
        LiveUpdates.setLiveUpdateListener(recordingListener(calls))
        val p = payload(eventType = LiveUpdatePayload.EVENT_TYPE_START)
        val message = remoteMessageForEnvelope(p.toEnvelopeJson())
        LiveUpdates.trackLiveUpdateEvent(mock(Context::class.java), message)
        captureEvent()
        assertEquals(listOf("received", "start"), calls)
    }

    // =====================================================================
    // triggerLocalLiveUpdate
    // =====================================================================

    @Test
    fun `triggerLocalLiveUpdate returns false when no handler registered`() {
        mockStatic(Messaging::class.java).use { messagingMock ->
            messagingMock.`when`<ILiveUpdateHandler?> { Messaging.getLiveUpdateHandler() }
                .thenReturn(null)
            assertFalse(LiveUpdates.triggerLocalLiveUpdate(mock(Context::class.java), payload()))
        }
    }

    @Test
    fun `triggerLocalLiveUpdate returns false for a custom non-canonical handler`() {
        val customHandler = object : ILiveUpdateHandler {
            override fun handleLiveUpdatePush(context: Context, message: RemoteMessage) {}
        }
        mockStatic(Messaging::class.java).use { messagingMock ->
            messagingMock.`when`<ILiveUpdateHandler?> { Messaging.getLiveUpdateHandler() }
                .thenReturn(customHandler)
            assertFalse(LiveUpdates.triggerLocalLiveUpdate(mock(Context::class.java), payload()))
        }
    }

    @Test
    fun `triggerLocalLiveUpdate delegates to postLiveUpdate and returns true for canonical handler`() {
        val handlerImpl = mock(LiveUpdateHandlerImpl::class.java)
        val context = mock(Context::class.java)
        val p = payload()
        mockStatic(Messaging::class.java).use { messagingMock ->
            messagingMock.`when`<ILiveUpdateHandler?> { Messaging.getLiveUpdateHandler() }
                .thenReturn(handlerImpl)
            val result = LiveUpdates.triggerLocalLiveUpdate(context, p)
            assertTrue(result)
            verify(handlerImpl).postLiveUpdate(context, p)
        }
    }

    // =====================================================================
    // addPushTrackingDetails
    // =====================================================================

    @Test
    fun `addPushTrackingDetails returns false for null intent or message`() {
        assertFalse(LiveUpdates.addPushTrackingDetails(null, mock(RemoteMessage::class.java)))
        assertFalse(LiveUpdates.addPushTrackingDetails(mock(Intent::class.java), null))
    }

    @Test
    fun `addPushTrackingDetails returns false when message is not a Live Update`() {
        val intent = mock(Intent::class.java)
        val message = mock(RemoteMessage::class.java)
        `when`(message.data).thenReturn(emptyMap())
        assertFalse(LiveUpdates.addPushTrackingDetails(intent, message))
    }

    @Test
    fun `addPushTrackingDetails adds extras and returns true`() {
        val intent = mock(Intent::class.java)
        val xdm = JSONObject().put("campaignID", "camp1")
        val p = payload(eventType = LiveUpdatePayload.EVENT_TYPE_START, topicName = "topicA", xdm = xdm)
        val message = remoteMessageForEnvelope(p.toEnvelopeJson(), xdm.toString())

        val result = LiveUpdates.addPushTrackingDetails(intent, message)

        assertTrue(result)
        verify(intent).putExtra(LiveUpdates.EXTRA_NOTIFICATION_ID, p.notificationId)
        verify(intent).putExtra(LiveUpdates.EXTRA_EVENT_TYPE, p.eventType)
        verify(intent).putExtra(LiveUpdates.EXTRA_CHANNEL_ID, "topicA")
        verify(intent).putExtra(LiveUpdates.EXTRA_XDM, xdm.toString())
    }

    @Test
    fun `addPushTrackingDetails omits channel and xdm extras when absent`() {
        val intent = mock(Intent::class.java)
        val p = payload(eventType = LiveUpdatePayload.EVENT_TYPE_START)
        val message = remoteMessageForEnvelope(p.toEnvelopeJson())

        assertTrue(LiveUpdates.addPushTrackingDetails(intent, message))
        verify(intent, never()).putExtra(eq(LiveUpdates.EXTRA_CHANNEL_ID), anyString())
        verify(intent, never()).putExtra(eq(LiveUpdates.EXTRA_XDM), anyString())
    }

    // =====================================================================
    // handleNotificationResponse
    // =====================================================================

    @Test
    fun `handleNotificationResponse returns false for null intent`() {
        assertFalse(LiveUpdates.handleNotificationResponse(null, true))
    }

    @Test
    fun `handleNotificationResponse returns false when notification id extra missing`() {
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra(LiveUpdates.EXTRA_NOTIFICATION_ID)).thenReturn(null)
        assertFalse(LiveUpdates.handleNotificationResponse(intent, true))
        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, never())
    }

    @Test
    fun `handleNotificationResponse dispatches applicationOpened tracking on tap`() {
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra(LiveUpdates.EXTRA_NOTIFICATION_ID)).thenReturn("id1")
        `when`(intent.getStringExtra(LiveUpdates.EXTRA_CHANNEL_ID)).thenReturn("topicA")

        val result = LiveUpdates.handleNotificationResponse(intent, true)

        assertTrue(result)
        val xdm = xdmMap(captureEvent())
        assertEquals("liveUpdateTracking.applicationOpened", xdm["eventType"])
        assertNull(liveActivity(xdm)["event"])
    }

    @Test
    fun `handleNotificationResponse dispatches customAction tracking for action button or dismiss`() {
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra(LiveUpdates.EXTRA_NOTIFICATION_ID)).thenReturn("id1")

        val result = LiveUpdates.handleNotificationResponse(intent, false, "Dismiss")

        assertTrue(result)
        val xdm = xdmMap(captureEvent())
        assertEquals("liveUpdateTracking.customAction", xdm["eventType"])
        @Suppress("UNCHECKED_CAST")
        val customAction = pushNotificationTracking(xdm)["customAction"] as Map<String, Any?>
        assertEquals("Dismiss", customAction["actionID"])
    }

    @Test
    fun `handleNotificationResponse tolerates malformed xdm extra`() {
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra(LiveUpdates.EXTRA_NOTIFICATION_ID)).thenReturn("id1")
        `when`(intent.getStringExtra(LiveUpdates.EXTRA_XDM)).thenReturn("{not-json")

        assertTrue(LiveUpdates.handleNotificationResponse(intent, true))
        captureEvent()
    }

    @Test
    fun `dispatchInteractionTracking skips dispatch when neither applicationOpened nor customActionId set`() {
        LiveUpdates.dispatchInteractionTracking(
            notificationId = "id1",
            topicName = null,
            incomingXdm = null,
            applicationOpened = false,
            customActionId = null
        )
        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, never())
    }

    // =====================================================================
    // Topic subscription tracking
    // =====================================================================

    @Test
    fun `trackTopicSubscribed lightweight overload dispatches topic_subscribed`() {
        LiveUpdates.trackTopicSubscribed("topicA")
        val xdm = xdmMap(captureEvent())
        assertEquals("liveUpdateTracking.topic", xdm["eventType"])
        val la = liveActivity(xdm)
        assertEquals("topic_subscribed", la["event"])
        assertEquals("topicA", la["channelID"])
        assertFalse(la.containsKey("liveActivityID"))
    }

    @Test
    fun `trackTopicSubscribed with notificationId includes liveActivityID`() {
        LiveUpdates.trackTopicSubscribed("topicA", "notif1")
        val la = liveActivity(xdmMap(captureEvent()))
        assertEquals("notif1", la["liveActivityID"])
    }

    @Test
    fun `trackTopicSubscribed payload overload threads payload xdm and notificationId`() {
        val xdm = JSONObject().put("campaignID", "camp9")
        val p = payload(eventType = LiveUpdatePayload.EVENT_TYPE_START, xdm = xdm, notificationId = "notifX")

        LiveUpdates.trackTopicSubscribed("topicA", p)

        val xdmMap = xdmMap(captureEvent())
        assertEquals("camp9", xdmMap["campaignID"])
        assertEquals("notifX", liveActivity(xdmMap)["liveActivityID"])
        assertEquals("topic_subscribed", liveActivity(xdmMap)["event"])
    }

    @Test
    fun `trackTopicUnsubscribed lightweight overload dispatches topic_unsubscribed`() {
        LiveUpdates.trackTopicUnsubscribed("topicB")
        assertEquals("topic_unsubscribed", liveActivity(xdmMap(captureEvent()))["event"])
    }

    @Test
    fun `trackTopicUnsubscribed with notificationId includes liveActivityID`() {
        LiveUpdates.trackTopicUnsubscribed("topicB", "notif2")
        assertEquals("notif2", liveActivity(xdmMap(captureEvent()))["liveActivityID"])
    }

    @Test
    fun `trackTopicUnsubscribed payload overload threads payload xdm and notificationId`() {
        val xdm = JSONObject().put("campaignID", "camp7")
        val p = payload(eventType = LiveUpdatePayload.EVENT_TYPE_END, xdm = xdm, notificationId = "notifY")

        LiveUpdates.trackTopicUnsubscribed("topicB", p)

        val xdmMap = xdmMap(captureEvent())
        assertEquals("camp7", xdmMap["campaignID"])
        assertEquals("notifY", liveActivity(xdmMap)["liveActivityID"])
        assertEquals("topic_unsubscribed", liveActivity(xdmMap)["event"])
    }

    @Test
    fun `dispatchTopicTracking skips dispatch when topic is empty`() {
        LiveUpdates.trackTopicSubscribed("")
        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, never())
    }

    // =====================================================================
    // XDM shape / dataset override / mixin flattening
    // =====================================================================

    @Test
    fun `dispatchLiveUpdateEventTracking skips dispatch for non-canonical event_type`() {
        LiveUpdates.dispatchLiveUpdateEventTracking(mock(Context::class.java), payload(eventType = "unknown"))
        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, never())
    }

    @Test
    fun `dispatchLiveUpdateEventTracking maps start to liveupdate_start`() {
        LiveUpdates.dispatchLiveUpdateEventTracking(
            mock(Context::class.java),
            payload(eventType = LiveUpdatePayload.EVENT_TYPE_START, topicName = "topicA")
        )
        val xdm = xdmMap(captureEvent())
        assertEquals("liveUpdateTracking.received", xdm["eventType"])
        val la = liveActivity(xdm)
        assertEquals("liveupdate_start", la["event"])
        assertEquals("topicA", la["channelID"])
    }

    @Test
    fun `dispatchLiveUpdateEventTracking maps localstart to liveupdate_start`() {
        LiveUpdates.dispatchLiveUpdateEventTracking(
            mock(Context::class.java),
            payload(eventType = LiveUpdatePayload.EVENT_TYPE_LOCAL_START)
        )
        assertEquals("liveupdate_start", liveActivity(xdmMap(captureEvent()))["event"])
    }

    @Test
    fun `dispatchLiveUpdateEventTracking maps update to liveupdate_update`() {
        LiveUpdates.dispatchLiveUpdateEventTracking(
            mock(Context::class.java),
            payload(eventType = LiveUpdatePayload.EVENT_TYPE_UPDATE)
        )
        assertEquals("liveupdate_update", liveActivity(xdmMap(captureEvent()))["event"])
    }

    @Test
    fun `dispatchLiveUpdateEventTracking maps end to liveupdate_end`() {
        LiveUpdates.dispatchLiveUpdateEventTracking(
            mock(Context::class.java),
            payload(eventType = LiveUpdatePayload.EVENT_TYPE_END)
        )
        assertEquals("liveupdate_end", liveActivity(xdmMap(captureEvent()))["event"])
    }

    @Test
    fun `dispatch includes pushNotificationTracking with fcm provider and message id`() {
        LiveUpdates.dispatchLiveUpdateEventTracking(
            mock(Context::class.java),
            payload(eventType = LiveUpdatePayload.EVENT_TYPE_START, notificationId = "notifZ")
        )
        val pnt = pushNotificationTracking(xdmMap(captureEvent()))
        assertEquals("fcm", pnt["pushProvider"])
        assertEquals("notifZ", pnt["pushProviderMessageID"])
    }

    @Test
    fun `dispatch includes default messageProfile channel`() {
        LiveUpdates.dispatchLiveUpdateEventTracking(
            mock(Context::class.java),
            payload(eventType = LiveUpdatePayload.EVENT_TYPE_START)
        )
        val xdm = xdmMap(captureEvent())
        @Suppress("UNCHECKED_CAST")
        val messageProfile = cjm(xdm)["messageProfile"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val channel = messageProfile["channel"] as Map<String, Any?>
        assertEquals("https://ns.adobe.com/xdm/channels/push", channel["_id"])
    }

    @Test
    fun `dispatch includes meta datasetId when configured`() {
        setCachedDatasetId("dataset123")
        LiveUpdates.dispatchLiveUpdateEventTracking(
            mock(Context::class.java),
            payload(eventType = LiveUpdatePayload.EVENT_TYPE_START)
        )
        val event = captureEvent()
        @Suppress("UNCHECKED_CAST")
        val meta = event.eventData!!["meta"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val collect = meta["collect"] as Map<String, Any?>
        assertEquals("dataset123", collect["datasetId"])
    }

    @Test
    fun `dispatch omits meta when no dataset configured`() {
        LiveUpdates.dispatchLiveUpdateEventTracking(
            mock(Context::class.java),
            payload(eventType = LiveUpdatePayload.EVENT_TYPE_START)
        )
        val event = captureEvent()
        assertFalse(event.eventData!!.containsKey("meta"))
    }

    @Test
    fun `incoming xdm with mixins key is flattened to root`() {
        val innerMixins = JSONObject().put("messageExecution", JSONObject().put("messageExecutionID", "exec1"))
        val xdm = JSONObject().put("mixins", innerMixins)
        LiveUpdates.dispatchLiveUpdateEventTracking(
            mock(Context::class.java),
            payload(eventType = LiveUpdatePayload.EVENT_TYPE_START, xdm = xdm)
        )
        val xdmMap = xdmMap(captureEvent())
        @Suppress("UNCHECKED_CAST")
        val messageExecution = xdmMap["messageExecution"] as Map<String, Any?>
        assertEquals("exec1", messageExecution["messageExecutionID"])
        assertFalse(xdmMap.containsKey("mixins"))
    }

    @Test
    fun `incoming xdm with cjm alias key is flattened to root`() {
        val innerCjm = JSONObject().put("campaignID", "camp1")
        val xdm = JSONObject().put("cjm", innerCjm)
        LiveUpdates.dispatchLiveUpdateEventTracking(
            mock(Context::class.java),
            payload(eventType = LiveUpdatePayload.EVENT_TYPE_START, xdm = xdm)
        )
        val xdmMap = xdmMap(captureEvent())
        assertEquals("camp1", xdmMap["campaignID"])
        assertFalse(xdmMap.containsKey("cjm"))
    }

    @Test
    fun `incoming xdm without mixins or cjm merges top-level keys directly`() {
        val xdm = JSONObject().put("customField", "customValue")
        LiveUpdates.dispatchLiveUpdateEventTracking(
            mock(Context::class.java),
            payload(eventType = LiveUpdatePayload.EVENT_TYPE_START, xdm = xdm)
        )
        val xdmMap = xdmMap(captureEvent())
        assertEquals("customValue", xdmMap["customField"])
    }

    @Test
    fun `incoming xdm carrying a pre-built _experience block is preserved and not overwritten`() {
        val preBuiltExperience = JSONObject().put(
            "customerJourneyManagement",
            JSONObject().put(
                "messageProfile",
                JSONObject().put("channel", JSONObject().put("_id", "custom-channel-id"))
            )
        )
        val xdm = JSONObject().put("_experience", preBuiltExperience)

        LiveUpdates.dispatchLiveUpdateEventTracking(
            mock(Context::class.java),
            payload(eventType = LiveUpdatePayload.EVENT_TYPE_START, xdm = xdm)
        )

        val xdmMap = xdmMap(captureEvent())
        val messageProfile = cjm(xdmMap)["messageProfile"] as Map<*, *>
        val channel = messageProfile["channel"] as Map<*, *>
        // The pre-existing channel id from the passthrough xdm wins - it is not clobbered by
        // the SDK's own default push-channel id.
        assertEquals("custom-channel-id", channel["_id"])
        // The liveActivity block the SDK builds is still merged in alongside the preserved cjm.
        assertEquals("liveupdate_start", liveActivity(xdmMap)["event"])
    }

    @Test
    fun `incoming xdm nested arrays and null values are converted to List and null`() {
        val arr = JSONArray().put("a").put(JSONObject.NULL)
        val xdm = JSONObject().put("customField", "v").put("customList", arr)
        LiveUpdates.dispatchLiveUpdateEventTracking(
            mock(Context::class.java),
            payload(eventType = LiveUpdatePayload.EVENT_TYPE_START, xdm = xdm)
        )
        val xdmMap = xdmMap(captureEvent())
        assertEquals(listOf("a", null), xdmMap["customList"])
    }

    // =====================================================================
    // notifyDismissed
    // =====================================================================

    @Test
    fun `notifyDismissed no-ops when no listener registered`() {
        val intent = mock(Intent::class.java)
        LiveUpdates.notifyDismissed(intent)
        // No exception; nothing to assert further.
    }

    @Test
    fun `notifyDismissed no-ops when EXTRA_PAYLOAD missing`() {
        val calls = mutableListOf<String>()
        LiveUpdates.setLiveUpdateListener(recordingListener(calls))
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra(anyString())).thenReturn(null)
        LiveUpdates.notifyDismissed(intent)
        assertTrue(calls.none { it == "dismissed" })
    }

    @Test
    fun `notifyDismissed no-ops when payload is unparseable`() {
        val calls = mutableListOf<String>()
        LiveUpdates.setLiveUpdateListener(recordingListener(calls))
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra(LiveUpdates.EXTRA_PAYLOAD)).thenReturn("{not-json")
        LiveUpdates.notifyDismissed(intent)
        assertTrue(calls.none { it == "dismissed" })
    }

    @Test
    fun `notifyDismissed rehydrates payload and invokes onDismissed`() {
        var received: LiveUpdatePayload? = null
        LiveUpdates.setLiveUpdateListener(object : ILiveUpdateListener {
            override fun onDismissed(payload: LiveUpdatePayload) {
                received = payload
            }
        })
        val p = LiveUpdatePayload.create("id1", "chan", LiveUpdatePayload.EVENT_TYPE_END, "T")
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra(LiveUpdates.EXTRA_PAYLOAD)).thenReturn(p.toEnvelopeJson())

        LiveUpdates.notifyDismissed(intent)

        assertNotNull(received)
        assertEquals("id1", received!!.notificationId)
    }

    @Test
    fun `notifyDismissed catches onDismissed listener exceptions`() {
        LiveUpdates.setLiveUpdateListener(object : ILiveUpdateListener {
            override fun onDismissed(payload: LiveUpdatePayload) {
                throw RuntimeException("boom")
            }
        })
        val p = LiveUpdatePayload.create("id1", "chan", LiveUpdatePayload.EVENT_TYPE_END, "T")
        val intent = mock(Intent::class.java)
        `when`(intent.getStringExtra(LiveUpdates.EXTRA_PAYLOAD)).thenReturn(p.toEnvelopeJson())

        // Should not propagate.
        LiveUpdates.notifyDismissed(intent)
    }

    // =====================================================================
    // Test helpers
    // =====================================================================

    private fun interceptorReturning(result: Boolean): ILiveUpdateInterceptor =
        object : ILiveUpdateInterceptor {
            override fun shouldDisplayLiveUpdate(payload: LiveUpdatePayload) = result
        }

    private fun recordingListener(calls: MutableList<String>): ILiveUpdateListener = object : ILiveUpdateListener {
        override fun onLiveUpdateReceived(payload: LiveUpdatePayload) {
            calls.add("received")
        }

        override fun onStart(payload: LiveUpdatePayload) {
            calls.add("start")
        }

        override fun onUpdate(payload: LiveUpdatePayload) {
            calls.add("update")
        }

        override fun onEnd(payload: LiveUpdatePayload) {
            calls.add("end")
        }
    }

    private fun payload(
        eventType: String = LiveUpdatePayload.EVENT_TYPE_START,
        topicName: String? = null,
        xdm: JSONObject? = null,
        notificationId: String = "notif-default"
    ): LiveUpdatePayload = LiveUpdatePayload.create(
        notificationId = notificationId,
        channelId = "chan",
        eventType = eventType,
        title = "Title",
        topicName = topicName,
        xdm = xdm
    )

    private fun remoteMessageForEnvelope(envelopeJson: String, xdmRaw: String? = null): RemoteMessage {
        val message = mock(RemoteMessage::class.java)
        val data = mutableMapOf("adb_liveupdate_data" to envelopeJson)
        if (xdmRaw != null) {
            data["_xdm"] = xdmRaw
        }
        `when`(message.data).thenReturn(data)
        return message
    }

    private fun captureEvent(): Event {
        val captor = ArgumentCaptor.forClass(Event::class.java)
        mobileCoreMock.verify({ MobileCore.dispatchEvent(captor.capture()) })
        return captor.value
    }

    @Suppress("UNCHECKED_CAST")
    private fun xdmMap(event: Event): Map<String, Any?> = event.eventData!!["xdm"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun cjm(xdm: Map<String, Any?>): Map<String, Any?> =
        (xdm["_experience"] as Map<String, Any?>)["customerJourneyManagement"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun liveActivity(xdm: Map<String, Any?>): Map<String, Any?> =
        cjm(xdm)["pushChannelContext"].let { (it as Map<String, Any?>)["liveActivity"] as Map<String, Any?> }

    @Suppress("UNCHECKED_CAST")
    private fun pushNotificationTracking(xdm: Map<String, Any?>): Map<String, Any?> =
        xdm["pushNotificationTracking"] as Map<String, Any?>

    private fun setCachedDatasetId(value: String?) {
        val field = LiveUpdates::class.java.getDeclaredField("cachedEventDatasetId")
        field.isAccessible = true
        field.set(LiveUpdates, value)
    }
}
