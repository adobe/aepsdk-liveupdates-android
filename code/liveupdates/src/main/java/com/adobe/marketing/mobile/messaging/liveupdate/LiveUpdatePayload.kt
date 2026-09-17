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

import com.adobe.marketing.mobile.services.Log
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONException
import org.json.JSONObject

/**
 * Parsed Live Update push payload, decoupled from Messaging's [com.adobe.marketing.mobile.MessagingPushPayload].
 *
 * Every envelope field the SDK or the sample app reads is parsed into a typed property here.
 * No raw `JSONObject` or `data: Map` is retained - if a new field is needed, add it as a
 * first-class property and parse it in [parse].
 *
 * Construct via [parse]; never instantiate directly.
 *
 * Schema v1.1 required fields (parse returns null when any is missing or empty):
 *  - `notification_id`
 *  - `notification_channel_id`
 *  - `event_type`
 *  - `title`
 *  - `timestamp`
 */
class LiveUpdatePayload private constructor(
    // SDK-canonical fields (drive NotificationCompat.Builder calls and event tracking)
    val notificationId: String,
    val channelId: String,
    val eventType: String,
    val title: String,
    val timestamp: Long,
    val priority: String?,
    val body: String?,
    val criticalText: String?,
    val whenMillis: Long?,
    val dismissAfterSeconds: Long?,
    val contentState: JSONObject?,

    // `topicName` rides through to the tracking dispatch as
    // pushChannelContext.liveActivity.channelID. The StyleProvider does NOT read it.
    val topicName: String?,

    // Per-push small icon override. Drawable resource name (e.g. "ic_flight_notification").
    // Resolved by the renderer via Resources.getIdentifier. When null or unresolvable, the
    // renderer falls back to MobileCore.getSmallIconResourceID(), then to the app icon.
    val smallIcon: String?,

    // Parsed AJO XDM tracking block from data['_xdm']. Opaque to the SDK; passed through
    // to Edge as-is in the Live Update event tracking dispatch so AJO server-side reporting
    // can correlate via messageExecutionID / campaignID / etc. Null when '_xdm' is absent
    // or unparseable.
    val xdm: JSONObject?
) {

    /**
     * Concise, log-friendly representation. The default (non-data-class) `toString()` would
     * only print the object hash, so this surfaces the key identifying fields instead.
     */
    override fun toString(): String =
        "LiveUpdatePayload(notificationId=$notificationId, eventType=$eventType, " +
            "channelId=$channelId, title=$title, timestamp=$timestamp, topicName=$topicName)"

    /**
     * Serializes this payload back into the SDK-canonical envelope JSON (the same shape
     * [parse] reads from `adb_liveupdate_data`). Used to carry the full payload on a chip's
     * interaction PendingIntents (e.g. the dismiss delete-intent) so the SDK can re-hydrate
     * it via [fromEnvelopeJson] when the interaction fires - possibly after process death,
     * where only the PendingIntent extras survive. The `_xdm` block is carried separately.
     */
    internal fun toEnvelopeJson(): String {
        val obj = JSONObject()
        obj.put(KEY_NOTIFICATION_ID, notificationId)
        obj.put(KEY_CHANNEL_ID, channelId)
        obj.put(KEY_EVENT_TYPE, eventType)
        obj.put(KEY_TITLE, title)
        obj.put(KEY_TIMESTAMP, timestamp)
        priority?.let { obj.put(KEY_PRIORITY, it) }
        body?.let { obj.put(KEY_BODY, it) }
        criticalText?.let { obj.put(KEY_CRITICAL_TEXT, it) }
        whenMillis?.let { obj.put(KEY_WHEN, it) }
        dismissAfterSeconds?.let { obj.put(KEY_DISMISS_AFTER, it) }
        contentState?.let { obj.put(KEY_CONTENT_STATE, it) }
        topicName?.let { obj.put(KEY_TOPIC_NAME, it) }
        smallIcon?.let { obj.put(KEY_SMALL_ICON, it) }
        return obj.toString()
    }

    companion object {
        private const val SELF_TAG = "LiveUpdatePayload"

        // SDK-canonical envelope keys
        private const val KEY_NOTIFICATION_ID = "notification_id"
        private const val KEY_CHANNEL_ID = "notification_channel_id"
        private const val KEY_EVENT_TYPE = "event_type"
        private const val KEY_TITLE = "title"
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_PRIORITY = "priority"
        private const val KEY_BODY = "body"
        private const val KEY_CRITICAL_TEXT = "critical_text"
        private const val KEY_WHEN = "when"
        private const val KEY_DISMISS_AFTER = "dismiss_after"
        private const val KEY_CONTENT_STATE = "content_state"
        private const val KEY_TOPIC_NAME = "topic_name"
        private const val KEY_SMALL_ICON = "small_icon"

        // FCM data map key for the XDM passthrough block.
        private const val DATA_KEY_XDM = "_xdm"

        /** Canonical Live Update event_type values the SDK dispatches tracking + listener callbacks for. */
        const val EVENT_TYPE_START = "start"
        const val EVENT_TYPE_UPDATE = "update"
        const val EVENT_TYPE_END = "end"

        /**
         * event_type value used when the host application triggers a Live Update locally
         * via [LiveUpdates.triggerLocalLiveUpdate], as opposed to receiving it as an FCM
         * push. Matches the iOS Live Activities `localStart` convention.
         */
        const val EVENT_TYPE_LOCAL_START = "localstart"

        /**
         * Constructs a [LiveUpdatePayload] directly from typed inputs, without an
         * intermediate `RemoteMessage`. Primary use case is
         * [LiveUpdates.triggerLocalLiveUpdate], where the host app raises a Live Update
         * chip programmatically. Required inputs match the envelope's required fields
         * (`notification_id`, `notification_channel_id`, `event_type`, `title`, `timestamp`);
         * everything else is optional and defaults to `null` / absent.
         */
        @JvmStatic
        @JvmOverloads
        fun create(
            notificationId: String,
            channelId: String,
            eventType: String,
            title: String,
            timestamp: Long,
            priority: String? = null,
            body: String? = null,
            criticalText: String? = null,
            whenMillis: Long? = null,
            dismissAfterSeconds: Long? = null,
            contentState: JSONObject? = null,
            topicName: String? = null,
            smallIcon: String? = null,
            xdm: JSONObject? = null
        ): LiveUpdatePayload = LiveUpdatePayload(
            notificationId = notificationId,
            channelId = channelId,
            eventType = eventType,
            title = title,
            timestamp = timestamp,
            priority = priority,
            body = body,
            criticalText = criticalText,
            whenMillis = whenMillis,
            dismissAfterSeconds = dismissAfterSeconds,
            contentState = contentState,
            topicName = topicName,
            smallIcon = smallIcon,
            xdm = xdm
        )

        /** Fast detection - does this [message] carry the Live Update envelope key? */
        @JvmStatic
        fun isLiveUpdate(message: RemoteMessage): Boolean =
            message.data.containsKey(LiveUpdatesConstants.LIVE_UPDATE_DATA_KEY)

        /**
         * Parses [message] into a [LiveUpdatePayload]. Returns `null` when the envelope is
         * absent, malformed, or missing any required field (`notification_id`,
         * `notification_channel_id`, `event_type`, `title`).
         */
        @JvmStatic
        fun parse(message: RemoteMessage): LiveUpdatePayload? {
            val envelopeJson = message.data[LiveUpdatesConstants.LIVE_UPDATE_DATA_KEY]
            if (envelopeJson.isNullOrEmpty()) {
                return null
            }
            return fromEnvelopeJson(envelopeJson, message.data[DATA_KEY_XDM])
        }

        /**
         * Re-hydrates a payload from a serialized envelope JSON string (as produced by
         * [toEnvelopeJson]) plus an optional raw `_xdm` string. Shared by [parse] and by the
         * interaction-intent round-trip (e.g. dismiss), where the SDK rebuilds the full
         * payload from the PendingIntent extras. Returns null on malformed JSON or a missing
         * required field.
         */
        internal fun fromEnvelopeJson(envelopeJson: String, xdmRaw: String?): LiveUpdatePayload? {
            val obj = try {
                JSONObject(envelopeJson)
            } catch (e: JSONException) {
                Log.debug(
                    LiveUpdatesConstants.LOG_TAG,
                    SELF_TAG,
                    "Unable to parse adb_liveupdate_data: ${e.localizedMessage}"
                )
                return null
            }

            val notificationId = obj.requiredString(KEY_NOTIFICATION_ID) ?: return null
            val channelId = obj.requiredString(KEY_CHANNEL_ID) ?: return null
            val eventType = obj.requiredString(KEY_EVENT_TYPE) ?: return null
            val title = obj.requiredString(KEY_TITLE) ?: return null
            val timestamp = obj.requiredLong(KEY_TIMESTAMP) ?: return null

            // Parse the _xdm block as a typed JSONObject. Opaque to the SDK; null when
            // absent or unparseable. Carried through to the tracking dispatch so AJO
            // reporting can correlate via messageExecutionID / campaignID etc.
            val xdm = xdmRaw?.takeIf { it.isNotEmpty() }?.let { raw ->
                try {
                    JSONObject(raw)
                } catch (e: JSONException) {
                    Log.debug(
                        LiveUpdatesConstants.LOG_TAG,
                        SELF_TAG,
                        "Unable to parse _xdm: ${e.localizedMessage}"
                    )
                    null
                }
            }

            val payload = LiveUpdatePayload(
                notificationId = notificationId,
                channelId = channelId,
                eventType = eventType,
                title = title,
                timestamp = timestamp,
                priority = obj.optString(KEY_PRIORITY).takeIf { it.isNotEmpty() },
                body = obj.optString(KEY_BODY).takeIf { it.isNotEmpty() },
                criticalText = obj.optString(KEY_CRITICAL_TEXT).takeIf { it.isNotEmpty() },
                whenMillis = if (obj.has(KEY_WHEN)) obj.optLong(KEY_WHEN) else null,
                dismissAfterSeconds = if (obj.has(KEY_DISMISS_AFTER)) obj.optLong(KEY_DISMISS_AFTER) else null,
                contentState = obj.optJSONObject(KEY_CONTENT_STATE),
                topicName = obj.optString(KEY_TOPIC_NAME).takeIf { it.isNotEmpty() },
                smallIcon = obj.optString(KEY_SMALL_ICON).takeIf { it.isNotEmpty() },
                xdm = xdm
            )
            Log.debug(LiveUpdatesConstants.LOG_TAG, SELF_TAG, "Parsed Live Update payload: $payload")
            return payload
        }

        /** Reads a non-empty string field, logging a debug message and returning null if missing. */
        private fun JSONObject.requiredString(key: String): String? {
            val value = optString(key).takeIf { it.isNotEmpty() }
            if (value == null) {
                Log.debug(
                    LiveUpdatesConstants.LOG_TAG,
                    SELF_TAG,
                    "adb_liveupdate_data missing required field '$key'"
                )
            }
            return value
        }

        private fun JSONObject.requiredLong(key: String): Long? {
            if (!has(key)) {
                Log.debug(
                    LiveUpdatesConstants.LOG_TAG,
                    SELF_TAG,
                    "adb_liveupdate_data missing required field '$key'"
                )
                return null
            }
            return optLong(key)
        }
    }
}
