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
 * App-side hook fired when the SDK observes Live Update activity - a push being received,
 * or the user dismissing the chip.
 *
 * Register via [LiveUpdates.setLiveUpdateListener]. Implement only the methods you care
 * about; all have default empty bodies.
 *
 * **Invocation order (receive)**: [onLiveUpdateReceived] always fires first as a generic
 * hook, then exactly one of [onStart] / [onUpdate] / [onEnd] fires based on the envelope's
 * `event_type`. If `event_type` is absent or not one of the three canonical values, only
 * [onLiveUpdateReceived] fires (no specific Live Update event method is called).
 *
 * [onDismissed] fires on a separate, later interaction (a chip swipe) - see its own note.
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

    /**
     * Fired when the user dismisses (swipes away) a Live Update chip.
     *
     * Unlike the receive callbacks above, this fires on a later, separate interaction - the
     * app process may have been killed since the chip was posted and cold-started just to
     * deliver this dismiss. The [payload] is re-hydrated from the data serialized onto the
     * chip's dismiss intent at post time, so it reflects the payload of the **most recently
     * posted** version of the chip, not necessarily live app state. For that reason, treat
     * [LiveUpdatePayload.notificationId] as the reliable key and look up any current state
     * from your own store.
     *
     * Because this can run in a freshly-started process, the listener is only invoked if one
     * is registered by the time the dismiss is handled - register it in `Application.onCreate`
     * (as the sample does) so it survives process death.
     */
    fun onDismissed(payload: LiveUpdatePayload) {}

    /**
     * Fired when the user taps the Live Update chip body (an action-button click is a
     * separate interaction and does not trigger this). Like [onDismissed], this fires from
     * the SDK's tracker Activity on a later interaction, so the [payload] is re-hydrated from
     * the data serialized onto the chip's tap intent at post time and reflects the most
     * recently posted version of the chip - treat [LiveUpdatePayload.notificationId] as the
     * reliable key and look up current state from your own store.
     *
     * Because this can run in a freshly-started process, the listener is only invoked if one
     * is registered by the time the tap is handled - register it in `Application.onCreate`
     * (as the sample does) so it survives process death.
     */
    fun onClick(payload: LiveUpdatePayload) {}
}
