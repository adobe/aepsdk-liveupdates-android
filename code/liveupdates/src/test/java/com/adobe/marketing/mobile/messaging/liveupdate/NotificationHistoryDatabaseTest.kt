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
import java.util.concurrent.TimeUnit

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
        assertTrue(database.recordIfNewer("id1", "chan1", 100L, expiresAt = 100_000L))
    }

    @Test
    fun `newer timestamp is accepted and replaces the stored value`() {
        database.recordIfNewer("id1", "chan1", 1000L, expiresAt = 100_000L)
        assertTrue(database.recordIfNewer("id1", "chan1", 2000L, expiresAt = 100_000L))
        assertEquals(2000L, queryTimestamp("id1", "chan1"))
    }

    @Test
    fun `equal timestamp is rejected as a duplicate`() {
        database.recordIfNewer("id1", "chan1", 1000L, expiresAt = 100_000L)
        assertFalse(database.recordIfNewer("id1", "chan1", 1000L, expiresAt = 100_000L))
    }

    @Test
    fun `deleteExpired removes only rows expiring before now`() {
        database.recordIfNewer("old", "chan1", 1000L, expiresAt = 1500L)
        database.recordIfNewer("new", "chan1", 5000L, expiresAt = 6000L)
        assertEquals(1, database.deleteExpired(now = 2000L))
        assertNull(queryTimestamp("old", "chan1"))
        assertEquals(5000L, queryTimestamp("new", "chan1"))
    }

    @Test
    fun `consumeLocalStart returns null when no local start was recorded`() {
        assertNull(database.consumeLocalStart("id1", "chan1"))
    }

    @Test
    fun `recordLocalStart then consumeLocalStart returns the stored millis once, then null`() {
        val nowMillis = System.currentTimeMillis()
        database.recordLocalStart("id1", "chan1", nowMillis)
        assertEquals(nowMillis, database.consumeLocalStart("id1", "chan1"))
        // Second consume: entry already removed, so the catch-up cannot fire twice.
        assertNull(database.consumeLocalStart("id1", "chan1"))
    }

    @Test
    fun `local start registry is keyed by notificationId plus channelId`() {
        val nowMillis = System.currentTimeMillis()
        database.recordLocalStart("id1", "chanA", nowMillis)
        // Same id, different channel is a different key.
        assertNull(database.consumeLocalStart("id1", "chanB"))
        assertEquals(nowMillis, database.consumeLocalStart("id1", "chanA"))
    }

    @Test
    fun `recordLocalStart twice for the same key updates the stored timestamp`() {
        val first = System.currentTimeMillis()
        val second = first + 5000L
        database.recordLocalStart("id1", "chan1", first)
        // A second local start for the same (id, channel) upserts the single row with the newer
        // time - it must not add a second row.
        database.recordLocalStart("id1", "chan1", second)
        assertEquals(second, database.consumeLocalStart("id1", "chan1"))
        // Only one row existed, so the catch-up can fire exactly once.
        assertNull(database.consumeLocalStart("id1", "chan1"))
    }

    @Test
    fun `recordLocalStart does not move the stored timestamp backwards`() {
        val later = System.currentTimeMillis()
        val earlier = later - 5000L
        database.recordLocalStart("id1", "chan1", later)
        // An older repeat local start for the same key is ignored (update-if-newer only).
        database.recordLocalStart("id1", "chan1", earlier)
        assertEquals(later, database.consumeLocalStart("id1", "chan1"))
    }

    @Test
    fun `recordLocalStart evicts entries older than the 28 day TTL`() {
        val nowMillis = System.currentTimeMillis()
        database.recordLocalStart("stale", "chan1", nowMillis - TimeUnit.DAYS.toMillis(29))
        database.recordLocalStart("fresh", "chan1", nowMillis)
        assertNull(database.consumeLocalStart("stale", "chan1"))
        assertEquals(nowMillis, database.consumeLocalStart("fresh", "chan1"))
    }

    @Test
    fun `deleteExpired also evicts stale local starts and keeps fresh ones`() {
        val nowSeconds = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())
        val nowMillis = TimeUnit.SECONDS.toMillis(nowSeconds)
        // Both rows are fresh when written (so recordLocalStart's on-write eviction keeps them).
        database.recordLocalStart("aged", "chan1", nowMillis)
        database.recordLocalStart("survivor", "chan1", nowMillis + TimeUnit.DAYS.toMillis(40))
        // Simulate the post-render cleanup pass running 29 days later: "aged" is now past the
        // 28-day TTL, "survivor" is not.
        database.deleteExpired(now = nowSeconds + TimeUnit.DAYS.toSeconds(29))
        assertNull(database.consumeLocalStart("aged", "chan1"))
        assertEquals(nowMillis + TimeUnit.DAYS.toMillis(40), database.consumeLocalStart("survivor", "chan1"))
    }

    @Test
    fun `recordIfNewer no longer evicts expired rows`() {
        database.recordIfNewer("old", "chan1", 1000L, expiresAt = 1500L)
        database.recordIfNewer("new", "chan1", 5000L, expiresAt = 6000L)
        assertEquals(1000L, queryTimestamp("old", "chan1"))
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
