package dev.messenger.data

import androidx.room.Room
import dev.messenger.data.wire.wireJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class ShareInboxTest {
    @Test fun deliveryPreservesDraftAndConsumesExactlyOnce()=runTest {
        val db=buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication()))
        try {
            val account="${Uuid.random()}:${Uuid.random()}"; val chat=Uuid.random().toString()
            val id=Uuid.random().toString(); val reply=Uuid.random().toString()
            db.messages().saveDraft(account,chat,"existing")
            db.messages().saveExtras(account,chat,reply,"[]")
            db.shares().insert(ShareInboxEntity(id,"shared","[]"))
            val transfer=TransferEntity(account,Uuid.random().toString(),chat,"UPLOAD","a","text/plain",1,"a".repeat(64))
            db.shares().deliver(id,account,chat,listOf(transfer))
            db.shares().deliver(id,account,chat,listOf(transfer))
            val draft=requireNotNull(db.messages().draft(account,chat))
            assertEquals("existing\nshared",draft.text)
            assertEquals(reply,draft.replyTo)
            assertEquals(listOf(transfer.jobId),wireJson.decodeFromString<List<String>>(draft.filesJson))
            assertTrue(db.shares().observe().first().isEmpty())
            assertEquals(transfer,db.transfers().get(account,transfer.jobId))
            assertTrue(db.messages().ready(account,10).isEmpty())
        } finally { db.close() }
    }
    @Test fun overflowingDraftDoesNotConsumeShareOrInsertTransfer()=runTest {
        val db=buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication()))
        try {
            val account="${Uuid.random()}:${Uuid.random()}"; val chat=Uuid.random().toString()
            val id=Uuid.random().toString()
            db.messages().saveDraft(account,chat,"existing")
            db.messages().saveExtras(account,chat,null,wireJson.encodeToString(List(10) { Uuid.random().toString() }))
            db.shares().insert(ShareInboxEntity(id,"shared","[]"))
            val transfer=TransferEntity(account,Uuid.random().toString(),chat,"UPLOAD","a","text/plain",1,"a".repeat(64))
            assertFailsWith<IllegalArgumentException> { db.shares().deliver(id,account,chat,listOf(transfer)) }
            assertEquals("existing",db.messages().draft(account,chat)?.text)
            assertEquals(id,db.shares().observe().first().single().shareId)
            assertNull(db.transfers().get(account,transfer.jobId))
        } finally { db.close() }
    }
}