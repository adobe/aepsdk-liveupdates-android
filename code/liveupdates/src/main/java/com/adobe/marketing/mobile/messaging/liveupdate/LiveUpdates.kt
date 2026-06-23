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
import com.adobe.marketing.mobile.AdobeCallback
import com.adobe.marketing.mobile.Event
import com.adobe.marketing.mobile.EventSource
import com.adobe.marketing.mobile.EventType
import com.adobe.marketing.mobile.Messaging
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.services.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.RemoteMessage

/**
 * Public facade for the Live Updates SDK.
 *
 * Not a registered MobileCore Extension. The SDK runs as a plain class library: it dispatches
 * outbound events via [MobileCore.dispatchEvent] but does not subscribe to the Event Hub for
 * inbound events. See the design proposal wiki for the rationale.
 *
 * Surface:
 *  - [handleLiveUpdatePush]: Pattern 2 (mixed) entry point for apps with their own [com.google.firebase.messaging.FirebaseMessagingService].
 *  - [setLiveUpdateListener] / [getLiveUpdateListener]: register a hook for start/update/end Live Update events.
 *  - [trackLiveUpdateEvent]: Pattern 3 (manual) entry point that fires Live Update event tracking + listener invocation when the app builds and posts the notification itself.
 *  - [subscribeToTopic] / [unsubscribeFromTopic] / [getSubscribedTopics]: FCM topic subscription helpers for the broadcast use case.
 */
object LiveUpdates {

    private const val SELF_TAG = "LiveUpdates"
    private const val EXTENSION_VERSION = "1.0.0"

    // Event dispatch constants - shape mirrors Messaging's push tracking event.
    private const val EVENT_NAME_LIVE_UPDATE_TRACKING = "Live Update Event Tracking"
    private const val EVENT_DATA_KEY_XDM = "xdm"
    private const val EVENT_DATA_KEY_DATA = "data"
    private const val DATA_KEY_LIVE_UPDATE_EVENT = "liveUpdateEvent"
    private const val DATA_KEY_NOTIFICATION_ID = "notificationId"
    private const val DATA_KEY_PUSH_DATA = "pushData"

    /** Returns the SDK version string. */
    @JvmStatic
    fun extensionVersion(): String = EXTENSION_VERSION

    // ---------- Pattern 2: mixed-mode entry point ----------

    /**
     * Handles a [RemoteMessage] as a Live Update push. Use this from inside a custom
     * [com.google.firebase.messaging.FirebaseMessagingService.onMessageReceived] when the app
     * wants to route Live Updates through the SDK while keeping its own service for the rest
     * of its push traffic (Pattern 2 - mixed mode).
     *
     * @return `true` if [message] is a Live Update and was either dispatched to the
     *   registered handler or dropped because no handler is registered. The caller should
     *   stop processing. `false` if [message] is not a Live Update (caller should try its
     *   other handlers, e.g. `MessagingService.handleRemoteMessage`).
     */
    @JvmStatic
    fun handleLiveUpdatePush(context: Context, message: RemoteMessage): Boolean {
        if (!LiveUpdatePayload.isLiveUpdate(message)) {
            return false
        }
        val handler = Messaging.getLiveUpdateHandler()
        if (handler == null) {
            Log.warning(
                SELF_TAG, SELF_TAG,
                "Received a Live Update push but no ILiveUpdateHandler is registered. " +
                    "Dropping. Register a handler via Messaging.setLiveUpdateHandler(...)."
            )
            return true
        }
        handler.handleLiveUpdatePush(context, message)
        return true
    }

    // ---------- Listener registration ----------

    /**
     * Registers an [ILiveUpdateListener] to receive callbacks when a Live Update push is
     * processed. Only one listener can be active at a time; setting a new listener replaces
     * the previous one. Pass `null` to clear.
     */
    @JvmStatic
    fun setLiveUpdateListener(listener: ILiveUpdateListener?) {
        LiveUpdateListenerStore.setListener(listener)
    }

    /** Returns the currently-registered [ILiveUpdateListener], or `null` if none. */
    @JvmStatic
    fun getLiveUpdateListener(): ILiveUpdateListener? = LiveUpdateListenerStore.getListener()

    // ---------- Pattern 3: manual Live Update event tracking ----------

    /**
     * Fires the Live Update event tracking dispatch for the push carried in [message] and
     * invokes the registered [ILiveUpdateListener] (if any). Use when the app builds and
     * posts the notification itself (Pattern 3 - manual mode) but still wants Live Update
     * event reporting in AJO and/or local lifecycle callbacks.
     *
     * No-ops with a debug log if:
     *  - the payload fails to parse (any required field missing)
     *  - `event_type` is not one of `start` / `update` / `end` (still fires `onLiveUpdateReceived`)
     */
    @JvmStatic
    fun trackLiveUpdateEvent(context: Context, message: RemoteMessage) {
        val payload = LiveUpdatePayload.parse(message)
        if (payload == null) {
            Log.warning(
                SELF_TAG, SELF_TAG,
                "trackLiveUpdateEvent: failed to parse payload; skipping tracking + listener dispatch."
            )
            return
        }
        dispatchLiveUpdateEventTracking(context, payload)
        invokeListener(payload)
    }

    // ---------- Topic subscribe / unsubscribe (broadcast use case) ----------

    /**
     * Subscribes the device to an FCM topic so it can receive broadcast Live Updates. Thin
     * wrapper around [FirebaseMessaging.subscribeToTopic]. Persists the subscription in
     * SharedPreferences so [getSubscribedTopics] returns the correct set across launches.
     *
     * @param topic the FCM topic name (no `/topics/` prefix)
     * @param callback invoked with `true` on success, `false` on FCM failure; may be `null`
     */
    @JvmStatic
    @JvmOverloads
    fun subscribeToTopic(context: Context, topic: String, callback: AdobeCallback<Boolean>? = null) {
        FirebaseMessaging.getInstance().subscribeToTopic(topic).addOnCompleteListener { task ->
            val success = task.isSuccessful
            if (success) {
                LiveUpdateTopicStore.addTopic(context, topic)
            } else {
                Log.warning(
                    SELF_TAG, SELF_TAG,
                    "subscribeToTopic($topic) failed: ${task.exception?.localizedMessage}"
                )
            }
            callback?.call(success)
        }
    }

    /**
     * Unsubscribes the device from an FCM topic. Thin wrapper around
     * [FirebaseMessaging.unsubscribeFromTopic]. Removes the topic from the persisted set.
     *
     * @param topic the FCM topic name (no `/topics/` prefix)
     * @param callback invoked with `true` on success, `false` on FCM failure; may be `null`
     */
    @JvmStatic
    @JvmOverloads
    fun unsubscribeFromTopic(context: Context, topic: String, callback: AdobeCallback<Boolean>? = null) {
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic).addOnCompleteListener { task ->
            val success = task.isSuccessful
            if (success) {
                LiveUpdateTopicStore.removeTopic(context, topic)
            } else {
                Log.warning(
                    SELF_TAG, SELF_TAG,
                    "unsubscribeFromTopic($topic) failed: ${task.exception?.localizedMessage}"
                )
            }
            callback?.call(success)
        }
    }

    /**
     * Returns the set of FCM topic names this device is currently subscribed to via the
     * Live Updates SDK. Reflects only subscriptions made through [subscribeToTopic]; topics
     * subscribed via direct [FirebaseMessaging] calls or the IID server-side endpoint will
     * not appear here.
     */
    @JvmStatic
    fun getSubscribedTopics(context: Context): Set<String> =
        LiveUpdateTopicStore.getAllTopics(context)

    // ---------- internal helpers (visible to LiveUpdateHandlerImpl) ----------

    /**
     * Dispatches a `MobileCore.dispatchEvent` carrying the Live Update event marker, the
     * parsed AJO XDM block (with messageExecutionID / campaignID etc. preserved), and the
     * raw push data. Edge picks this up and forwards to AJO. Skips dispatch (with a debug
     * log) if `event_type` is not one of `start` / `update` / `end`.
     *
     * Event data shape:
     * ```
     * {
     *   "xdm":  { ... parsed _xdm passthrough from the FCM data map ... },
     *   "data": {
     *     "liveUpdateEvent": "start" | "update" | "end",
     *     "notificationId":  "...",
     *     "pushData":        { ... raw FCM data map ... }
     *   }
     * }
     * ```
     */
    internal fun dispatchLiveUpdateEventTracking(context: Context, payload: LiveUpdatePayload) {
        val eventType = payload.eventType
        val isCanonical = eventType == LiveUpdatePayload.EVENT_TYPE_START ||
            eventType == LiveUpdatePayload.EVENT_TYPE_UPDATE ||
            eventType == LiveUpdatePayload.EVENT_TYPE_END
        if (!isCanonical) {
            Log.debug(
                SELF_TAG, SELF_TAG,
                "Skipping Live Update event tracking dispatch: event_type='$eventType' is not start/update/end."
            )
            return
        }
        val xdmMap = payload.xdm?.let { jsonObjectToMap(it) } ?: emptyMap<String, Any?>()
        val dataMap = mapOf<String, Any?>(
            DATA_KEY_LIVE_UPDATE_EVENT to eventType,
            DATA_KEY_NOTIFICATION_ID to payload.notificationId,
            DATA_KEY_PUSH_DATA to payload.rawData
        )
        val eventData = mapOf<String, Any?>(
            EVENT_DATA_KEY_XDM to xdmMap,
            EVENT_DATA_KEY_DATA to dataMap
        )
        val event = Event.Builder(
            EVENT_NAME_LIVE_UPDATE_TRACKING,
            EventType.MESSAGING,
            EventSource.REQUEST_CONTENT
        ).setEventData(eventData).build()
        MobileCore.dispatchEvent(event)
    }

    /**
     * Recursively converts a [org.json.JSONObject] to a `Map<String, Any?>` for use in Event
     * Hub event data (which requires Map/List/scalar values, not JSONObject/JSONArray).
     * Preserves nested objects, arrays, and scalar types; converts `JSONObject.NULL` to `null`.
     */
    private fun jsonObjectToMap(obj: org.json.JSONObject): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = jsonToValue(obj.opt(key))
        }
        return result
    }

    private fun jsonArrayToList(arr: org.json.JSONArray): List<Any?> {
        val result = mutableListOf<Any?>()
        for (i in 0 until arr.length()) {
            result.add(jsonToValue(arr.opt(i)))
        }
        return result
    }

    private fun jsonToValue(value: Any?): Any? = when (value) {
        is org.json.JSONObject -> jsonObjectToMap(value)
        is org.json.JSONArray -> jsonArrayToList(value)
        org.json.JSONObject.NULL -> null
        else -> value
    }

    /**
     * Invokes the registered listener (if any). `onLiveUpdateReceived` fires first as a
     * generic hook, then exactly one of `onStart` / `onUpdate` / `onEnd` based on
     * `event_type`. If `event_type` is not canonical, only `onLiveUpdateReceived` fires.
     */
    internal fun invokeListener(payload: LiveUpdatePayload) {
        val listener = LiveUpdateListenerStore.getListener() ?: return
        try {
            listener.onLiveUpdateReceived(payload)
            when (payload.eventType) {
                LiveUpdatePayload.EVENT_TYPE_START -> listener.onStart(payload)
                LiveUpdatePayload.EVENT_TYPE_UPDATE -> listener.onUpdate(payload)
                LiveUpdatePayload.EVENT_TYPE_END -> listener.onEnd(payload)
                else -> {
                    // non-canonical event_type: onLiveUpdateReceived already fired; do nothing more.
                }
            }
        } catch (e: Exception) {
            Log.warning(
                SELF_TAG, SELF_TAG,
                "ILiveUpdateListener threw an exception: ${e.localizedMessage}"
            )
        }
    }
}
