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

package com.adobe.marketing.mobile.messaging.liveupdate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;

import android.content.Intent;
import com.adobe.marketing.mobile.MobileCore;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

/**
 * {@code @JvmOverloads} generates one extra Java-callable overload per optional trailing
 * parameter (e.g. {@link LiveUpdatePayload#create}, {@link LiveUpdates#trackTopicSubscribed},
 * {@link LiveUpdates#handleNotificationResponse}). Kotlin call sites never dispatch to these
 * generated overloads - the Kotlin compiler always calls the single master method with
 * defaults substituted inline - so a Kotlin-only test suite leaves the generated overloads
 * themselves unexecuted. This small Java test file calls those shorter-arity overloads
 * directly, the way a Java consumer of the SDK would, to close that coverage gap.
 */
public class JvmOverloadsCoverageTest {

    @Test
    public void create_withOnlyRequiredArgs_fromJavaOverload() {
        LiveUpdatePayload payload =
                LiveUpdatePayload.create("id1", "chan", LiveUpdatePayload.EVENT_TYPE_START, "Title");
        assertNotNull(payload);
        assertEquals("id1", payload.getNotificationId());
        assertEquals("Title", payload.getTitle());
    }

    @Test
    public void trackTopicSubscribed_javaOverload_withoutNotificationId() {
        try (MockedStatic<MobileCore> mobileCore = mockStatic(MobileCore.class)) {
            LiveUpdates.trackTopicSubscribed("topicA");
            mobileCore.verify(() -> MobileCore.dispatchEvent(any()));
        }
    }

    @Test
    public void trackTopicUnsubscribed_javaOverload_withoutNotificationId() {
        try (MockedStatic<MobileCore> mobileCore = mockStatic(MobileCore.class)) {
            LiveUpdates.trackTopicUnsubscribed("topicB");
            mobileCore.verify(() -> MobileCore.dispatchEvent(any()));
        }
    }

    @Test
    public void handleNotificationResponse_javaOverload_withoutCustomActionId() {
        Intent intent = Mockito.mock(Intent.class);
        Mockito.when(intent.getStringExtra(LiveUpdates.EXTRA_NOTIFICATION_ID)).thenReturn("id1");
        try (MockedStatic<MobileCore> mobileCore = mockStatic(MobileCore.class)) {
            LiveUpdates.handleNotificationResponse(intent, true);
            mobileCore.verify(() -> MobileCore.dispatchEvent(any()));
        }
    }
}
