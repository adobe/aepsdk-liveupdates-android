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
import android.content.Intent
import com.adobe.marketing.mobile.Event
import com.adobe.marketing.mobile.EventSource
import com.adobe.marketing.mobile.EventType
import com.adobe.marketing.mobile.Messaging
import com.adobe.marketing.mobile.MobileCore
import com.adobe.marketing.mobile.services.Log
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONException
import org.json.JSONObject

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
 *  - [addPushTrackingDetails]: attaches Live Update tracking extras to an [Intent] so manual-mode apps can wire their own PendingIntents.
 *  - [handleNotificationResponse]: fires tap / action / dismiss tracking. Called by the SDK's own tracker Activity and dismiss receiver, and by manual-mode apps from their target Activity.
 *  - [trackTopicSubscribed] / [trackTopicUnsubscribed]: dispatch Live Update tracking events after the host application has subscribed / unsubscribed the device from an FCM topic. The subscribe / unsubscribe mechanic itself lives in the application layer.
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
    private const val XDM_VALUE_LIVE_UPDATE_TRACKING_RECEIVED = "liveUpdateTracking.received"

    // pushChannelContext.liveActivity.event values dispatched in the outbound XDM.
    // These are the reporting-facing labels; they intentionally differ from the
    // incoming envelope's raw event_type values (start / update / end) so AJO reporting
    // can filter Live Update lifecycle events without a substring match against generic
    // "start" / "end" strings that might appear in other event schemas.
    private const val EVENT_VALUE_LIVE_UPDATE_START = "liveupdate_start"
    private const val EVENT_VALUE_LIVE_UPDATE_UPDATE = "liveupdate_update"
    private const val EVENT_VALUE_LIVE_UPDATE_END = "liveupdate_end"
    // Topic subscription tracking values. Dispatched via trackTopicSubscribed /
    // trackTopicUnsubscribed after the host app completes the corresponding
    // FirebaseMessaging call.
    private const val EVENT_VALUE_TOPIC_SUBSCRIBED = "topic_subscribed"
    private const val EVENT_VALUE_TOPIC_UNSUBSCRIBED = "topic_unsubscribed"
    private const val XDM_VALUE_LIVE_UPDATE_TRACKING_TOPIC = "liveUpdateTracking.topic"
    private const val XDM_VALUE_LIVE_UPDATE_TRACKING_APPLICATION_OPENED = "liveUpdateTracking.applicationOpened"
    private const val XDM_VALUE_LIVE_UPDATE_TRACKING_CUSTOM_ACTION = "liveUpdateTracking.customAction"
    private const val XDM_KEY_PUSH_NOTIFICATION_TRACKING = "pushNotificationTracking"
    private const val XDM_KEY_CUSTOM_ACTION = "customAction"
    private const val XDM_KEY_ACTION_ID = "actionID"
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

    // Dataset override - mirrors what Messaging does for standard push tracking events.
    // We emit {"xdm": ..., "meta": {"collect": {"datasetId": <uuid>}}} so Edge routes the
    // event to the AJO push tracking dataset specifically, not the tag's default dataset.
    // The dataset id comes from the same config key Messaging reads (messaging.eventDataset)
    // because we are intentionally writing to the same AJO dataset (per the design wiki).
    private const val EVENT_DATA_KEY_META = "meta"
    private const val META_KEY_COLLECT = "collect"
    private const val META_KEY_DATASET_ID = "datasetId"
    private const val CONFIG_KEY_EVENT_DATASET = "messaging.eventDataset"

    // Intent extras injected onto Live Update tap / dismiss / action-button PendingIntents.
    // Distinct from Messaging's tracking extras so `Messaging.handleNotificationResponse`
    // and `LiveUpdates.handleNotificationResponse` pick up only their own intents when
    // both SDKs are installed side by side.
    internal const val EXTRA_NOTIFICATION_ID = "adb_liveupdate_notification_id"
    internal const val EXTRA_XDM = "adb_liveupdate_xdm"
    internal const val EXTRA_EVENT_TYPE = "adb_liveupdate_event_type"
    internal const val EXTRA_CHANNEL_ID = "adb_liveupdate_channel_id"
    internal const val EXTRA_ACTION_URI = "adb_liveupdate_action_uri"
    internal const val EXTRA_ACTION_ID = "adb_liveupdate_action_id"
    // Full payload serialized as envelope JSON (see LiveUpdatePayload.toEnvelopeJson), carried
    // on interaction intents so the SDK can re-hydrate the complete payload for onDismissed
    // even after process death.
    internal const val EXTRA_PAYLOAD = "adb_liveupdate_payload"

    /** Custom action id used when the notification is dismissed by the user. */
    const val ACTION_ID_DISMISS = "Dismiss"

    @Volatile
    private var cachedEventDatasetId: String? = null

    // App-registered gate consulted before rendering/tracking an incoming Live Update. Held
    // directly on the facade (no separate store) since only this class reads and writes it.
    @Volatile
    private var liveUpdateInterceptor: ILiveUpdateInterceptor? = null

    init {
        // Listen for Configuration response events so we can cache messaging.eventDataset and
        // inject it on every outbound tracking dispatch. The Configuration extension fires
        // RESPONSE_CONTENT after every config update; missing the first one only means we
        // skip the dataset override on pushes that arrive before config has loaded - graceful.
        MobileCore.registerEventListener(
            EventType.CONFIGURATION,
            EventSource.RESPONSE_CONTENT
        ) { event ->
            val config = event.eventData ?: return@registerEventListener
            val datasetId = config[CONFIG_KEY_EVENT_DATASET] as? String
            if (!datasetId.isNullOrEmpty()) {
                cachedEventDatasetId = datasetId
                Log.debug(
                    LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                    "Cached $CONFIG_KEY_EVENT_DATASET for Live Update tracking: $datasetId"
                )
            }
        }
    }

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

    // ---------- Interceptor registration ----------

    /**
     * Registers an [ILiveUpdateInterceptor] that the SDK consults - after parsing, before any
     * rendering / tracking / listener dispatch - to decide whether to proceed with an incoming
     * Live Update. Only one interceptor is active at a time; setting a new one replaces the
     * previous. Pass `null` to clear (the SDK then always proceeds).
     */
    @JvmStatic
    fun setLiveUpdateInterceptor(interceptor: ILiveUpdateInterceptor?) {
        liveUpdateInterceptor = interceptor
    }

    /** Returns the currently-registered [ILiveUpdateInterceptor], or `null` if none. */
    @JvmStatic
    fun getLiveUpdateInterceptor(): ILiveUpdateInterceptor? = liveUpdateInterceptor

    /**
     * Asks the registered [ILiveUpdateInterceptor] whether the SDK should proceed with
     * [payload]. Returns `true` when no interceptor is registered (default: proceed). Any
     * exception thrown by the interceptor is caught and treated as "proceed" so a buggy
     * interceptor never silently swallows Live Updates.
     */
    internal fun shouldDisplay(payload: LiveUpdatePayload): Boolean {
        val interceptor = liveUpdateInterceptor ?: return true
        return try {
            interceptor.shouldDisplayLiveUpdate(payload)
        } catch (e: Exception) {
            Log.warning(
                LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                "ILiveUpdateInterceptor threw an exception; proceeding with the Live Update: ${e.localizedMessage}"
            )
            true
        }
    }

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
                LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                "trackLiveUpdateEvent: failed to parse payload; skipping tracking + listener dispatch."
            )
            return
        }
        dispatchLiveUpdateEventTracking(context, payload)
        invokeListener(payload)
    }

    /**
     * TODO(ergonomics): callers must build a full [LiveUpdatePayload] up front (16 fields
     * on the factory). If real-world usage settles into "everyone sets only 4-5 of them",
     * layer a lighter overload on top (envelope-JSON string, builder, or partial-payload
     * DSL). Deferred until we see how apps actually adopt the API.
     *
     * Renders a Live Update chip locally (no FCM push required) using the passed [payload],
     * dispatches the receive lifecycle tracking event, and invokes any registered
     * [ILiveUpdateListener]. Intended for cases where the host application starts a Live
     * Update from local state - a workout timer, a step-by-step onboarding, a self-initiated
     * download - and wants the chip + tracking without a round trip through the server.
     *
     * The passed payload's `event_type` drives the outbound tracking value. Use
     * [LiveUpdatePayload.EVENT_TYPE_LOCAL_START] to mark the initial locally-raised chip so
     * server-side reporting can distinguish it from an FCM `start`.
     *
     * @return `true` if the SDK's canonical [LiveUpdateHandlerImpl] was registered via
     *   [Messaging.setLiveUpdateHandler] and rendering ran; `false` if the registered
     *   handler is a custom implementation the SDK cannot invoke directly (in that case
     *   the host app should render the notification itself and call [trackLiveUpdateEvent]).
     */
    @JvmStatic
    fun triggerLocalLiveUpdate(context: Context, payload: LiveUpdatePayload): Boolean {
        val handler = Messaging.getLiveUpdateHandler()
        if (handler !is LiveUpdateHandlerImpl) {
            Log.warning(
                LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                "triggerLocalLiveUpdate requires the canonical LiveUpdateHandlerImpl to be " +
                    "registered via Messaging.setLiveUpdateHandler(...). Skipping."
            )
            return false
        }
        handler.postLiveUpdate(context, payload)
        return true
    }

    // ---------- Interaction tracking (tap / action / dismiss) ----------

    /**
     * Attaches Live Update tracking extras to [intent] so a downstream call to
     * [handleNotificationResponse] can reconstruct enough context to dispatch tracking.
     * Manual-mode apps that build their own PendingIntents for a Live Update chip should
     * call this on every intent whose interaction they want tracked.
     *
     * The SDK's own tracker Activity and dismiss receiver use the same extras internally,
     * so calling this from a manual-mode integration produces identical tracking output.
     *
     * @return `true` if the extras were added; `false` if [intent] is null or [message] is
     *         not a Live Update push.
     */
    @JvmStatic
    fun addPushTrackingDetails(intent: Intent?, message: RemoteMessage?): Boolean {
        if (intent == null || message == null) return false
        val payload = LiveUpdatePayload.parse(message) ?: return false
        intent.putExtra(EXTRA_NOTIFICATION_ID, payload.notificationId)
        intent.putExtra(EXTRA_EVENT_TYPE, payload.eventType)
        payload.topicName?.let { intent.putExtra(EXTRA_CHANNEL_ID, it) }
        payload.xdm?.let { intent.putExtra(EXTRA_XDM, it.toString()) }
        return true
    }

    /**
     * Dispatches a Live Update interaction tracking event. Reads the extras placed on
     * [intent] by [addPushTrackingDetails] and fires a tap / action-button / dismiss event
     * to Edge. No-op when the intent carries no Live Update tracking extras.
     *
     * Called by the SDK's own tracker Activity (for tap and action-button clicks) and its
     * dismiss receiver. Manual-mode apps that own their target Activity should also call
     * this in `onCreate` / `onNewIntent` after building intents with [addPushTrackingDetails].
     *
     * @param intent            the interaction intent carrying Live Update tracking extras
     * @param applicationOpened `true` for a notification tap (chip body); `false` for
     *                          action-button clicks and dismissals
     * @param customActionId    non-null for action-button clicks (button label) or dismissal
     *                          ([ACTION_ID_DISMISS]); `null` for a plain tap
     * @return `true` if a tracking event was dispatched; `false` if the intent carried no
     *         Live Update extras
     */
    @JvmStatic
    @JvmOverloads
    fun handleNotificationResponse(
        intent: Intent?,
        applicationOpened: Boolean,
        customActionId: String? = null
    ): Boolean {
        if (intent == null) return false
        val notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID)
        if (notificationId.isNullOrEmpty()) {
            // Not one of ours; leaves standard-push tracking to Messaging.handleNotificationResponse.
            return false
        }
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
        val xdmString = intent.getStringExtra(EXTRA_XDM)
        val xdm = xdmString?.takeIf { it.isNotEmpty() }?.let { raw ->
            try {
                JSONObject(raw)
            } catch (e: JSONException) {
                Log.debug(
                    LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                    "handleNotificationResponse: unable to parse _xdm extra: ${e.localizedMessage}"
                )
                null
            }
        }
        dispatchInteractionTracking(
            notificationId = notificationId,
            topicName = channelId,
            incomingXdm = xdm,
            applicationOpened = applicationOpened,
            customActionId = customActionId
        )
        return true
    }

    // ---------- Topic subscription tracking ----------

    /**
     * Dispatches a tracking event indicating the device has successfully subscribed to
     * an FCM topic. Fired by the host application after
     * [com.google.firebase.messaging.FirebaseMessaging.subscribeToTopic] completes
     * successfully. Topic subscribe / unsubscribe themselves are intentionally NOT part of
     * the SDK's public API - the application owns that mechanic - the SDK only exposes the
     * tracking dispatch so subscribe events land in AJO reporting alongside the receive
     * lifecycle events.
     *
     * Outbound XDM: `pushChannelContext.liveActivity.event = "topic_subscribed"`,
     * `channelID = <topic>`, `liveActivityID` populated when a [notificationId] is provided
     * (e.g. when the subscription is triggered from an `onStart` Live Update listener).
     *
     * @param topic the FCM topic name (no `/topics/` prefix)
     * @param notificationId the Live Update `notification_id` that triggered the
     *   subscription, or `null` when the subscription is not tied to a specific chip
     */
    @JvmStatic
    @JvmOverloads
    fun trackTopicSubscribed(topic: String, notificationId: String? = null) {
        dispatchTopicTracking(
            topic = topic,
            event = EVENT_VALUE_TOPIC_SUBSCRIBED,
            notificationId = notificationId,
            incomingXdm = null
        )
    }

    /**
     * Live-Update-triggered variant of [trackTopicSubscribed]. Use this when the subscription
     * was raised in response to a Live Update (e.g. from an `onStart` callback): the full
     * [payload] is threaded in so the topic event correlates to the originating campaign /
     * journey. Specifically, `pushProviderMessageID` + `liveActivityID` come from
     * [payload]'s `notificationId`, and the AJO passthrough mixins (`messageExecution`,
     * `decisioning`, `campaignID`, ...) from [payload]'s `_xdm` are merged into the outbound
     * XDM - matching the correlation the lifecycle start/update/end events already carry.
     *
     * @param topic the FCM topic the device was subscribed to (no `/topics/` prefix)
     * @param payload the Live Update payload that triggered the subscription
     */
    @JvmStatic
    fun trackTopicSubscribed(topic: String, payload: LiveUpdatePayload) {
        dispatchTopicTracking(
            topic = topic,
            event = EVENT_VALUE_TOPIC_SUBSCRIBED,
            notificationId = payload.notificationId,
            incomingXdm = payload.xdm
        )
    }

    /**
     * Dispatches a tracking event indicating the device has successfully unsubscribed
     * from an FCM topic. Companion to [trackTopicSubscribed] - fire after
     * [com.google.firebase.messaging.FirebaseMessaging.unsubscribeFromTopic] completes
     * successfully.
     *
     * Outbound XDM: `pushChannelContext.liveActivity.event = "topic_unsubscribed"`,
     * `channelID = <topic>`, `liveActivityID` populated when a [notificationId] is provided.
     *
     * @param topic the FCM topic name
     * @param notificationId the Live Update `notification_id` that triggered the
     *   unsubscription, or `null` when the unsubscription is not tied to a specific chip
     */
    @JvmStatic
    @JvmOverloads
    fun trackTopicUnsubscribed(topic: String, notificationId: String? = null) {
        dispatchTopicTracking(
            topic = topic,
            event = EVENT_VALUE_TOPIC_UNSUBSCRIBED,
            notificationId = notificationId,
            incomingXdm = null
        )
    }

    /**
     * Live-Update-triggered variant of [trackTopicUnsubscribed]. Use this when the
     * unsubscription was raised in response to a Live Update (e.g. from an `onEnd` callback):
     * the full [payload] is threaded in so the topic event correlates to the originating
     * campaign / journey, exactly as [trackTopicSubscribed] does for the subscribe case.
     *
     * @param topic the FCM topic the device was unsubscribed from (no `/topics/` prefix)
     * @param payload the Live Update payload that triggered the unsubscription
     */
    @JvmStatic
    fun trackTopicUnsubscribed(topic: String, payload: LiveUpdatePayload) {
        dispatchTopicTracking(
            topic = topic,
            event = EVENT_VALUE_TOPIC_UNSUBSCRIBED,
            notificationId = payload.notificationId,
            incomingXdm = payload.xdm
        )
    }

    /**
     * Shared dispatch used by [trackTopicSubscribed] and [trackTopicUnsubscribed]. Wraps
     * the outbound Edge event with the same lifecycle-XDM shape used for start / update /
     * end, but with the [event] value driving `pushChannelContext.liveActivity.event`.
     * [incomingXdm] carries the originating push's `_xdm` passthrough (campaign / journey
     * correlation mixins) for Live-Update-triggered calls; `null` for standalone subscribes.
     */
    private fun dispatchTopicTracking(
        topic: String,
        event: String,
        notificationId: String?,
        incomingXdm: JSONObject?
    ) {
        if (topic.isEmpty()) {
            Log.debug(
                LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                "Skipping topic tracking dispatch: topic is empty (event='$event')."
            )
            return
        }
        dispatchTrackingEvent(
            xdmEventType = XDM_VALUE_LIVE_UPDATE_TRACKING_TOPIC,
            notificationId = notificationId,
            topicName = topic,
            liveActivityEvent = event,
            incomingXdm = incomingXdm,
            customActionId = null
        )
    }

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
     *           "event":          "liveupdate_start" | "liveupdate_update" | "liveupdate_end"
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
            eventType == LiveUpdatePayload.EVENT_TYPE_END ||
            eventType == LiveUpdatePayload.EVENT_TYPE_LOCAL_START
        if (!isCanonical) {
            Log.debug(
                LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                "Skipping Live Update event tracking dispatch: event_type='$eventType' is not start/update/end."
            )
            return
        }
        // Map the envelope's raw event_type to the outbound XDM reporting value.
        // The envelope stays as start / update / end (server + parser contract);
        // the outbound XDM uses the prefixed liveupdate_* values for AJO reporting.
        // EVENT_TYPE_LOCAL_START (app-raised locally) reports as liveupdate_start.
        val liveActivityEventXdmValue = when (eventType) {
            LiveUpdatePayload.EVENT_TYPE_START,
            LiveUpdatePayload.EVENT_TYPE_LOCAL_START -> EVENT_VALUE_LIVE_UPDATE_START
            LiveUpdatePayload.EVENT_TYPE_UPDATE -> EVENT_VALUE_LIVE_UPDATE_UPDATE
            LiveUpdatePayload.EVENT_TYPE_END -> EVENT_VALUE_LIVE_UPDATE_END
            else -> return // isCanonical guard above makes this unreachable
        }
        dispatchTrackingEvent(
            xdmEventType = XDM_VALUE_LIVE_UPDATE_TRACKING_RECEIVED,
            notificationId = payload.notificationId,
            topicName = payload.topicName,
            liveActivityEvent = liveActivityEventXdmValue,
            incomingXdm = payload.xdm,
            customActionId = null
        )
    }

    /**
     * Dispatches an interaction tracking event for a Live Update - notification tap, action
     * button click, or dismissal. Reconstructs the tracking XDM from the extras carried on
     * the interaction [Intent] (see [addPushTrackingDetails] for how the extras are set).
     *
     * The top-level `eventType` is chosen from [applicationOpened] and [customActionId]:
     *  - `customActionId` non-null (action button click or dismiss) - "liveUpdateTracking.customAction"
     *  - `applicationOpened` true (notification tap) - "liveUpdateTracking.applicationOpened"
     *  - otherwise - no dispatch (nothing to track)
     *
     * Interaction events omit `pushChannelContext.liveActivity.event`; only the lifecycle
     * receive dispatch fills that field. Otherwise a tap or dismiss occurring during the
     * `start` push would be double-counted against the "start" phase in AJO reporting.
     */
    internal fun dispatchInteractionTracking(
        notificationId: String,
        topicName: String?,
        incomingXdm: JSONObject?,
        applicationOpened: Boolean,
        customActionId: String?
    ) {
        val xdmEventType = when {
            !customActionId.isNullOrEmpty() -> XDM_VALUE_LIVE_UPDATE_TRACKING_CUSTOM_ACTION
            applicationOpened -> XDM_VALUE_LIVE_UPDATE_TRACKING_APPLICATION_OPENED
            else -> {
                Log.debug(
                    LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                    "Skipping interaction tracking dispatch: neither applicationOpened nor customActionId set."
                )
                return
            }
        }
        dispatchTrackingEvent(
            xdmEventType = xdmEventType,
            notificationId = notificationId,
            topicName = topicName,
            liveActivityEvent = null,
            incomingXdm = incomingXdm,
            customActionId = customActionId
        )
    }

    /**
     * Shared Edge-event build + dispatch for both the receive lifecycle and interaction
     * events. Adds the [messaging.eventDataset][CONFIG_KEY_EVENT_DATASET] override on
     * `meta.collect.datasetId` whenever configuration has supplied a value.
     */
    private fun dispatchTrackingEvent(
        xdmEventType: String,
        notificationId: String?,
        topicName: String?,
        liveActivityEvent: String?,
        incomingXdm: JSONObject?,
        customActionId: String?
    ) {
        val xdmMap = buildLiveActivityTrackingXdm(
            xdmEventType = xdmEventType,
            notificationId = notificationId,
            topicName = topicName,
            liveActivityEvent = liveActivityEvent,
            incomingXdm = incomingXdm,
            customActionId = customActionId
        )
        val eventData = mutableMapOf<String, Any?>(EVENT_DATA_KEY_XDM to xdmMap)
        cachedEventDatasetId?.let { datasetId ->
            eventData[EVENT_DATA_KEY_META] = mapOf<String, Any?>(
                META_KEY_COLLECT to mapOf<String, Any?>(META_KEY_DATASET_ID to datasetId)
            )
        }
        val event = Event.Builder(
            EVENT_NAME_LIVE_UPDATE_TRACKING,
            EventType.EDGE,
            EventSource.REQUEST_CONTENT
        ).setEventData(eventData).build()
        MobileCore.dispatchEvent(event)
    }

    /**
     * Constructs the outbound XDM map for a Live Update tracking event. Used for both the
     * lifecycle receive event (`liveUpdateTracking.received`) and interaction events
     * (`liveUpdateTracking.applicationOpened` for tap,
     * `liveUpdateTracking.customAction` for action-button clicks and dismiss).
     *
     * The receive lifecycle phase (`start` / `update` / `end`) rides through
     * `pushChannelContext.liveActivity.event` only for the receive dispatch; interaction
     * events (tap / action click / dismiss) omit the field so AJO reporting does not
     * double-count them against the concurrent lifecycle phase. When [customActionId]
     * is non-null, it is added under `pushNotificationTracking.customAction.actionID`.
     */
    private fun buildLiveActivityTrackingXdm(
        xdmEventType: String,
        notificationId: String?,
        topicName: String?,
        liveActivityEvent: String?,
        incomingXdm: JSONObject?,
        customActionId: String?
    ): Map<String, Any?> {
        val xdmMap = mutableMapOf<String, Any?>()

        // 1. Top-level eventType.
        xdmMap[XDM_KEY_EVENT_TYPE] = xdmEventType

        // 2. pushNotificationTracking marker - identifies FCM as the push provider for this
        //    event. pushProviderMessageID is only meaningful when a notification is
        //    associated with the event (all lifecycle/interaction events); topic-subscription
        //    events omit it.
        val pushNotificationTracking = mutableMapOf<String, Any?>(
            XDM_KEY_PUSH_PROVIDER to XDM_VALUE_PLATFORM_FCM
        )
        if (!notificationId.isNullOrEmpty()) {
            pushNotificationTracking[XDM_KEY_PUSH_PROVIDER_MESSAGE_ID] = notificationId
        }
        if (!customActionId.isNullOrEmpty()) {
            pushNotificationTracking[XDM_KEY_CUSTOM_ACTION] = mapOf<String, Any?>(
                XDM_KEY_ACTION_ID to customActionId
            )
        }
        xdmMap[XDM_KEY_PUSH_NOTIFICATION_TRACKING] = pushNotificationTracking

        // 3. Merge the incoming _xdm passthrough from the server. AJO nests its mixins under
        //    "mixins" (or "cjm" as an alias) for schema composition; flatten to root before
        //    Edge sees it. Mirrors MessagingExtension.addXDMData.
        val incomingXdmMap = incomingXdm?.let { jsonObjectToMap(it) }
        if (incomingXdmMap != null) {
            @Suppress("UNCHECKED_CAST")
            val mixinsLayer = (incomingXdmMap[XDM_KEY_MIXINS] as? Map<String, Any?>)
                ?: (incomingXdmMap[XDM_KEY_CJM] as? Map<String, Any?>)
            if (mixinsLayer != null) {
                xdmMap.putAll(mixinsLayer)
            } else {
                xdmMap.putAll(incomingXdmMap.filterKeys { it != XDM_KEY_MIXINS && it != XDM_KEY_CJM })
            }
        }

        // 4. Find or build _experience.customerJourneyManagement and inject the standard
        //    Messaging push profile plus the pushChannelContext.liveActivity block.
        @Suppress("UNCHECKED_CAST")
        val experience = (xdmMap[XDM_KEY_EXPERIENCE] as? Map<String, Any?>)?.toMutableMap()
            ?: mutableMapOf()
        @Suppress("UNCHECKED_CAST")
        val cjm = (experience[XDM_KEY_CUSTOMER_JOURNEY_MANAGEMENT] as? Map<String, Any?>)?.toMutableMap()
            ?: mutableMapOf()

        @Suppress("UNCHECKED_CAST")
        val messageProfile = (cjm[XDM_KEY_MESSAGE_PROFILE] as? Map<String, Any?>)?.toMutableMap()
            ?: mutableMapOf()
        if (!messageProfile.containsKey(XDM_KEY_CHANNEL)) {
            messageProfile[XDM_KEY_CHANNEL] = mapOf<String, Any?>(
                XDM_KEY_ID to XDM_VALUE_PUSH_CHANNEL_ID
            )
        }
        cjm[XDM_KEY_MESSAGE_PROFILE] = messageProfile

        val liveActivity = mutableMapOf<String, Any?>(
            XDM_KEY_LIVE_ACTIVITY_CHANNEL_ID to (topicName ?: "")
        )
        // liveActivityID only rides when a notification is associated with this event.
        // Topic subscribe / unsubscribe fired outside a Live Update chip has no id.
        if (!notificationId.isNullOrEmpty()) {
            liveActivity[XDM_KEY_LIVE_ACTIVITY_ID] = notificationId
        }
        // liveActivity.event is populated only for receive lifecycle events (start/update/end)
        // and topic-change events (topic_subscribed/topic_unsubscribed); interaction events
        // omit it so AJO reporting does not double-count them against the concurrent lifecycle phase.
        if (!liveActivityEvent.isNullOrEmpty()) {
            liveActivity[XDM_KEY_LIVE_ACTIVITY_EVENT] = liveActivityEvent
        }
        cjm[XDM_KEY_PUSH_CHANNEL_CONTEXT] = mapOf<String, Any?>(
            XDM_KEY_PLATFORM to XDM_VALUE_PLATFORM_FCM,
            XDM_KEY_LIVE_ACTIVITY to liveActivity
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
                LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                "ILiveUpdateListener threw an exception: ${e.localizedMessage}"
            )
        }
    }

    /**
     * Re-hydrates the full [LiveUpdatePayload] from the extras on a dismiss [intent] (set via
     * [LiveUpdateHandlerImpl] at post time) and invokes [ILiveUpdateListener.onDismissed].
     * No-op if no listener is registered or the payload extra is missing / unparseable.
     * Called from [LiveUpdateInteractionReceiver] after dismiss tracking is dispatched.
     */
    internal fun notifyDismissed(intent: Intent) {
        val listener = LiveUpdateListenerStore.getListener() ?: return
        val envelopeJson = intent.getStringExtra(EXTRA_PAYLOAD)
        if (envelopeJson.isNullOrEmpty()) {
            Log.debug(
                LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                "Cannot invoke onDismissed: dismiss intent carries no serialized payload."
            )
            return
        }
        val payload = LiveUpdatePayload.fromEnvelopeJson(envelopeJson, intent.getStringExtra(EXTRA_XDM))
        if (payload == null) {
            Log.debug(
                LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                "Cannot invoke onDismissed: serialized payload failed to re-hydrate."
            )
            return
        }
        try {
            listener.onDismissed(payload)
        } catch (e: Exception) {
            Log.warning(
                LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                "ILiveUpdateListener.onDismissed threw an exception: ${e.localizedMessage}"
            )
        }
    }

    /**
     * Re-hydrates the full [LiveUpdatePayload] from the extras on a chip-tap [intent] (set via
     * [LiveUpdateHandlerImpl] at post time) and invokes [ILiveUpdateListener.onClick].
     * No-op if no listener is registered or the payload extra is missing / unparseable.
     * Called from [LiveUpdateTrackerActivity] for a chip body tap after tap tracking is dispatched.
     */
    internal fun notifyClicked(intent: Intent) {
        val listener = LiveUpdateListenerStore.getListener() ?: return
        val envelopeJson = intent.getStringExtra(EXTRA_PAYLOAD)
        if (envelopeJson.isNullOrEmpty()) {
            Log.debug(
                LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                "Cannot invoke onClick: tap intent carries no serialized payload."
            )
            return
        }
        val payload = LiveUpdatePayload.fromEnvelopeJson(envelopeJson, intent.getStringExtra(EXTRA_XDM))
        if (payload == null) {
            Log.debug(
                LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                "Cannot invoke onClick: serialized payload failed to re-hydrate."
            )
            return
        }
        try {
            listener.onClick(payload)
        } catch (e: Exception) {
            Log.warning(
                LiveUpdatesConstants.LOG_TAG, SELF_TAG,
                "ILiveUpdateListener.onClick threw an exception: ${e.localizedMessage}"
            )
        }
    }
}
