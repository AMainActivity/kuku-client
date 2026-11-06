package dev.messenger.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.messenger.data.wire.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.*
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], manifest=Config.NONE)
class PersistenceTest {
    private val account="${Uuid.random()}:${Uuid.random()}"
    private val chat=Uuid.random().toString()
    private fun db()=buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication()))
    @Test fun staleMemberRefreshCannotRollBackReceiptCursors()=runTest {
        val database=db()
        try {
            val dao=database.conversations()
            val id=Uuid.random().toString()
            fun row(value:MemberInfo)=MemberCacheEntity(account,chat,id,wireJson.encodeToString(value))
            val fresh=MemberInfo(id,"Updated","human",null,"admin","active",2,25,20)
            dao.cacheMembers(listOf(row(fresh)))
            dao.cacheMembers(listOf(row(fresh.copy(displayName="Old",version=1,deliveredSeq=10,readSeq=5))))
            val actual=wireJson.decodeFromString<MemberInfo>(dao.members(account,chat).single().json)
            assertEquals(fresh,actual)
            dao.cacheMembers(listOf(row(fresh.copy(deliveredSeq=30,readSeq=26))))
            assertEquals(26L,wireJson.decodeFromString<MemberInfo>(dao.members(account,chat).single().json).readSeq)
        } finally {database.close()}
    }
    @Test fun unknownEventAndCursorCommitTogetherAndInvalidPageRollsBack()=runTest {
        val database=db()
        try {
            val dao=database.conversations()
            dao.installCatalog(account,0,"generation")
            val unknown=SyncEvent(5,"future.event",buildJsonObject { put("opaque","retained") },"2026-11-05T00:00:00Z")
            dao.applyPage(account,0,SyncPage(listOf(unknown),5,false,5),emptyList(),emptyList(),emptySet())
            assertEquals(5L,dao.syncState(account)?.cursor)
            assertFailsWith<IllegalArgumentException> {
                dao.applyPage(account,5,SyncPage(listOf(SyncEvent(7,"future",buildJsonObject {},""),SyncEvent(6,"future",buildJsonObject {},"")),6,false,7),emptyList(),emptyList(),emptySet())
            }
            assertEquals(5L,dao.syncState(account)?.cursor)
            assertFailsWith<IllegalArgumentException> {
                dao.applyPage(account,0,SyncPage(emptyList(),0,false,0),emptyList(),emptyList(),emptySet())
            }
        } finally { database.close() }
    }
    @Test fun fullDraftEnqueueAndTransferPauseAreDurable()=runTest {
        val database=db()
        try {
            val messages=database.messages()
            val reply=Uuid.random().toString()
            val jobId=Uuid.random().toString()
            messages.saveDraft(account,chat,"body")
            messages.saveExtras(account,chat,reply,wireJson.encodeToString(listOf(jobId)))
            val original=requireNotNull(messages.draft(account,chat))
            val changed=messages.saveDraft(account,chat,"changed")
            assertEquals(reply,changed.replyTo)
            assertEquals(original.filesJson,changed.filesJson)
            messages.enqueue(changed,OutboxEntity(account,Uuid.random().toString(),chat,changed.text,1,"WAITING_FOR_FILES",reply))
            assertEquals("[]",messages.draft(account,chat)?.filesJson)
            assertNull(messages.draft(account,chat)?.replyTo)
            val transfers=database.transfers()
            transfers.put(TransferEntity(account,jobId,chat,"UPLOAD","a","text/plain",1,"a".repeat(64)))
            transfers.state(account,jobId,"PAUSED")
            transfers.progress(account,jobId,1,null)
            transfers.outcome(account,jobId,"READY",null)
            assertEquals("PAUSED",transfers.get(account,jobId)?.state)
            assertNull(transfers.next(account,"UPLOAD"))
            assertTrue(messages.ready(account,20).isEmpty())
        } finally { database.close() }
    }
    @Test fun expiredUploadRetryKeepsDraftReferenceButRotatesRemoteOperations()=runTest {
        val database=db()
        try {
            val dao=database.transfers()
            val job=TransferEntity(account,Uuid.random().toString(),chat,"UPLOAD","a","text/plain",1,"a".repeat(64),state="FAILED",remoteId=Uuid.random().toString(),error="UPLOAD_EXPIRED")
            dao.put(job)
            val create=Uuid.random().toString(); val complete=Uuid.random().toString(); val cancel=Uuid.random().toString()
            assertEquals(1,dao.restartExpired(account,job.jobId,create,complete,cancel))
            val restarted=requireNotNull(dao.get(account,job.jobId))
            assertEquals(job.jobId,restarted.jobId)
            assertEquals("QUEUED",restarted.state)
            assertNull(restarted.remoteId)
            assertEquals(create,restarted.createId)
            assertEquals(complete,restarted.completeId)
            assertEquals(cancel,restarted.cancelId)
            assertEquals(0,dao.restartExpired(account,job.jobId,"unexpected","unexpected","unexpected"))
            assertEquals(create,dao.get(account,job.jobId)?.createId)
        } finally { database.close() }
    }
    @Test fun versionOneMigrationPreservesPendingAndDraft()=runTest {
        val context=RuntimeEnvironment.getApplication()
        val name="migration-${Uuid.random()}.db"
        val path=context.getDatabasePath(name)
        path.parentFile?.mkdirs()
        val schema=Json.parseToJsonElement(File(requireNotNull(System.getProperty("messenger.schemaDirectory")),"dev.messenger.data.MessengerDatabase/1.json").readText()).jsonObject.getValue("database").jsonObject
        val connection=BundledSQLiteDriver().open(path.absolutePath)
        fun sql(value:String) { connection.prepare(value).use { it.step() } }
        try {
            for(entity in requireNotNull(schema["entities"]).jsonArray) sql(requireNotNull(entity.jsonObject["createSql"]).jsonPrimitive.content.replace("\${TABLE_NAME}",requireNotNull(entity.jsonObject["tableName"]).jsonPrimitive.content))
            for(entity in requireNotNull(schema["entities"]).jsonArray) {
                for(index in entity.jsonObject["indices"]?.jsonArray.orEmpty()) sql(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",entity.jsonObject.getValue("tableName").jsonPrimitive.content))
            }
            for(query in requireNotNull(schema["setupQueries"]).jsonArray) sql(query.jsonPrimitive.content)
            sql("PRAGMA user_version=1")
            sql("INSERT INTO drafts VALUES ('$account','$chat','preserved',42)")
            sql("INSERT INTO outbox VALUES ('$account','${Uuid.random()}','$chat','pending',1,'READY')")
        } finally { connection.close() }
        val database=buildDatabase(Room.databaseBuilder<MessengerDatabase>(context,name))
        try {
            assertEquals("preserved",database.messages().draft(account,chat)?.text)
            assertEquals(42L,database.messages().draft(account,chat)?.revision)
            assertEquals("[]",database.messages().draft(account,chat)?.filesJson)
            assertEquals("pending",database.messages().ready(account,1).single().text)
        } finally { database.close(); context.deleteDatabase(name) }
    }
    @Test fun cachedProfileSurvivesReopenAndIsAccountScoped()=runTest {
        val context=RuntimeEnvironment.getApplication()
        val name="profile-${Uuid.random()}.db"
        val other="${Uuid.random()}:${Uuid.random()}"
        val first=buildDatabase(Room.databaseBuilder<MessengerDatabase>(context,name))
        try {
            first.profiles().put(AccountProfileEntity(account,"profile-a"))
            first.profiles().put(AccountProfileEntity(other,"profile-b"))
        } finally { first.close() }
        val reopened=buildDatabase(Room.databaseBuilder<MessengerDatabase>(context,name))
        try {
            assertEquals("profile-a",reopened.profiles().observe(account).first()?.json)
            reopened.profiles().clear(account)
            assertNull(reopened.profiles().observe(account).first())
            assertEquals("profile-b",reopened.profiles().observe(other).first()?.json)
        } finally { reopened.close();context.deleteDatabase(name) }
    }
    @Test fun historyWindowIsBoundedWithoutDeletingCachedRows()=runTest {
        val database=db()
        try {
            val dao=database.conversations()
            val messages=(1L..400L).map { seq -> Message(Uuid.random().toString(),Uuid.random().toString(),chat,Uuid.random().toString(),seq,1,null,null,false,"",null,emptyList()) }
            dao.cacheMessages(account,messages)
            assertEquals((251L..400L).toList(),dao.observeWindow(account,chat,0,null,150).first().map {it.seq})
            assertEquals((101L..250L).toList(),dao.observeWindow(account,chat,101,250,150).first().map {it.seq})
            assertTrue(dao.observeWindow("other",chat,0,null,150).first().isEmpty())
            assertEquals(400,dao.observeHistory(account,chat).first().size)
            assertEquals((51L..100L).toList(),dao.cachedBefore(account,chat,101,50).reversed().map {it.seq})
            assertEquals((251L..300L).toList(),dao.cachedAfter(account,chat,250,50).map {it.seq})
        } finally {database.close()}
    }
    @Test fun partialFileSurvivesReopenAndHashDetectsWrongBytes() {
        val directory=File(RuntimeEnvironment.getApplication().cacheDir,"files-${Uuid.random()}").also { it.mkdirs() }
        val files=ManagedFiles(directory.absolutePath); val id=Uuid.random().toString()
        try {
            files.write(account,id,0,"ab".encodeToByteArray())
            ManagedFiles(directory.absolutePath).write(account,id,2,"c".encodeToByteArray())
            assertEquals(3L,files.size(account,id))
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",files.hash(account,id))
            assertContentEquals("bc".encodeToByteArray(),files.read(account,id,1,2))
            assertFailsWith<IllegalArgumentException> { files.path(account,"../foreign") }
        } finally { files.remove(account,id) }
    }
}
