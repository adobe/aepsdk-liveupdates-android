package com.adobe.marketing.mobile.messaging.liveupdate

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
internal interface NotificationHistoryDao {

    /**
     * @return the last stored timestamp for this ([notificationId], [channelId]) key,
     * or null if no row exists yet.
     */
    @Query(
        "SELECT timestamp FROM notification_history " +
        "WHERE notificationId = :notificationId AND channelId = :channelId"
    )
    fun getTimestamp(notificationId: String, channelId: String): Long?

    /**
     * Creates or updates the tracked timestamp for a (notificationId, channelId) key.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: NotificationHistoryEntity)

    /**
     * Deletes rows whose timestamp is older than [cutoff] (caller computes `now - 28days`).
     * @return number of rows deleted.
     */
    @Query("DELETE FROM notification_history WHERE timestamp < :cutoff")
    fun evictOlderThan(cutoff: Long): Int

    /**
     * Atomically checks [newTimestamp] against the last stored value for this
     * (notificationId, channelId) key and, if it is not a regression, upserts it and evicts
     * rows older than [cutoff]. Wrapping the read + write in one `@Transaction` closes the
     * check-then-act race that would otherwise exist if two calls for the same key ran
     * concurrently (e.g. FCM redelivery).
     *
     * @return `true` if [newTimestamp] was accepted and recorded; `false` if it was rejected
     * as older than the last stored timestamp.
     */
    @Transaction
    fun recordIfNewer(
        notificationId: String,
        channelId: String,
        newTimestamp: Long,
        cutoff: Long
    ): Boolean {
        val lastTimestamp = getTimestamp(notificationId, channelId)
        if (lastTimestamp != null && newTimestamp < lastTimestamp) {
            return false
        }
        upsert(NotificationHistoryEntity(notificationId, channelId, newTimestamp))
        evictOlderThan(cutoff)
        return true
    }
}