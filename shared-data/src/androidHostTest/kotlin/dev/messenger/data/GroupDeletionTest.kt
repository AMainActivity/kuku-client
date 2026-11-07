package dev.messenger.data

import androidx.room.Room
import dev.messenger.data.wire.*
import dev.messenger.domain.ApplicationFailure
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class GroupDeletionTest {
    @get:org.junit.Rule val temporaryFolder=org.junit.rules.TemporaryFolder()
    private val account=Uuid.random().toString()+":"+Uuid.random()
    private val group=Uuid.random().toString()
    private val other=Uuid.random().toString()
    private fun chat(id:String)=Chat(id,"group","Group","",null,7,Membership("owner","active",1,1),null,0,null,Preferences(false,0,false,0))
    private fun transfer(id:String,chatId:String)=TransferEntity(account,id,chatId,"UPLOAD","image.jpg","image/jpeg",10,"a".repeat(64))

    @Test fun deletionCommitsWithCursorAndStopsOnlyItsOwnWork()=runBlocking<Unit> {
        val db=buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication()))
        try {
            val dao=db.conversations()
            dao.installCatalog(account,0,"test")
            dao.cacheChat(account,chat(group));dao.cacheChat(account,chat(other))
            val ownJob=Uuid.random().toString()
            val sharedJob=Uuid.random().toString()
            val otherJob=Uuid.random().toString()
            db.transfers().put(transfer(ownJob,group))
            db.transfers().put(transfer(sharedJob,group))
            db.transfers().put(transfer(otherJob,other))
            db.transfers().link(TransferChatLink(account,other,sharedJob))
            val draft=db.messages().saveDraft(account,group,"unsent")
            val operation=Uuid.random().toString()
            db.messages().enqueue(draft,OutboxEntity(account,operation,group,"unsent",1,"READY"))
            db.messages().saveDraft(account,group,"another draft")
            db.messages().saveDraft(account,other,"keep this")
            dao.advanceReceipt(account,group,"read",1)
            val event=SyncEvent(1,"chat.accessRevoked",buildJsonObject {
                put("chatId",group);put("reason","group_deleted");put("version",8);put("deletedAt","2026-11-07T00:00:00Z")
            },"")
            dao.applyPage(account,0,SyncPage(listOf(event),1,false,1),emptyList(),emptyList(),setOf(group))
            assertEquals(1,dao.syncState(account)?.cursor)
            assertNull(dao.cachedChat(account,group))
            assertNull(db.messages().draft(account,group))
            assertNull(dao.receipt(account,group,"read"))
            assertEquals("FAILED",db.messages().operation(account,operation)?.state)
            assertEquals("CHAT_UNAVAILABLE",db.transfers().get(account,ownJob)?.error)
            assertEquals("QUEUED",db.transfers().get(account,sharedJob)?.state)
            assertEquals("QUEUED",db.transfers().get(account,otherJob)?.state)
            assertEquals("keep this",db.messages().draft(account,other)?.text)
            assertEquals("group_deleted",dao.observeRevoked(account).first().single().revokeReason)
            // Event-before-response and duplicate revocation must retain the deletion reason.
            dao.revoke(account,group,"group_deleted");dao.revoke(account,group)
            assertEquals("group_deleted",dao.cacheState(account,group)?.revokeReason)
            assertFalse(dao.cacheChat(account,chat(group),allowRestore=true))
            db.transfers().outcome(account,ownJob,"READY",null)
            assertEquals("FAILED",db.transfers().get(account,ownJob)?.state)
            assertFailsWith<ApplicationFailure> {db.messages().saveDraft(account,group,"late composer save")}
        } finally {db.close()}
    }

    @Test fun sharedUploadReferencedByAnotherDraftSurvives()=runBlocking<Unit> {
        val db=buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication()))
        try {
            val job=Uuid.random().toString()
            db.transfers().put(transfer(job,group))
            db.messages().saveExtras(account,other,null,"[\"$job\"]")
            db.conversations().revoke(account,group,"group_deleted")
            assertEquals("QUEUED",db.transfers().get(account,job)?.state)
            db.conversations().revoke(account,other,"group_deleted")
            assertEquals("CHAT_UNAVAILABLE",db.transfers().get(account,job)?.error)
        } finally {db.close()}
    }

    @Test fun failedSendInAnotherChatKeepsItsLocalFile()=runBlocking<Unit> {
        val db=buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication()))
        try {
            val job=Uuid.random().toString()
            db.transfers().put(transfer(job,group))
            val draft=db.messages().saveDraft(account,other,"retry later")
            db.messages().enqueue(draft,OutboxEntity(account,Uuid.random().toString(),other,"retry later",1,"FAILED",
                contentJson=wireJson.encodeToString(PlainContent("retry later",attachmentIds=listOf(job)))))
            db.conversations().revoke(account,group,"group_deleted")
            assertEquals("QUEUED",db.transfers().get(account,job)?.state)
            db.conversations().revoke(account,other,"group_deleted")
            assertEquals("CHAT_UNAVAILABLE",db.transfers().get(account,job)?.error)
        } finally {db.close()}
    }

    @Test fun migrationFrom14PreservesRevocationAndDrafts()=runBlocking<Unit> {
        val path=java.io.File(temporaryFolder.root,"groups.db")
        val schemaFile=java.io.File(requireNotNull(System.getProperty("messenger.schemaDirectory")),"dev.messenger.data.MessengerDatabase/14.json")
        val schema=wireJson.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        val connection=androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(path.absolutePath)
        try {
            fun sql(query:String){connection.prepare(query).use {it.step()}}
            for(value in schema.getValue("entities").jsonArray) {
                val entity=value.jsonObject
                val name=entity.getValue("tableName").jsonPrimitive.content
                sql(entity.getValue("createSql").jsonPrimitive.content.replace("${'$'}{TABLE_NAME}",name))
                for(index in entity["indices"]?.jsonArray.orEmpty()) sql(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("${'$'}{TABLE_NAME}",name))
            }
            for(query in schema.getValue("setupQueries").jsonArray) sql(query.jsonPrimitive.content)
            sql("PRAGMA user_version=14")
            sql("INSERT INTO chat_cache_state VALUES ('$account','$group',3,3,1)")
            sql("INSERT INTO drafts (accountId,chatId,text,revision) VALUES ('$account','$other','keep',2)")
        } finally {connection.close()}
        val db=buildDatabase(Room.databaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication(),path.absolutePath))
        try {
            val state=assertNotNull(db.conversations().cacheState(account,group))
            assertTrue(state.revoked)
            assertNull(state.revokeReason)
            assertEquals("keep",db.messages().draft(account,other)?.text)
            db.conversations().revoke(account,group,"group_deleted")
            assertEquals("group_deleted",db.conversations().cacheState(account,group)?.revokeReason)
        } finally {db.close()}
    }

    @Test fun wireDeletionCarriesExpectedVersionAndLastHumanGuard() {
        val value=wireJson.encodeToJsonElement(GroupDeleteParams(group,7,true)).jsonObject
        assertEquals(7,value.getValue("expectedVersion").jsonPrimitive.long)
        assertTrue(value.getValue("onlyIfLastHuman").jsonPrimitive.boolean)
        assertTrue(MoreCommands.GroupDelete.mutation)
        assertTrue(MoreCommands.AdminGroupDelete.mutation)
        assertFalse(MoreCommands.AdminGroupsList.mutation)
        assertFailsWith<IllegalArgumentException> {AdminGroupDeleteParams(group,7," ")}
        assertFailsWith<IllegalArgumentException> {GroupDeleteParams(group,0,false)}
    }
}
