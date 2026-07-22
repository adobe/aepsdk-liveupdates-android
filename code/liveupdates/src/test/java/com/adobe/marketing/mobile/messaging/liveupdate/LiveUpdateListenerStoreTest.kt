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

import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LiveUpdateListenerStoreTest {

    @After
    fun tearDown() {
        LiveUpdateListenerStore.setListener(null)
    }

    @Test
    fun `getListener returns null when none set`() {
        assertNull(LiveUpdateListenerStore.getListener())
    }

    @Test
    fun `setListener and getListener round trip`() {
        val listener = object : ILiveUpdateListener {}
        LiveUpdateListenerStore.setListener(listener)
        assertSame(listener, LiveUpdateListenerStore.getListener())
    }

    @Test
    fun `setListener replaces the previous listener`() {
        val first = object : ILiveUpdateListener {}
        val second = object : ILiveUpdateListener {}
        LiveUpdateListenerStore.setListener(first)
        LiveUpdateListenerStore.setListener(second)
        assertSame(second, LiveUpdateListenerStore.getListener())
    }

    @Test
    fun `setListener null clears the listener`() {
        LiveUpdateListenerStore.setListener(object : ILiveUpdateListener {})
        LiveUpdateListenerStore.setListener(null)
        assertNull(LiveUpdateListenerStore.getListener())
    }
}
