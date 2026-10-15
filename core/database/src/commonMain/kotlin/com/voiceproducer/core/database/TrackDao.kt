package com.voiceproducer.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class TrackDao {
    @Insert
    abstract suspend fun insertTrack(track: TrackEntity): Long

    @Insert
    abstract suspend fun insertVersion(version: VersionEntity): Long

    @Query(
        "UPDATE tracks SET currentVersionId = :versionId WHERE id = :trackId " +
            "AND EXISTS (SELECT 1 FROM versions WHERE id = :versionId AND trackId = :trackId)",
    )
    abstract suspend fun setCurrentVersion(
        trackId: Long,
        versionId: Long,
    ): Int

    @Query("UPDATE tracks SET currentVersionId = NULL")
    abstract suspend fun clearCurrentVersions()

    @Query("SELECT * FROM tracks WHERE currentVersionId IS NOT NULL ORDER BY createdAt DESC, id DESC LIMIT 1")
    abstract suspend fun latestTrack(): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id")
    abstract suspend fun track(id: Long): TrackEntity?

    @Query("SELECT * FROM versions WHERE id = :id")
    abstract suspend fun version(id: Long): VersionEntity?

    @Query("SELECT audioPath FROM versions")
    abstract suspend fun audioPaths(): List<String>

    @Query("SELECT COALESCE(MAX(number), 0) FROM versions WHERE trackId = :trackId")
    abstract suspend fun maxVersionNumber(trackId: Long): Int

    @Transaction
    open suspend fun insertTrackWithVersion(
        track: TrackEntity,
        build: (trackId: Long) -> VersionEntity,
    ): Long {
        val trackId = insertTrack(track)
        val versionId = insertVersion(build(trackId))
        setCurrentVersion(trackId, versionId)
        return versionId
    }

    @Transaction
    open suspend fun appendVersion(
        trackId: Long,
        build: (number: Int) -> VersionEntity,
    ): Long {
        val versionId = insertVersion(build(maxVersionNumber(trackId) + 1))
        setCurrentVersion(trackId, versionId)
        return versionId
    }
}
