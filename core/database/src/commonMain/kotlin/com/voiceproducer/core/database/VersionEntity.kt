package com.voiceproducer.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "versions",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["trackId", "number"], unique = true)],
)
data class VersionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val number: Int,
    val parentVersionId: Long?,
    val planJson: String,
    val sourceMapJson: String,
    val audioPath: String,
    val editedSection: String?,
    val diffRangesJson: String,
    val createdAt: Long,
)
