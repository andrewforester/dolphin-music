package com.voiceproducer.core.data

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.voiceproducer.core.data.repository.HistoryRepository
import com.voiceproducer.core.database.buildAppDatabase
import com.voiceproducer.core.model.MessageRole
import com.voiceproducer.core.model.StoredMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MigrationTest {
    private val dir = File(System.getProperty("java.io.tmpdir"), "voiceproducer-migration-${System.nanoTime()}")
    private val file = File(dir, "voiceproducer.db")

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun writeVersion1() {
        dir.mkdirs()
        val conn = BundledSQLiteDriver().open(file.absolutePath)
        listOf(
            "CREATE TABLE IF NOT EXISTS `tracks` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`createdAt` INTEGER NOT NULL, `prompt` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`sampleRate` INTEGER NOT NULL, `channels` INTEGER NOT NULL, `currentVersionId` INTEGER)",
            "CREATE TABLE IF NOT EXISTS `versions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`trackId` INTEGER NOT NULL, `number` INTEGER NOT NULL, `parentVersionId` INTEGER, " +
                "`planJson` TEXT NOT NULL, `sourceMapJson` TEXT NOT NULL, `audioPath` TEXT NOT NULL, " +
                "`editedSection` TEXT, `diffRangesJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_versions_trackId_number` ON `versions` (`trackId`, `number`)",
            "CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)",
            "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '4f003daae9ed9f606fc46c1f17a6b4e2')",
            "INSERT INTO tracks VALUES (1, 5, 'lofi', 'Lofi', 44100, 2, 1)",
            "INSERT INTO versions VALUES (1, 1, 1, NULL, '{\"chunks\":[]}', '[]', 'v1.wav', NULL, '[]', 6)",
            "PRAGMA user_version = 1",
        ).forEach(conn::execSQL)
        conn.close()
    }

    @Test
    fun version1TracksSurviveTheMigrationAndHistoryWorks() =
        runTest {
            writeVersion1()
            val db = buildAppDatabase(file)
            try {
                val track = db.trackDao().latestTrack()!!
                assertEquals("Lofi", track.title)
                assertEquals(1L, track.currentVersionId)
                assertEquals("v1.wav", db.trackDao().version(1)!!.audioPath)
                val history = HistoryRepository(db.messageDao(), clock = { 0 }, ioDispatcher = Dispatchers.IO)
                history.append(track.id, StoredMessage(MessageRole.User, "hi"))
                assertEquals(listOf(StoredMessage(MessageRole.User, "hi")), history.recent(track.id, limit = 5).map { it.message })
            } finally {
                db.close()
            }
        }
}
