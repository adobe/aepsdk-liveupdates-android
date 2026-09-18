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
internal class NotificationHistoryDatabase private constructor(private val databasePath: String) {

    private val dbMutex = Any()

    init {
        val tableCreationQuery =
            "CREATE TABLE IF NOT EXISTS $TABLE_NAME (" +
                "$COLUMN_NOTIFICATION_ID TEXT NOT NULL, " +
                "$COLUMN_CHANNEL_ID TEXT NOT NULL, " +
                "$COLUMN_TIMESTAMP INTEGER NOT NULL, " +
                "PRIMARY KEY ($COLUMN_NOTIFICATION_ID, $COLUMN_CHANNEL_ID));"
        synchronized(dbMutex) {
            SQLiteDatabaseHelper.createTableIfNotExist(databasePath, tableCreationQuery)
        }
    }

    /**
     * Checks [newTimestamp] against the last stored value for this (notificationId, channelId)
     * key and, if it is strictly newer, upserts it and evicts rows older than [cutoff]. The
     * whole operation runs on one connection inside a single synchronized block, so no other
     * call on this instance can interleave with it.
     *
     * @return `true` if [newTimestamp] was accepted and recorded; `false` if it was rejected -
     * either older than, or exactly equal to (a duplicate delivery), the last stored timestamp.
     */
    fun recordIfNewer(
        notificationId: String,
        channelId: String,
        newTimestamp: Long,
        cutoff: Long
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
                }
                database.insertWithOnConflict(
                    TABLE_NAME,
                    null,
                    contentValues,
                    SQLiteDatabase.CONFLICT_REPLACE
                )

                database.delete(TABLE_NAME, "$COLUMN_TIMESTAMP < ?", arrayOf(cutoff.toString()))
                return true
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

    companion object {
        private const val LOG_TAG = "NotificationHistoryDatabase"
        private const val DATABASE_NAME = "com.adobe.module.liveupdates.notificationhistory"
        private const val TABLE_NAME = "notification_history"
        private const val COLUMN_NOTIFICATION_ID = "notificationId"
        private const val COLUMN_CHANNEL_ID = "channelId"
        private const val COLUMN_TIMESTAMP = "timestamp"

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
