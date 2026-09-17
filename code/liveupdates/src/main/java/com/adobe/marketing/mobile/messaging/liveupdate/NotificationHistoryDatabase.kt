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

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.adobe.marketing.mobile.services.ServiceProvider

@Database(entities = [NotificationHistoryEntity::class], version = 1, exportSchema = false)
internal abstract class NotificationHistoryDatabase : RoomDatabase() {

    abstract fun notificationHistoryDao(): NotificationHistoryDao

    companion object {
        private const val DATABASE_NAME = "com.adobe.module.liveupdates.notificationhistory"

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
                    Room.databaseBuilder(
                        appContext,
                        NotificationHistoryDatabase::class.java,
                        DATABASE_NAME
                    ).build().also { instance = it }
                }
            }
    }
}