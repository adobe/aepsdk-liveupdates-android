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
 * App-side hook fired when the SDK observes a Live Update push being received.
 *
 * Register via [LiveUpdates.setLiveUpdateListener]. Implement only the methods you care
 * about; all four have default empty bodies.
 *
 * **Invocation order**: [onLiveUpdateReceived] always fires first as a generic hook, then
 * exactly one of [onStart] / [onUpdate] / [onEnd] fires based on the envelope's `event_type`.
 * If `event_type` is absent or not one of the three canonical values, only [onLiveUpdateReceived]
 * fires (no specific Live Update event method is called).
 *
 * **Threading**: callbacks fire on whatever thread the SDK is processing the push on
 * - typically the FCM background thread for Patterns 1 and 2 (auto / mixed), and whatever
 * thread the app called [LiveUpdates.trackLiveUpdateEvent] on for Pattern 3 (manual).
 * Do not perform long-running work; marshal to your own executor if needed.
 */
interface ILiveUpdateListener {

    /**
     * Generic hook fired once per Live Update push, regardless of `event_type`. Useful
     * when the app wants a single entry point for any Live Update receipt.
     */
    fun onLiveUpdateReceived(payload: LiveUpdatePayload) {}

    /** Fired when `event_type == "start"`. */
    fun onStart(payload: LiveUpdatePayload) {}

    /** Fired when `event_type == "update"`. */
    fun onUpdate(payload: LiveUpdatePayload) {}

    /** Fired when `event_type == "end"`. */
    fun onEnd(payload: LiveUpdatePayload) {}
}
