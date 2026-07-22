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

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import com.adobe.marketing.mobile.Event
import com.adobe.marketing.mobile.MobileCore
import org.junit.After
import org.junit.Assert.assertEquals
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
    }

    private fun intentFor(actionUri: String? = null, actionId: String? = null): Intent {
        val intent = Intent(RuntimeEnvironment.getApplication(), LiveUpdateTrackerActivity::class.java)
        intent.putExtra(LiveUpdates.EXTRA_NOTIFICATION_ID, "id1")
        intent.putExtra(LiveUpdates.EXTRA_EVENT_TYPE, LiveUpdatePayload.EVENT_TYPE_START)
        actionUri?.let { intent.putExtra(LiveUpdates.EXTRA_ACTION_URI, it) }
        actionId?.let { intent.putExtra(LiveUpdates.EXTRA_ACTION_ID, it) }
        return intent
    }

    private fun capturedXdm(): Map<String, Any?> {
        val captor = ArgumentCaptor.forClass(Event::class.java)
        mobileCoreMock.verify({ MobileCore.dispatchEvent(captor.capture()) })
        @Suppress("UNCHECKED_CAST")
        return captor.value.eventData!!["xdm"] as Map<String, Any?>
    }

    @Test
    fun `onCreate with action uri dispatches tap tracking and opens the uri`() {
        val intent = intentFor(actionUri = "https://example.com/deeplink")

        val activity = Robolectric.buildActivity(LiveUpdateTrackerActivity::class.java, intent)
            .create().get()

        assertEquals("liveUpdateTracking.applicationOpened", capturedXdm()["eventType"])
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.com/deeplink", started.data.toString())
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `onCreate without action uri opens the application`() {
        val activity = Robolectric.buildActivity(LiveUpdateTrackerActivity::class.java, intentFor())
            .create().get()

        assertEquals("liveUpdateTracking.applicationOpened", capturedXdm()["eventType"])
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `onCreate without action uri and no foreground activity launches the registered launcher activity`() {
        val app = RuntimeEnvironment.getApplication()
        val launcherComponent = ComponentName(app.packageName, "com.example.MainActivity")
        val launcherFilter = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        shadowOf(app.packageManager).addActivityIfNotPresent(launcherComponent)
        shadowOf(app.packageManager).addIntentFilterForActivity(launcherComponent, launcherFilter)

        val activity = Robolectric.buildActivity(LiveUpdateTrackerActivity::class.java, intentFor())
            .create().get()

        val started = shadowOf(activity).nextStartedActivity
        assertEquals(launcherComponent.className, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }

    @Test
    fun `onCreate with action id dispatches customAction tracking`() {
        Robolectric.buildActivity(LiveUpdateTrackerActivity::class.java, intentFor(actionId = "Snooze"))
            .create()

        val xdm = capturedXdm()
        assertEquals("liveUpdateTracking.customAction", xdm["eventType"])
        @Suppress("UNCHECKED_CAST")
        val pushNotificationTracking = xdm["pushNotificationTracking"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val customAction = pushNotificationTracking["customAction"] as Map<String, Any?>
        assertEquals("Snooze", customAction["actionID"])
    }

    @Test
    fun `onNewIntent re-processes the incoming intent and dispatches tracking again`() {
        val controller = Robolectric.buildActivity(LiveUpdateTrackerActivity::class.java, intentFor())
            .create()
        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, times(1))

        controller.newIntent(intentFor(actionId = "Snooze"))

        mobileCoreMock.verify({ MobileCore.dispatchEvent(any()) }, times(2))
    }

    @Test
    fun `onCreate with a URI no activity can resolve logs a warning and does not crash`() {
        val intent = intentFor(actionUri = "no-such-scheme://nowhere")

        val activity = Robolectric.buildActivity(LiveUpdateTrackerActivity::class.java, intent)
            .create().get()

        // Should not throw; the tracker activity still finishes.
        assertTrue(activity.isFinishing)
    }
}
