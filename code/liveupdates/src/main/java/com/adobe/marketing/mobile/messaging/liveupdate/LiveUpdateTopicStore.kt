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

/**
 * SharedPreferences-backed set of FCM topic names the device is currently subscribed to via
 * the Live Updates SDK. Used by [LiveUpdates.subscribeToTopic] / [unsubscribeFromTopic] /
 * [getSubscribedTopics].
 *
 * Persists across launches so [LiveUpdates.getSubscribedTopics] returns the correct set
 * after a cold start. The SharedPreferences file is private to the application package.
 */
internal object LiveUpdateTopicStore {

    private const val PREFS_NAME = "com.adobe.marketing.mobile.messaging.liveupdate.topics"
    private const val KEY_TOPICS = "subscribed_topics"

    fun addTopic(context: Context, topic: String) {
        val prefs = prefs(context)
        val current = prefs.getStringSet(KEY_TOPICS, emptySet()).orEmpty()
        if (topic in current) return
        prefs.edit().putStringSet(KEY_TOPICS, current + topic).apply()
    }

    fun removeTopic(context: Context, topic: String) {
        val prefs = prefs(context)
        val current = prefs.getStringSet(KEY_TOPICS, emptySet()).orEmpty()
        if (topic !in current) return
        prefs.edit().putStringSet(KEY_TOPICS, current - topic).apply()
    }

    fun getAllTopics(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_TOPICS, emptySet()).orEmpty().toSet()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
