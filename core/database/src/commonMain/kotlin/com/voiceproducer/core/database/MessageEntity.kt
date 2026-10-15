package com.voiceproducer.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(entity = TrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index(value = ["trackId"])],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long?,
    val createdAt: Long,
    val role: String,
    val text: String,
    val toolKind: String?,
    val toolStatus: String?,
    val toolSection: String?,
    val toolDetail: String?,
)
