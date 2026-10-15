package com.voiceproducer.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val prompt: String,
    val title: String,
    val sampleRate: Int,
    val channels: Int,
    val currentVersionId: Long?,
)
