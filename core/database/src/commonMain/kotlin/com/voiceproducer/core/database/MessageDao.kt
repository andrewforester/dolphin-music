package com.voiceproducer.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query(
        "UPDATE messages SET text = :text, toolStatus = :toolStatus, toolSection = :toolSection, " +
            "toolDetail = :toolDetail WHERE id = :id",
    )
    suspend fun update(
        id: Long,
        text: String,
        toolStatus: String?,
        toolSection: String?,
        toolDetail: String?,
    )

    @Query("SELECT * FROM messages WHERE trackId IS :trackId ORDER BY id DESC LIMIT :limit")
    suspend fun latest(
        trackId: Long?,
        limit: Int,
    ): List<MessageEntity>

    @Query("UPDATE messages SET trackId = :trackId WHERE trackId IS NULL")
    suspend fun assignPending(trackId: Long)

    @Query("DELETE FROM messages WHERE trackId IS :trackId")
    suspend fun clear(trackId: Long?)
}
