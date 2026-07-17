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

/**
 * App-side gate consulted by the SDK the moment a Live Update push has been parsed, before
 * any rendering, tracking, or listener dispatch happens. Lets the host application veto a
 * Live Update based on its own state.
 *
 * The canonical motivating case: a Live Update the user already dismissed can arrive again
 * (e.g. a later `update` / `end` push for the same activity, or a duplicate). Without a gate
 * the SDK would re-post the chip. An interceptor that remembers dismissed
 * [LiveUpdatePayload.notificationId]s can return `false` for those and keep them off-screen.
 *
 * Register via [LiveUpdates.setLiveUpdateInterceptor]. Only one interceptor is active at a
 * time; registering a new one replaces the previous. When no interceptor is registered the
 * SDK proceeds as normal (equivalent to always returning `true`).
 *
 * **Scope**: honored on the SDK-rendered receive paths (auto and mixed integration, via
 * [LiveUpdateHandlerImpl]). It does not gate app-owned full-manual rendering, where the app
 * already controls whether to display.
 *
 * **Threading**: called on whatever thread the SDK is processing the push on - typically the
 * FCM background thread. Keep the decision fast and side-effect-light; do not block.
 */
interface ILiveUpdateInterceptor {

    /**
     * Decides whether the SDK should proceed with this Live Update.
     *
     * @param payload the parsed Live Update the SDK is about to process.
     * @return `true` to let the SDK render, track, and dispatch listener callbacks as usual;
     *   `false` to drop the Live Update entirely (no chip, no tracking, no listener callback).
     */
    fun shouldDisplayLiveUpdate(payload: LiveUpdatePayload): Boolean
}
