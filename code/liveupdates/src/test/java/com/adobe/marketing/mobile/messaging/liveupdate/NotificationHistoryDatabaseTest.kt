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

import com.adobe.marketing.mobile.internal.util.SQLiteDatabaseHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationHistoryDatabaseTest {

    private lateinit var database: NotificationHistoryDatabase
    private lateinit var dbPath: String

    @Before
    fun setUp() {
        dbPath = File.createTempFile("notification_history_test", ".db")
            .apply { deleteOnExit() }.path
        database = NotificationHistoryDatabase(dbPath)
    }

    @Test
    fun `first-time key is accepted`() {
        assertTrue(database.recordIfNewer("id1", "chan1", 100L, expiresAt = 100_000L, now = 0L))
    }

    @Test
    fun `newer timestamp is accepted and replaces the stored value`() {
        database.recordIfNewer("id1", "chan1", 1000L, expiresAt = 100_000L, now = 0L)
        assertTrue(database.recordIfNewer("id1", "chan1", 2000L, expiresAt = 100_000L, now = 0L))
        assertEquals(2000L, queryTimestamp("id1", "chan1"))
    }

    @Test
    fun `equal timestamp is rejected as a duplicate`() {
        database.recordIfNewer("id1", "chan1", 1000L, expiresAt = 100_000L, now = 0L)
        assertFalse(database.recordIfNewer("id1", "chan1", 1000L, expiresAt = 100_000L, now = 0L))
    }

    @Test
    fun `eviction removes rows older than now on the next accepted write`() {
        database.recordIfNewer("old", "chan1", 1000L, expiresAt = 1500L, now = 0L)
        database.recordIfNewer("new", "chan1", 5000L, expiresAt = 6000L, now = 2000L)
        assertNull(queryTimestamp("old", "chan1"))
        assertEquals(5000L, queryTimestamp("new", "chan1"))
    }

    private fun queryTimestamp(notificationId: String, channelId: String): Long? {
        val db = SQLiteDatabaseHelper.openDatabase(dbPath, SQLiteDatabaseHelper.DatabaseOpenMode.READ_ONLY)
        try {
            db.rawQuery(
                "SELECT timestamp FROM notification_history WHERE notificationId = ? AND channelId = ?",
                arrayOf(notificationId, channelId)
            ).use { cursor ->
                return if (cursor.moveToFirst()) cursor.getLong(0) else null
            }
        } finally {
            SQLiteDatabaseHelper.closeDatabase(db)
        }
    }
}
