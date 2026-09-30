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

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.adobe.marketing.mobile.internal.util.SQLiteDatabaseHelper
import com.adobe.marketing.mobile.services.Log
import com.adobe.marketing.mobile.services.ServiceProvider
import java.util.concurrent.TimeUnit

/**
 * Local history of the last timestamp seen per (notificationId, channelId), backed by a raw
 * SQLiteDatabase rather than Room.
 *
 * This mirrors core's `AndroidEventHistoryDatabase` implementation pattern (same
 * `SQLiteDatabaseHelper` open/close/create-table calls, same mutex-guarded single-connection
 * approach) per the team decision to inherit/adapt core's existing DB approach instead of
 * introducing a new persistence library.
 *
 * TODO: core does not yet expose a first-class public API for extensions to reuse its history
 * DB implementation directly; `SQLiteDatabaseHelper` lives under core's `internal.util` package
 * and is not a supported cross-module contract, only usable today because Java does not enforce
 * Kotlin's module-level `internal` visibility. Revisit once core exposes a proper API.
 */
internal class NotificationHistoryDatabase internal constructor(private val databasePath: String) {

    private val dbMutex = Any()

    init {
        val tableCreationQuery =
            "CREATE TABLE IF NOT EXISTS $TABLE_NAME (" +
                "$COLUMN_NOTIFICATION_ID TEXT NOT NULL, " +
                "$COLUMN_CHANNEL_ID TEXT NOT NULL, " +
                "$COLUMN_TIMESTAMP INTEGER NOT NULL, " +
                "$COLUMN_EXPIRES_AT INTEGER NOT NULL, " +
                "PRIMARY KEY ($COLUMN_NOTIFICATION_ID, $COLUMN_CHANNEL_ID));"
        // Registry of Live Updates started locally via LiveUpdates.triggerLocalLiveUpdate. Keyed by
        // (notificationId, channelId); eventTimestamp is epoch MILLISECONDS captured when the local
        // start happened - the same clock/unit an Event's own timestamp uses, so the deferred
        // localstart tracking event can be stamped with it. (The history table's `timestamp` is
        // epoch seconds from the payload, used only for stale-update checks.) An entry is removed
        // when the first backend update/end arrives for it (the localstart catch-up), see
        // consumeLocalStart.
        val localStartTableQuery =
            "CREATE TABLE IF NOT EXISTS $TABLE_LOCAL_STARTS (" +
                "$COLUMN_NOTIFICATION_ID TEXT NOT NULL, " +
                "$COLUMN_CHANNEL_ID TEXT NOT NULL, " +
                "$COLUMN_EVENT_TIMESTAMP INTEGER NOT NULL, " +
                "PRIMARY KEY ($COLUMN_NOTIFICATION_ID, $COLUMN_CHANNEL_ID));"
        synchronized(dbMutex) {
            SQLiteDatabaseHelper.createTableIfNotExist(databasePath, tableCreationQuery)
            SQLiteDatabaseHelper.createTableIfNotExist(databasePath, localStartTableQuery)
        }
    }

    /**
     * Checks [newTimestamp] against the last stored value for this (notificationId, channelId)
     * key and, if it is strictly newer, upserts it (along with [expiresAt], precomputed by the
     * caller as `newTimestamp + TTL`). Expired-row eviction is handled separately by
     * [deleteExpired] so it stays off the render path.
     *
     * @return `true` if [newTimestamp] was accepted and recorded; `false` if it was rejected -
     * either older than, or exactly equal to (a duplicate delivery), the last stored timestamp.
     */
    fun recordIfNewer(
        notificationId: String,
        channelId: String,
        newTimestamp: Long,
        expiresAt: Long
    ): Boolean {
        synchronized(dbMutex) {
            var database: SQLiteDatabase? = null
            try {
                database = SQLiteDatabaseHelper.openDatabase(
                    databasePath,
                    SQLiteDatabaseHelper.DatabaseOpenMode.READ_WRITE
                )

                val lastTimestamp = queryTimestamp(database, notificationId, channelId)
                if (lastTimestamp != null && newTimestamp <= lastTimestamp) {
                    return false
                }

                val contentValues = ContentValues().apply {
                    put(COLUMN_NOTIFICATION_ID, notificationId)
                    put(COLUMN_CHANNEL_ID, channelId)
                    put(COLUMN_TIMESTAMP, newTimestamp)
                    put(COLUMN_EXPIRES_AT, expiresAt)
                }
                database.insertWithOnConflict(
                    TABLE_NAME,
                    null,
                    contentValues,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                return true
            } finally {
                SQLiteDatabaseHelper.closeDatabase(database)
            }
        }
    }

    fun deleteExpired(now: Long): Int {
        synchronized(dbMutex) {
            var database: SQLiteDatabase? = null
            try {
                database = SQLiteDatabaseHelper.openDatabase(
                    databasePath,
                    SQLiteDatabaseHelper.DatabaseOpenMode.READ_WRITE
                )
                return database.delete(
                    TABLE_NAME,
                    "$COLUMN_EXPIRES_AT < ?",
                    arrayOf(now.toString())
                )
            } finally {
                SQLiteDatabaseHelper.closeDatabase(database)
            }
        }
    }

    private fun queryTimestamp(
        database: SQLiteDatabase,
        notificationId: String,
        channelId: String
    ): Long? {
        val cursor = database.rawQuery(
            "SELECT $COLUMN_TIMESTAMP FROM $TABLE_NAME " +
                "WHERE $COLUMN_NOTIFICATION_ID = ? AND $COLUMN_CHANNEL_ID = ?",
            arrayOf(notificationId, channelId)
        )
        cursor.use {
            if (!it.moveToFirst()) {
                return null
            }
            return it.getLong(0)
        }
    }

    // ---------- Locally-started Live Update registry ----------

    /**
     * Records that the Live Update identified by ([notificationId], [channelId]) was started
     * locally at [eventTimestampMillis] (epoch milliseconds). Upserts the row.
     *
     * Opportunistically evicts entries older than [LOCAL_START_TTL_MILLIS] (a locally-started
     * activity that never received a backend update/end would otherwise linger).
     *
     * TODO(local-start-cleanup): this on-write eviction only runs when another local start is
     *   recorded. For guaranteed cleanup of stale entries, revisit with either a periodic/external
     *   task or an eviction pass tied to notification render. Aligned with the timestamp table's
     *   own eviction approach.
     */
    fun recordLocalStart(notificationId: String, channelId: String, eventTimestampMillis: Long) {
        synchronized(dbMutex) {
            var database: SQLiteDatabase? = null
            try {
                database = SQLiteDatabaseHelper.openDatabase(
                    databasePath,
                    SQLiteDatabaseHelper.DatabaseOpenMode.READ_WRITE
                )
                val contentValues = ContentValues().apply {
                    put(COLUMN_NOTIFICATION_ID, notificationId)
                    put(COLUMN_CHANNEL_ID, channelId)
                    put(COLUMN_EVENT_TIMESTAMP, eventTimestampMillis)
                }
                database.insertWithOnConflict(
                    TABLE_LOCAL_STARTS,
                    null,
                    contentValues,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
                val cutoff = System.currentTimeMillis() - LOCAL_START_TTL_MILLIS
                database.delete(TABLE_LOCAL_STARTS, "$COLUMN_EVENT_TIMESTAMP < ?", arrayOf(cutoff.toString()))
            } finally {
                SQLiteDatabaseHelper.closeDatabase(database)
            }
        }
    }

    /**
     * Atomically reads and removes the locally-started registry entry for ([notificationId],
     * [channelId]). Returns the recorded start time (epoch milliseconds) when an entry existed
     * (the caller should fire the one-time localstart catch-up, stamped with that time), or `null`
     * otherwise. The delete guarantees the catch-up fires only once even if several backend
     * events arrive.
     */
    fun consumeLocalStart(notificationId: String, channelId: String): Long? {
        synchronized(dbMutex) {
            var database: SQLiteDatabase? = null
            try {
                database = SQLiteDatabaseHelper.openDatabase(
                    databasePath,
                    SQLiteDatabaseHelper.DatabaseOpenMode.READ_WRITE
                )
                val whereClause = "$COLUMN_NOTIFICATION_ID = ? AND $COLUMN_CHANNEL_ID = ?"
                val whereArgs = arrayOf(notificationId, channelId)
                val eventTimestamp = database.rawQuery(
                    "SELECT $COLUMN_EVENT_TIMESTAMP FROM $TABLE_LOCAL_STARTS WHERE $whereClause",
                    whereArgs
                ).use { if (it.moveToFirst()) it.getLong(0) else null }
                if (eventTimestamp != null) {
                    database.delete(TABLE_LOCAL_STARTS, whereClause, whereArgs)
                }
                return eventTimestamp
            } finally {
                SQLiteDatabaseHelper.closeDatabase(database)
            }
        }
    }

    companion object {
        private const val LOG_TAG = "NotificationHistoryDatabase"
        private const val DATABASE_NAME = "com.adobe.module.liveupdates.notificationhistory"
        private const val TABLE_NAME = "notification_history"
        private const val COLUMN_NOTIFICATION_ID = "notificationId"
        private const val COLUMN_CHANNEL_ID = "channelId"
        private const val COLUMN_TIMESTAMP = "timestamp"
        private const val COLUMN_EXPIRES_AT = "expiresAt"

        // Locally-started Live Update registry (same DB, separate table).
        private const val TABLE_LOCAL_STARTS = "local_started_live_updates"
        private const val COLUMN_EVENT_TIMESTAMP = "eventTimestamp"
        private val LOCAL_START_TTL_MILLIS = TimeUnit.DAYS.toMillis(28)

        @Volatile
        private var instance: NotificationHistoryDatabase? = null

        fun getInstance(): NotificationHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = ServiceProvider.getInstance()
                        .appContextService.applicationContext
                        ?: throw IllegalStateException(
                            "Failed to create $DATABASE_NAME database: ApplicationContext is null"
                        )
                    val databaseFile = appContext.getDatabasePath(DATABASE_NAME)
                    Log.trace(
                        LiveUpdatesConstants.LOG_TAG,
                        LOG_TAG,
                        "Opening NotificationHistory database at ${databaseFile.path}"
                    )
                    NotificationHistoryDatabase(databaseFile.path).also { instance = it }
                }
            }
    }
}
