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

import androidx.room.Entity

/**
 * Tracks the last-seen [timestamp] for a live update identified by
 * ([notificationId], [channelId]), used to detect out-of-order/stale updates
 * and to evict rows older than the 28-day FCM delivery window.
 */
@Entity(tableName = "notification_history", primaryKeys = ["notificationId", "channelId"])
internal data class NotificationHistoryEntity (
    val notificationId: String,
    val channelId: String,
    val timestamp: Long
)