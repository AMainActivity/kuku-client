package dev.messenger.data

import androidx.room.Room
import dev.messenger.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class LocalMessagesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val account = AccountId(Uuid.random(), Uuid.random())
    private val chatId = ChatId(Uuid.random())
    private fun memoryDb() = buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication()))

    @Test fun enqueueIsAtomicAndRepeatDoesNotEraseNewDraft() = runTest {
        val db = memoryDb()
        try {
            val repo = RoomLocalMessages(db.messages(), account)
            val original = repo.saveDraft(chatId, "First")
            val operationId = OperationId(Uuid.random())
            repo.enqueueDraft(original, operationId, 100)
            val next = repo.saveDraft(chatId, "Next")
            repo.enqueueDraft(original, operationId, 100)
            assertEquals(next, repo.observeDraft(chatId).first())
            assertEquals(listOf(operationId), repo.readyToSend(10).map { it.operationId })
            assertFailsWith<IllegalArgumentException> {
                repo.enqueueDraft(original.copy(text = "Changed"), operationId, 100)
            }
        } finally { db.close() }
    }

    @Test fun staleDraftCannotReplaceNewDraftOrCreateOperation() = runTest {
        val db = memoryDb()
        try {
            val repo = RoomLocalMessages(db.messages(), account)
            val stale = repo.saveDraft(chatId, "Original")
            val current = repo.saveDraft(chatId, "Edited")
            assertFailsWith<IllegalArgumentException> { repo.enqueueDraft(stale, OperationId(Uuid.random()), 100) }
            assertEquals(current, repo.observeDraft(chatId).first())
            assertTrue(repo.readyToSend(10).isEmpty())
        } finally { db.close() }
    }

    @Test fun accountIsolationAndBlockedFileDoesNotBlockText() = runTest {
        val db = memoryDb()
        try {
            val dao = db.messages()
            val local = RoomLocalMessages(dao, account)
            val foreign = RoomLocalMessages(dao, AccountId(Uuid.random(), account.userId))
            val fileDraft = dao.saveDraft(account.storageKey, chatId.value.toString(), "file")
            dao.enqueue(fileDraft, OutboxEntity(account.storageKey, Uuid.random().toString(),
                chatId.value.toString(), "file", 1, OutboxState.WAITING_FOR_FILES.name))
            local.enqueueDraft(local.saveDraft(chatId, "text"), OperationId(Uuid.random()), 2)
            assertEquals(listOf("text"), local.readyToSend(10).map { it.text })
            assertEquals(2, local.observePending(chatId).first().size)
            assertTrue(foreign.observePending(chatId).first().isEmpty())
            assertNull(foreign.observeDraft(chatId).first())
        } finally { db.close() }
    }

    @Test fun pendingOperationAndDraftSurviveDatabaseReopen() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val name = java.io.File(temporaryFolder.root, "restart.db").absolutePath
        fun open() = buildDatabase(Room.databaseBuilder<MessengerDatabase>(context, name))
        val operationId = OperationId(Uuid.random())
        var db = open()
        try {
            val first = RoomLocalMessages(db.messages(), account)
            first.enqueueDraft(first.saveDraft(chatId, "Persisted"), operationId, 42)
            first.saveDraft(chatId, "Unsent draft")
            db.close()
            db = open()
            val restored = RoomLocalMessages(db.messages(), account)
            assertEquals(operationId, restored.readyToSend(10).single().operationId)
            assertEquals("Unsent draft", restored.observeDraft(chatId).first()?.text)
        } finally { db.close() }
    }
}
