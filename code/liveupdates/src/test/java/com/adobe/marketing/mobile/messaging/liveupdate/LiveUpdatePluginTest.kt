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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import com.adobe.marketing.mobile.Event
import com.adobe.marketing.mobile.EventSource
import com.adobe.marketing.mobile.EventType
import com.adobe.marketing.mobile.MobileCore
import com.google.firebase.messaging.RemoteMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.MockedStatic
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.never
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LiveUpdatePluginTest {

    private lateinit var mobileCoreMock: MockedStatic<MobileCore>
    private val context = RuntimeEnvironment.getApplication()

    // Payloads posted through postLiveUpdate() now run through NotificationHistoryManager's
    // staleness check (payloads older than 28 days are dropped before rendering), so tests
    // that exercise the full post path need a "now"-ish timestamp, not an arbitrary constant.
    private val nowSeconds = System.currentTimeMillis() / 1000

    @Before
    fun setUp() {
        mobileCoreMock = mockStatic(MobileCore::class.java)
        mobileCoreMock.`when`<Int> { MobileCore.getSmallIconResourceID() }
            .thenReturn(android.R.drawable.ic_dialog_info)
        LiveUpdates.setLiveUpdateListener(null)
        LiveUpdates.setLiveUpdateInterceptor(null)
    }

    @After
    fun tearDown() {
        LiveUpdates.setLiveUpdateListener(null)
        LiveUpdates.setLiveUpdateInterceptor(null)
        mobileCoreMock.close()
    }

    private fun notificationManager(): NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun handler(style: NotificationCompat.Style? = NotificationCompat.BigTextStyle()) =
        LiveUpdatePlugin(ILiveUpdateStyleProvider { style })

    private fun interceptorReturning(result: Boolean): ILiveUpdateInterceptor =
        object : ILiveUpdateInterceptor {
            override fun shouldDisplayLiveUpdate(payload: LiveUpdatePayload) = result
        }

    private fun remoteMessage(payload: LiveUpdatePayload): RemoteMessage {
        val message = mock(RemoteMessage::class.java)
        val data = mutableMapOf("adb_liveupdate_data" to payload.toEnvelopeJson())
        payload.xdm?.let { data["_xdm"] = it.toString() }
        `when`(message.data).thenReturn(data)
        return message
    }

    @Test
    fun `handleLiveUpdatePush drops the push when parse fails`() {
        val message = mock(RemoteMessage::class.java)
        `when`(message.data).thenReturn(emptyMap())

        handler().handleLiveUpdatePush(context, message)

        assertTrue(shadowOf(notificationManager()).allNotifications.isEmpty())
        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, never())
    }

    @Test
    fun `handleLiveUpdatePush drops the push and reports render_error app_discarded when interceptor vetoes`() {
        LiveUpdates.setLiveUpdateInterceptor(interceptorReturning(false))
        val payload = LiveUpdatePayload.create(
            "id1", "chan", LiveUpdatePayload.EVENT_TYPE_START, "T", nowSeconds, topicName = "topicA"
        )

        handler().handleLiveUpdatePush(context, remoteMessage(payload))

        assertTrue(shadowOf(notificationManager()).allNotifications.isEmpty())
        // Exactly one Event Hub event: the render_error diagnostic. No render / tracking follows.
        val events = capturedEvents()
        assertEquals(1, events.size)
        val error = events[0]
        assertEquals(EventType.MESSAGING, error.type)
        assertEquals(EventSource.ERROR_RESPONSE_CONTENT, error.source)
        assertEquals("liveUpdateTracking.renderError", categoryOf(error))
        assertEquals(LiveUpdates.ERROR_SUBCATEGORY_APP_DISCARDED, subcategoryOf(error))
        assertEquals("id1", liveActivityIdOf(error))
        assertEquals("topicA", channelIdOf(error))
    }

    @Test
    fun `handleLiveUpdatePush proceeds and posts when interceptor allows`() {
        LiveUpdates.setLiveUpdateInterceptor(interceptorReturning(true))
        val payload = LiveUpdatePayload.create("id1", "chan", LiveUpdatePayload.EVENT_TYPE_START, "T", nowSeconds)

        handler().handleLiveUpdatePush(context, remoteMessage(payload))

        assertEquals(1, shadowOf(notificationManager()).allNotifications.size)
    }

    @Test
    fun `postLiveUpdate still posts without a style and reports render_error style_null when style provider returns null`() {
        val payload = LiveUpdatePayload.create("id1", "chan", LiveUpdatePayload.EVENT_TYPE_START, "T", nowSeconds)

        handler(style = null).postLiveUpdate(context, payload)

        // The notification still posts, just without a promoted style.
        assertEquals(1, shadowOf(notificationManager()).allNotifications.size)
        val error = capturedEvents().single {
            subcategoryOf(it) == LiveUpdates.ERROR_SUBCATEGORY_STYLE_NULL
        }
        assertEquals(EventType.MESSAGING, error.type)
        assertEquals(EventSource.ERROR_RESPONSE_CONTENT, error.source)
        assertEquals("liveUpdateTracking.renderError", categoryOf(error))
    }

    @Test
    fun `postLiveUpdate drops the push when the timestamp is stale`() {
        val staleTimestamp = nowSeconds - TimeUnit.DAYS.toSeconds(29)
        val payload = LiveUpdatePayload.create("id1", "chan", LiveUpdatePayload.EVENT_TYPE_START, "T", staleTimestamp)

        handler().postLiveUpdate(context, payload)

        assertTrue(shadowOf(notificationManager()).allNotifications.isEmpty())
        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, never())
    }

    @Test
    fun `postLiveUpdate posts an ongoing notification and dispatches tracking + listener`() {
        val calls = mutableListOf<String>()
        LiveUpdates.setLiveUpdateListener(object : ILiveUpdateListener {
            override fun onLiveUpdateReceived(payload: LiveUpdatePayload) {
                calls.add("received")
            }

            override fun onStart(payload: LiveUpdatePayload) {
                calls.add("start")
            }
        })
        val payload = LiveUpdatePayload.create(
            notificationId = "id1",
            channelId = "chan",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "Title",
            timestamp = nowSeconds,
            body = "Body",
            criticalText = "Crit",
            whenSeconds = 1234L
        )

        handler().postLiveUpdate(context, payload)

        val posted = shadowOf(notificationManager()).allNotifications
        assertEquals(1, posted.size)
        val notification = posted[0]
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(listOf("received", "start"), calls)
        // At least the receive tracking event dispatches. On this API 33 test target an
        // incompatible (device_api_below_36) diagnostic also fires; asserted separately below.
        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, atLeastOnce())
    }

    @Test
    fun `postLiveUpdate reports incompatible device_api_below_36 on a pre-36 device`() {
        // Test target runs at API 33 (see class @Config), so promotion to a chip is impossible.
        val payload = LiveUpdatePayload.create(
            "id1", "chan", LiveUpdatePayload.EVENT_TYPE_START, "T", nowSeconds, topicName = "topicA"
        )

        handler().postLiveUpdate(context, payload)

        // The notification still posts (as a normal ongoing notification).
        assertEquals(1, shadowOf(notificationManager()).allNotifications.size)
        val incompatible = capturedEvents().single {
            categoryOf(it) == "liveUpdateTracking.incompatible"
        }
        assertEquals(EventType.MESSAGING, incompatible.type)
        assertEquals(EventSource.ERROR_RESPONSE_CONTENT, incompatible.source)
        assertEquals(
            LiveUpdates.INCOMPATIBLE_SUBCATEGORY_DEVICE_API_BELOW_36,
            subcategoryOf(incompatible)
        )
        assertEquals("id1", liveActivityIdOf(incompatible))
        assertEquals("topicA", channelIdOf(incompatible))
    }

    @Test
    fun `postLiveUpdate reports render_error notification_permission_missing when notifications are disabled`() {
        shadowOf(notificationManager()).setNotificationsEnabled(false)
        val payload = LiveUpdatePayload.create("id1", "chan", LiveUpdatePayload.EVENT_TYPE_START, "T", nowSeconds)

        handler().postLiveUpdate(context, payload)

        val permissionError = capturedEvents().single {
            subcategoryOf(it) == LiveUpdates.ERROR_SUBCATEGORY_NOTIFICATION_PERMISSION_MISSING
        }
        assertEquals(EventType.MESSAGING, permissionError.type)
        assertEquals(EventSource.ERROR_RESPONSE_CONTENT, permissionError.source)
        assertEquals("liveUpdateTracking.renderError", categoryOf(permissionError))
    }

    @Test
    fun `postLiveUpdate applies dismiss_after timeout only for end event`() {
        val payload = LiveUpdatePayload.create(
            notificationId = "id1",
            channelId = "chan",
            eventType = LiveUpdatePayload.EVENT_TYPE_END,
            title = "Title",
            timestamp = nowSeconds,
            dismissAfterSeconds = 5L
        )

        handler().postLiveUpdate(context, payload)

        val notification = shadowOf(notificationManager()).allNotifications[0]
        assertEquals(5000L, notification.timeoutAfter)
    }

    @Test
    fun `postLiveUpdate ignores non-positive dismiss_after for end event`() {
        val payload = LiveUpdatePayload.create(
            notificationId = "id1",
            channelId = "chan",
            eventType = LiveUpdatePayload.EVENT_TYPE_END,
            title = "Title",
            timestamp = nowSeconds,
            dismissAfterSeconds = 0L
        )

        handler().postLiveUpdate(context, payload)

        val notification = shadowOf(notificationManager()).allNotifications[0]
        assertEquals(0L, notification.timeoutAfter)
    }

    @Test
    fun `postLiveUpdate does not apply timeout for non-end events even with dismiss_after`() {
        val payload = LiveUpdatePayload.create(
            notificationId = "id1",
            channelId = "chan",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "Title",
            timestamp = nowSeconds,
            dismissAfterSeconds = 5L
        )

        handler().postLiveUpdate(context, payload)

        val notification = shadowOf(notificationManager()).allNotifications[0]
        assertEquals(0L, notification.timeoutAfter)
    }

    @Test
    fun `postLiveUpdate never adds notification action buttons`() {
        val payload = LiveUpdatePayload.create("id1", "chan", LiveUpdatePayload.EVENT_TYPE_START, "T", nowSeconds)

        handler().postLiveUpdate(context, payload)

        val notification = shadowOf(notificationManager()).allNotifications[0]
        assertTrue(notification.actions == null || notification.actions.isEmpty())
    }

    @Test
    fun `postLiveUpdate creates the channel with high importance when not pre-registered`() {
        val payload = LiveUpdatePayload.create("id1", "chan-new", LiveUpdatePayload.EVENT_TYPE_START, "T", nowSeconds)

        handler().postLiveUpdate(context, payload)

        val channel = notificationManager().getNotificationChannel("chan-new")
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
    }

    @Test
    fun `postLiveUpdate leaves a pre-existing channel untouched`() {
        val nm = notificationManager()
        nm.createNotificationChannel(
            NotificationChannel("chan-existing", "Existing", NotificationManager.IMPORTANCE_LOW)
        )
        val payload = LiveUpdatePayload.create("id1", "chan-existing", LiveUpdatePayload.EVENT_TYPE_START, "T", nowSeconds)

        handler().postLiveUpdate(context, payload)

        assertEquals(NotificationManager.IMPORTANCE_LOW, nm.getNotificationChannel("chan-existing").importance)
    }

    @Suppress("DEPRECATION")
    @Test
    fun `postLiveUpdate maps every known priority string and defaults unknown ones`() {
        val cases = mapOf(
            "PRIORITY_MAX" to NotificationCompat.PRIORITY_MAX,
            "PRIORITY_HIGH" to NotificationCompat.PRIORITY_HIGH,
            "PRIORITY_LOW" to NotificationCompat.PRIORITY_LOW,
            "PRIORITY_MIN" to NotificationCompat.PRIORITY_MIN,
            "not_a_real_priority" to NotificationCompat.PRIORITY_DEFAULT,
            null to NotificationCompat.PRIORITY_DEFAULT
        )
        cases.entries.forEachIndexed { index, (priority, expected) ->
            val payload = LiveUpdatePayload.create(
                notificationId = "id-priority-$index",
                channelId = "chan",
                eventType = LiveUpdatePayload.EVENT_TYPE_START,
                title = "Title",
                timestamp = nowSeconds,
                priority = priority
            )
            handler().postLiveUpdate(context, payload)
            val notification = shadowOf(notificationManager())
                .getNotification("id-priority-$index".hashCode())
            assertEquals("priority='$priority'", expected, notification.priority)
        }
    }

    @Test
    fun `postLiveUpdate resolves the payload-supplied small icon when it exists as a drawable`() {
        val payload = LiveUpdatePayload.create(
            notificationId = "id1",
            channelId = "chan",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "T",
            timestamp = nowSeconds,
            smallIcon = "test_liveupdate_icon"
        )

        handler().postLiveUpdate(context, payload)

        val notification = shadowOf(notificationManager()).allNotifications[0]
        val expectedIcon = context.resources.getIdentifier("test_liveupdate_icon", "drawable", context.packageName)
        assertTrue(expectedIcon > 0)
        assertEquals(expectedIcon, notification.icon)
        // MobileCore.getSmallIconResourceID() should not even be consulted once the payload
        // icon resolves.
        mobileCoreMock.verify({ MobileCore.getSmallIconResourceID() }, never())
    }

    @Test
    fun `postLiveUpdate falls back to MobileCore small icon when payload icon does not resolve`() {
        val payload = LiveUpdatePayload.create(
            notificationId = "id1",
            channelId = "chan",
            eventType = LiveUpdatePayload.EVENT_TYPE_START,
            title = "T",
            timestamp = nowSeconds,
            smallIcon = "no_such_drawable_exists"
        )

        handler().postLiveUpdate(context, payload)

        val notification = shadowOf(notificationManager()).allNotifications[0]
        assertEquals(android.R.drawable.ic_dialog_info, notification.icon)
    }

    @Config(sdk = [21])
    @Test
    fun `ensureChannelExists is a no-op below API 26`() {
        val payload = LiveUpdatePayload.create("id1", "chan-old", LiveUpdatePayload.EVENT_TYPE_START, "T", nowSeconds)

        // Should not throw even though NotificationManager#createNotificationChannel doesn't
        // exist pre-O; postLiveUpdate should still post the notification normally.
        handler().postLiveUpdate(context, payload)

        // Use the pre-API-23 getSystemService(String) overload directly - the
        // getSystemService(Class) overload used by notificationManager() above isn't
        // available on the framework jar for API 21.
        @Suppress("DEPRECATION")
        val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as NotificationManager
        assertEquals(1, shadowOf(nm).allNotifications.size)
    }

    // ---- Event Hub error-event capture helpers ----

    /** All events passed to [MobileCore.dispatchEvent] during the test, in dispatch order. */
    private fun capturedEvents(): List<Event> {
        val captor = ArgumentCaptor.forClass(Event::class.java)
        mobileCoreMock.verify({ MobileCore.dispatchEvent(captor.capture()) }, atLeastOnce())
        return captor.allValues
    }

    @Suppress("UNCHECKED_CAST")
    private fun xdmOf(event: Event): Map<String, Any?> =
        event.eventData!!["xdm"] as Map<String, Any?>

    private fun categoryOf(event: Event): String? = xdmOf(event)["eventType"] as? String

    @Suppress("UNCHECKED_CAST")
    private fun liveActivityOf(event: Event): Map<String, Any?> {
        val experience = xdmOf(event)["_experience"] as Map<String, Any?>
        val cjm = experience["customerJourneyManagement"] as Map<String, Any?>
        val pushChannelContext = cjm["pushChannelContext"] as Map<String, Any?>
        return pushChannelContext["liveActivity"] as Map<String, Any?>
    }

    private fun subcategoryOf(event: Event): String? = liveActivityOf(event)["event"] as? String
    private fun liveActivityIdOf(event: Event): String? = liveActivityOf(event)["liveActivityID"] as? String
    private fun channelIdOf(event: Event): String? = liveActivityOf(event)["channelID"] as? String
}
