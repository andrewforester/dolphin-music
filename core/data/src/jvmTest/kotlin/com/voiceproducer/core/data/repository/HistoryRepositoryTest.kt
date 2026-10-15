package com.voiceproducer.core.data.repository

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.voiceproducer.core.database.AppDatabase
import com.voiceproducer.core.database.TrackEntity
import com.voiceproducer.core.model.HistoryEntry
import com.voiceproducer.core.model.MessageRole
import com.voiceproducer.core.model.StoredMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryRepositoryTest {
    private val db: AppDatabase = Room.inMemoryDatabaseBuilder<AppDatabase>().setDriver(BundledSQLiteDriver()).build()
    private var now = 100L
    private val repo = HistoryRepository(db.messageDao(), clock = { now++ }, ioDispatcher = Dispatchers.IO)

    @AfterTest
    fun close() = db.close()

    private suspend fun track(): Long =
        db.trackDao().insertTrack(
            TrackEntity(createdAt = 1, prompt = "p", title = "t", sampleRate = 1, channels = 1, currentVersionId = null),
        )

    private fun user(text: String) = StoredMessage(MessageRole.User, text)

    private fun tool(
        status: String,
        section: String? = null,
    ) = StoredMessage(
        MessageRole.Tool,
        "Changing a section",
        toolKind = "EditSection",
        toolStatus = status,
        toolSection = section,
        toolDetail = "brass",
    )

    @Test
    fun appendsAndLoadsTheLastMessagesOfATrackOldestFirst() =
        runTest {
            val a = track()
            val b = track()
            repo.append(a, user("one"))
            repo.append(b, user("other track"))
            repo.append(a, StoredMessage(MessageRole.Agent, "two"))
            repo.append(a, tool("Done", "Chorus"))
            assertEquals(
                listOf(StoredMessage(MessageRole.Agent, "two"), tool("Done", "Chorus")),
                repo.messages(a, limit = 2),
            )
            assertEquals(listOf(user("other track")), repo.messages(b, limit = 10))
        }

    @Test
    fun updatesAToolLineInPlace() =
        runTest {
            val a = track()
            val id = repo.append(a, tool("Running"))
            repo.append(a, user("after"))
            repo.update(id, tool("Done", "Verse 2"))
            assertEquals(listOf(tool("Done", "Verse 2"), user("after")), repo.messages(a, limit = 10))
        }

    @Test
    fun pendingMessagesMoveToTheNewTrack() =
        runTest {
            repo.append(null, user("brief"))
            repo.append(null, tool("Running"))
            assertEquals(2, repo.messages(null, limit = 10).size)
            val a = track()
            repo.assignPending(a)
            assertTrue(repo.messages(null, limit = 10).isEmpty())
            assertEquals(listOf(user("brief"), tool("Running")), repo.messages(a, limit = 10))
        }

    @Test
    fun clearDropsOnlyThatBucket() =
        runTest {
            val a = track()
            repo.append(a, user("kept"))
            repo.append(null, user("pending"))
            repo.clear(null)
            assertTrue(repo.messages(null, limit = 10).isEmpty())
            assertEquals(listOf(user("kept")), repo.messages(a, limit = 10))
            repo.clear(a)
            assertTrue(repo.messages(a, limit = 10).isEmpty())
        }

    @Test
    fun recentCarriesTheIdsUpdatesUse() =
        runTest {
            val a = track()
            val id = repo.append(a, user("one"))
            assertEquals(listOf(HistoryEntry(id, user("one"))), repo.recent(a, limit = 5))
        }

    private suspend fun HistoryRepository.messages(
        trackId: Long?,
        limit: Int,
    ) = recent(trackId, limit).map { it.message }
}
