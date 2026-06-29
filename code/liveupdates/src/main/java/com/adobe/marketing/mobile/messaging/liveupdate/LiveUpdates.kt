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
 *  - [setLiveUpdateListener] / [getLiveUpdateListener]: register a hook for start/update/end Live Update events.
 *  - [trackLiveUpdateEvent]: Pattern 3 (manual) entry point that fires Live Update event tracking + listener invocation when the app builds and posts the notification itself.
 *  - [subscribeToTopic] / [unsubscribeFromTopic] / [getSubscribedTopics]: FCM topic subscription helpers for the broadcast use case.
 *
 * Pattern 2 (mixed) integration does NOT need an entry point on this facade. Apps with their
 * own [com.google.firebase.messaging.FirebaseMessagingService] call
 * `MessagingService.handleRemoteMessage(context, message)` directly - that method already
 * detects Live Updates by the `adb_liveupdate_data` key and dispatches to the registered
 * [com.adobe.marketing.mobile.ILiveUpdateHandler].
 */
object LiveUpdates {

    private const val SELF_TAG = "LiveUpdates"
    private const val EXTENSION_VERSION = "1.0.0"

    // Event dispatch constants.
    private const val EVENT_NAME_LIVE_UPDATE_TRACKING = "Live Update Event Tracking"
    private const val EVENT_DATA_KEY_XDM = "xdm"

    // XDM schema field names - match the canonical AJO Push Tracking Experience Event Schema,
    // which is the same schema the iOS Live Activities tracking flow populates in production.
    private const val XDM_KEY_EVENT_TYPE = "eventType"
    private const val XDM_VALUE_PUSH_TRACKING_APPLICATION_OPENED = "pushTracking.applicationOpened"
    private const val XDM_KEY_PUSH_NOTIFICATION_TRACKING = "pushNotificationTracking"
    private const val XDM_KEY_PUSH_PROVIDER = "pushProvider"
    private const val XDM_KEY_PUSH_PROVIDER_MESSAGE_ID = "pushProviderMessageID"
    private const val XDM_KEY_EXPERIENCE = "_experience"
    private const val XDM_KEY_CUSTOMER_JOURNEY_MANAGEMENT = "customerJourneyManagement"
    private const val XDM_KEY_MIXINS = "mixins"
    private const val XDM_KEY_CJM = "cjm"
    private const val XDM_KEY_MESSAGE_PROFILE = "messageProfile"
    private const val XDM_KEY_CHANNEL = "channel"
    private const val XDM_KEY_ID = "_id"
    private const val XDM_VALUE_PUSH_CHANNEL_ID = "https://ns.adobe.com/xdm/channels/push"
    private const val XDM_KEY_PUSH_CHANNEL_CONTEXT = "pushChannelContext"
    private const val XDM_KEY_PLATFORM = "platform"
    private const val XDM_VALUE_PLATFORM_FCM = "fcm"
    private const val XDM_KEY_LIVE_ACTIVITY = "liveActivity"
    private const val XDM_KEY_LIVE_ACTIVITY_ID = "liveActivityID"
    private const val XDM_KEY_LIVE_ACTIVITY_CHANNEL_ID = "channelID"
    private const val XDM_KEY_LIVE_ACTIVITY_EVENT = "event"

    /** Returns the SDK version string. */
    @JvmStatic
    fun extensionVersion(): String = EXTENSION_VERSION

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
     * Dispatches an Edge event carrying the Live Update lifecycle tracking payload. The XDM
     * shape matches the canonical AJO Push Tracking Experience Event Schema (the same dataset
     * the iOS Live Activities tracking flow writes into today), so AJO server-side reporting
     * picks the events up without any new schema work. Skips dispatch (with a debug log) if
     * `event_type` is not one of `start` / `update` / `end`.
     *
     * Outbound XDM shape:
     * ```
     * {
     *   "eventType": "pushTracking.applicationOpened",
     *   "pushNotificationTracking": {
     *     "pushProvider":          "fcm",
     *     "pushProviderMessageID": "<notification_id>"
     *   },
     *   "_experience": {
     *     "customerJourneyManagement": {
     *       (passthrough from incoming _xdm: messageExecution, decisioning, etc.)
     *       "messageProfile":      { "channel": { "_id": "https://ns.adobe.com/xdm/channels/push" } },
     *       "pushChannelContext":  {
     *         "platform":     "fcm",
     *         "liveActivity": {
     *           "liveActivityID": "<notification_id>",
     *           "channelID":      "<topic_name>",
     *           "event":          "start" | "update" | "end"
     *         }
     *       }
     *     }
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
        val xdmMap = buildLiveActivityTrackingXdm(payload)
        val eventData = mapOf<String, Any?>(EVENT_DATA_KEY_XDM to xdmMap)
        // EventType.EDGE + REQUEST_CONTENT is the contract the Edge extension listens for.
        // Edge picks up this event from the Event Hub, posts it to the AJO Edge endpoint
        // with ECID correlation intact, and the XDM (messageExecutionID / campaignID etc.)
        // flows through unchanged so server-side AJO reporting can correlate.
        val event = Event.Builder(
            EVENT_NAME_LIVE_UPDATE_TRACKING,
            EventType.EDGE,
            EventSource.REQUEST_CONTENT
        ).setEventData(eventData).build()
        MobileCore.dispatchEvent(event)
    }

    /**
     * Constructs the outbound XDM map for a Live Update lifecycle tracking event. Mirrors the
     * shape Messaging's `MessagingExtension.getXdmData` + `addXDMData` produce for standard
     * push tracking, and adds the iOS-parity `pushChannelContext.liveActivity` block carrying
     * the Live Update id, topic name, and lifecycle phase. See class-level KDoc for the full
     * field list and the Push Tracking Experience Event Schema reference.
     */
    private fun buildLiveActivityTrackingXdm(payload: LiveUpdatePayload): Map<String, Any?> {
        val xdmMap = mutableMapOf<String, Any?>()

        // 1. Top-level eventType. Same canonical value Messaging uses for standard push
        //    tracking; the Live Update lifecycle phase is carried in
        //    pushChannelContext.liveActivity.event below.
        xdmMap[XDM_KEY_EVENT_TYPE] = XDM_VALUE_PUSH_TRACKING_APPLICATION_OPENED

        // 2. pushNotificationTracking marker - identifies FCM as the push provider for this
        //    event, identical to standard push tracking events.
        xdmMap[XDM_KEY_PUSH_NOTIFICATION_TRACKING] = mapOf<String, Any?>(
            XDM_KEY_PUSH_PROVIDER to XDM_VALUE_PLATFORM_FCM,
            XDM_KEY_PUSH_PROVIDER_MESSAGE_ID to payload.notificationId
        )

        // 3. Merge the incoming _xdm passthrough from the server. AJO nests its mixins under
        //    "mixins" (or "cjm" as an alias) for schema composition; flatten to root before
        //    Edge sees it. Mirrors MessagingExtension.addXDMData.
        val incomingXdm = payload.xdm?.let { jsonObjectToMap(it) }
        if (incomingXdm != null) {
            @Suppress("UNCHECKED_CAST")
            val mixinsLayer = (incomingXdm[XDM_KEY_MIXINS] as? Map<String, Any?>)
                ?: (incomingXdm[XDM_KEY_CJM] as? Map<String, Any?>)
            if (mixinsLayer != null) {
                xdmMap.putAll(mixinsLayer)
            } else {
                // Server did not use a wrapper; treat root fields as already-flattened.
                xdmMap.putAll(incomingXdm.filterKeys { it != XDM_KEY_MIXINS && it != XDM_KEY_CJM })
            }
        }

        // 4. Find or build _experience.customerJourneyManagement, then add the standard
        //    Messaging push profile + the iOS-parity pushChannelContext.liveActivity block.
        @Suppress("UNCHECKED_CAST")
        val experience = (xdmMap[XDM_KEY_EXPERIENCE] as? Map<String, Any?>)?.toMutableMap()
            ?: mutableMapOf()
        @Suppress("UNCHECKED_CAST")
        val cjm = (experience[XDM_KEY_CUSTOMER_JOURNEY_MANAGEMENT] as? Map<String, Any?>)?.toMutableMap()
            ?: mutableMapOf()

        // messageProfile.channel = push (same as Messaging's MESSAGE_PROFILE_JSON constant)
        @Suppress("UNCHECKED_CAST")
        val messageProfile = (cjm[XDM_KEY_MESSAGE_PROFILE] as? Map<String, Any?>)?.toMutableMap()
            ?: mutableMapOf()
        if (!messageProfile.containsKey(XDM_KEY_CHANNEL)) {
            messageProfile[XDM_KEY_CHANNEL] = mapOf<String, Any?>(
                XDM_KEY_ID to XDM_VALUE_PUSH_CHANNEL_ID
            )
        }
        cjm[XDM_KEY_MESSAGE_PROFILE] = messageProfile

        // pushChannelContext.liveActivity - the new bit for Live Updates. Mirrors the iOS
        // Live Activities tracking shape that AJO already understands in production.
        cjm[XDM_KEY_PUSH_CHANNEL_CONTEXT] = mapOf<String, Any?>(
            XDM_KEY_PLATFORM to XDM_VALUE_PLATFORM_FCM,
            XDM_KEY_LIVE_ACTIVITY to mapOf<String, Any?>(
                XDM_KEY_LIVE_ACTIVITY_ID to payload.notificationId,
                XDM_KEY_LIVE_ACTIVITY_CHANNEL_ID to (payload.topicName ?: ""),
                XDM_KEY_LIVE_ACTIVITY_EVENT to payload.eventType
            )
        )

        experience[XDM_KEY_CUSTOMER_JOURNEY_MANAGEMENT] = cjm
        xdmMap[XDM_KEY_EXPERIENCE] = experience

        return xdmMap
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
