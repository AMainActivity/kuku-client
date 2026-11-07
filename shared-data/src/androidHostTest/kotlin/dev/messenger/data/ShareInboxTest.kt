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
    @get:org.junit.Rule val temporaryFolder=org.junit.rules.TemporaryFolder()

    @Test fun deletionDuringShareCopyRejectsDeliveryWithoutConsumingIt()=runTest {
        val db=buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication()))
        try {
            val account="${Uuid.random()}:${Uuid.random()}"
            val chat=Uuid.random().toString()
            val id=Uuid.random().toString()
            val transfer=TransferEntity(account,Uuid.random().toString(),chat,"UPLOAD","a","text/plain",1,"a".repeat(64))
            db.shares().insert(ShareInboxEntity(id,"shared","[]"))
            // The destination was selected before the event; byte copying finishes afterwards.
            db.conversations().revoke(account,chat,"group_deleted")
            val failure=assertFailsWith<dev.messenger.domain.ApplicationFailure> {
                db.shares().deliver(id,account,chat,listOf(transfer))
            }
            assertEquals("CHAT_UNAVAILABLE",failure.code)
            assertNull(db.messages().draft(account,chat))
            assertNull(db.transfers().next(account,"UPLOAD"))
            assertNull(db.transfers().get(account,transfer.jobId))
            assertEquals(id,db.shares().observe().first().single().shareId)
            assertFalse(requireNotNull(db.shares().get(id)).consumed)
        } finally {db.close()}
    }

    @Test fun rejectedImportKeepsStagedBytesAndCanBeDeliveredToAnotherChat()=runTest {
        val db=buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication()))
        try {
            val files=ManagedFiles(temporaryFolder.root.absolutePath)
            val shares=ShareInboxRepository(db.shares(),files)
            val account="${Uuid.random()}:${Uuid.random()}"
            val deleted=Uuid.random().toString()
            val other=Uuid.random().toString()
            val id=Uuid.random().toString()
            val stagedId=Uuid.random().toString()
            val bytes="Shared bytes".encodeToByteArray()
            files.write(ShareInboxRepository.STAGING_ACCOUNT,stagedId,0,bytes)
            shares.record(id,"shared",listOf(SharedFile(stagedId,"a.txt","text/plain",bytes.size.toLong(),files.hash(ShareInboxRepository.STAGING_ACCOUNT,stagedId))))
            db.conversations().revoke(account,deleted,"group_deleted")
            assertFailsWith<dev.messenger.domain.ApplicationFailure> {shares.accept(id,account,deleted)}
            assertContentEquals(bytes,files.read(ShareInboxRepository.STAGING_ACCOUNT,stagedId,0,bytes.size.toLong()))
            assertFalse(requireNotNull(db.shares().get(id)).consumed)
            assertTrue(db.transfers().all(account).isEmpty())
            val accountDirectory=assertNotNull(java.io.File(files.path(account,Uuid.random().toString())).parentFile)
            assertTrue(accountDirectory.listFiles().orEmpty().isEmpty(),"Rejected destination copies must be removed")
            shares.accept(id,account,other)
            val job=db.transfers().all(account).single()
            assertEquals(other,job.chatId)
            assertEquals("QUEUED",job.state)
            assertContentEquals(bytes,files.read(account,job.jobId,0,bytes.size.toLong()))
            assertEquals("shared",db.messages().draft(account,other)?.text)
            assertTrue(requireNotNull(db.shares().get(id)).consumed)
            assertEquals(0L,files.size(ShareInboxRepository.STAGING_ACCOUNT,stagedId))
        } finally {db.close()}
    }

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