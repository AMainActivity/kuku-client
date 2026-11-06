package dev.messenger.data

import androidx.room.Room
import androidx.room.useReaderConnection
import dev.messenger.data.network.*
import dev.messenger.data.session.*
import dev.messenger.data.wire.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import okio.ByteString.Companion.toByteString
import kotlin.test.*
import kotlin.uuid.Uuid

/** Regressions for cache ordering, privacy, UTF-8 limits and attachment recovery. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34], manifest=Config.NONE)
class CacheAndTransferRegressionTest {
    private val server=Uuid.random().toString()
    private val user=Uuid.random().toString()
    private val account=server+":"+user
    private val chatId=Uuid.random().toString()
    private fun db()=buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication()))
    private fun chat(version:Long,pinned:Boolean)=Chat(chatId,"group","Title "+version,"",null,version,
        Membership("member","active",1,version),null,0,null,Preferences(pinned,0,false,version))
    private fun session(owner:CoroutineScope, limit:Long=MAX_TEXT_BYTES):SessionCoordinator {
        val device=Uuid.random().toString()
        val credentials=Credentials(Uuid.random().toString(),user,device,server,"a".repeat(40),"r".repeat(40),"2100-01-01T00:00:00Z")
        val saved=wireJson.encodeToString(Vault(device,SavedSession("example.com",443,credentials,maxTextBytes=limit)))
        return SessionCoordinator(object:SecureStore {
            override suspend fun read()=saved
            override suspend fun write(value:String) {}
        },Connector {_,_,_->object:RpcChannel {
            override val serverId=server
            override val maxTextBytes=limit
            override val status=MutableStateFlow(ConnectionStatus.OPEN)
            override val highWatermark=MutableStateFlow<Long?>(null)
            @Suppress("UNCHECKED_CAST")
            override suspend fun <P,R> request(command:Command<P,R>,params:P,operationId:Uuid?):R {
                check(command.name=="auth.resume")
                return credentials.identity() as R
            }
            override suspend fun close() {status.value=ConnectionStatus.CLOSED}
        }},owner)
    }
    @Test fun catalogOnlyUnreadCursorIsZeroUntilMembersArrive()=runBlocking<Unit> {
        val database=db()
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val sessions=session(owner).also {it.restore()}
            val repository=ConversationRepository(database,sessions,owner)
            val dao=database.conversations()
            val last=Message(Uuid.random().toString(),Uuid.random().toString(),chatId,user,1000,1,null,null,false,"",null,emptyList())
            val snapshot=chat(1,false).copy(lastMessage=last,unreadCount=1)
            dao.stage(listOf(CatalogEntity(account,chatId,wireJson.encodeToString(Chat.serializer(),snapshot))))
            dao.installCatalog(account,77,"catalog")
            assertTrue(dao.members(account,chatId).isEmpty())
            assertEquals(0L,repository.initialUnreadCursor(account,chatId))
            assertEquals(listOf(1000L),repository.cachedHistoryPage(account,chatId,"around",1).items.map {it.seq})
            dao.replaceMembers(account,chatId,listOf(MemberInfo(user,"Name","human",null,"member","active",1,1000,999)))
            assertEquals(999L,repository.initialUnreadCursor(account,chatId))
        } finally {owner.cancel();database.close()}
    }
    @Test fun independentVersionsMergeWithoutRollingBackUnreadSnapshot()=runBlocking<Unit> {
        val database=db()
        try {
            val dao=database.conversations()
            dao.cacheChat(account,chat(3,true).copy(unreadCount=5))
            val oldRequest=dao.cacheStamp(account)
            dao.cacheChat(account,chat(3,true).copy(unreadCount=0),requestStamp=oldRequest)
            dao.cacheChat(account,chat(1,false).copy(preferences=Preferences(false,0,true,4),unreadCount=5),requestStamp=oldRequest)
            val current=wireJson.decodeFromString<Chat>(requireNotNull(dao.cachedChat(account,chatId)).json)
            assertEquals(3L,current.version)
            assertEquals(3L,current.membership.version)
            assertEquals(4L,current.preferences.version)
            assertTrue(current.preferences.muted)
            assertEquals(0L,current.unreadCount)
        } finally {database.close()}
    }
    @Test fun oldHistoryCannotReturnAfterRevocationAndRejoining()=runBlocking<Unit> {
        val database=db()
        try {
            val dao=database.conversations()
            dao.cacheChat(account,chat(1,false))
            val oldRequest=dao.cacheStamp(account)
            dao.revoke(account,chatId)
            val revokedStamp=dao.cacheStamp(account)
            assertFalse(dao.cacheChat(account,chat(1,false),requestStamp=oldRequest,allowRestore=true))
            assertTrue(dao.cacheChat(account,chat(2,false).copy(membership=Membership("member","active",10,2)),requestStamp=revokedStamp,allowRestore=true))
            val late=Message(Uuid.random().toString(),Uuid.random().toString(),chatId,user,11,1,null,null,false,"",null,emptyList())
            dao.cacheMessages(account,listOf(late),oldRequest)
            assertNull(dao.message(account,late.messageId))
            dao.cacheMessages(account,listOf(late),dao.cacheStamp(account))
            assertNotNull(dao.message(account,late.messageId))
        } finally {database.close()}
    }
    @Test fun restoredServerByteLimitPreservesDraftAndAcceptsExactBoundary()=runBlocking<Unit> {
        val database=db()
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val sessions=session(owner,4).also {it.restore()}
            val repository=ConversationRepository(database,sessions,owner)
            database.messages().saveDraft(account,chatId,"ЯЯЯ")
            val failure=assertFailsWith<dev.messenger.domain.ApplicationFailure> {repository.send(account,chatId,"ЯЯЯ")}
            assertEquals("MESSAGE_TOO_LARGE",failure.code)
            assertEquals("ЯЯЯ",database.messages().draft(account,chatId)?.text)
            assertTrue(database.messages().ready(account,10).isEmpty())
            repository.send(account,chatId,"ЯЯ")
            assertEquals("ЯЯ",database.messages().ready(account,10).single().text)
        } finally {owner.cancel();database.close()}
    }
    @Test fun migrationFrom13DropsRawPayloadsAndPreservesCursorAndDraft()=runBlocking<Unit> {
        val context=RuntimeEnvironment.getApplication()
        val name="cache-safety-${Uuid.random()}.db"
        val path=context.getDatabasePath(name).also {it.parentFile?.mkdirs()}
        val schema=wireJson.parseToJsonElement(java.io.File(requireNotNull(System.getProperty("messenger.schemaDirectory")),"dev.messenger.data.MessengerDatabase/13.json").readText()).jsonObject.getValue("database").jsonObject
        val connection=androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(path.absolutePath)
        try {
            fun sql(value:String) {connection.prepare(value).use {it.step()}}
            for(entity in schema.getValue("entities").jsonArray) {
                val table=entity.jsonObject.getValue("tableName").jsonPrimitive.content
                sql(entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",table))
                for(index in entity.jsonObject["indices"]?.jsonArray.orEmpty()) sql(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",table))
            }
            for(query in schema.getValue("setupQueries").jsonArray) sql(query.jsonPrimitive.content)
            sql("PRAGMA user_version=13")
            sql("INSERT INTO sync_state VALUES ('$account',77,NULL)")
            sql("INSERT INTO sync_events VALUES ('$account',77,'message.created','old deleted text')")
            sql("INSERT INTO drafts (accountId,chatId,text,revision) VALUES ('$account','$chatId','pending draft',3)")
        } finally {connection.close()}
        val database=buildDatabase(Room.databaseBuilder<MessengerDatabase>(context,name))
        try {
            assertEquals(77L,database.conversations().syncState(account)?.cursor)
            assertEquals("pending draft",database.messages().draft(account,chatId)?.text)
            database.useReaderConnection {reader -> reader.usePrepared("SELECT COUNT(*) FROM sqlite_master WHERE name='sync_events'") {statement ->
                assertTrue(statement.step());assertEquals(0L,statement.getLong(0))
            }}
            database.transfers().link(TransferChatLink(account,chatId,Uuid.random().toString()))
        } finally {database.close();context.deleteDatabase(name)}
    }
    @Test fun deletedMessageMustNotLeavePlaintextInUnusedEventCache()=runBlocking<Unit> {
        val database=db()
        try {
            val dao=database.conversations()
            dao.installCatalog(account,0,"review")
            val id=Uuid.random().toString()
            val clientId=Uuid.random().toString()
            val original=Message(id,clientId,chatId,user,1,1,
                buildJsonObject {put("mode","plain");put("version",1);put("text","review-secret-before-deletion")},
                null,false,"2026-11-06T00:00:00Z",null,emptyList())
            val deleted=Message(id,clientId,chatId,user,1,2,null,null,true,"2026-11-06T00:00:00Z",null,emptyList())
            fun page(cursor:Long,type:String,message:Message)=SyncPage(listOf(SyncEvent(cursor,type,
                wireJson.encodeToJsonElement(message).jsonObject,"")),cursor,false,cursor)
            dao.applyPage(account,0,page(1,"message.created",original),emptyList(),listOf(original),emptySet())
            dao.applyPage(account,1,page(2,"message.updated",deleted),emptyList(),listOf(deleted),emptySet())
            val stored=database.useReaderConnection {connection ->
                connection.usePrepared("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='sync_events'") {statement ->
                    check(statement.step());statement.getLong(0)
                }
            }
            assertEquals(0L,stored,"Obsolete raw payload table must not remain")
        } finally {database.close()}
    }
    @Test fun utf8ByteLimitMustBeCheckedBeforeClearingComposer()=runBlocking<Unit> {
        val database=db()
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val sessions=session(owner).also {it.restore()}
            val repository=ConversationRepository(database,sessions,owner)
            val text="Я".repeat(9000)
            assertEquals(18000,text.encodeToByteArray().size)
            val result=runCatching {repository.send(account,chatId,text)}
            assertTrue(result.isFailure,"9000 Cyrillic letters were queued despite 16384-byte server maximum")
        } finally {owner.cancel();database.close()}
    }
    @Test fun lateChatSnapshotMustNotRollBackGroupAndPreferences()=runBlocking<Unit> {
        val database=db()
        try {
            val dao=database.conversations()
            dao.cacheChat(account,chat(2,true))
            dao.cacheChat(account,chat(1,false))
            val current=wireJson.decodeFromString<Chat>(dao.cachedChats(account).single().json)
            assertEquals(2L,current.version,"Late chats.get rolled version back")
            assertTrue(current.preferences.pinned)
        } finally {database.close()}
    }
    @Test fun responseStartedBeforeRevocationMustNotRestoreChat()=runBlocking<Unit> {
        val database=db()
        try {
            val dao=database.conversations()
            val late=chat(1,false)
            dao.cacheChat(account,late)
            dao.revoke(account,chatId)
            dao.cacheChat(account,late)
            assertNull(dao.observeChat(account,chatId).first(),"Revoked chat resurrected by late result")
        } finally {database.close()}
    }
    @Test fun forwardedCachedAttachmentMustBeObservableInSecondChat()=runBlocking<Unit> {
        val database=db()
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val network=MessengerNetwork(messengerHttpClient(MockEngine {error("No HTTP expected")}))
        val files=ManagedFiles(RuntimeEnvironment.getApplication().cacheDir.resolve("review-files").absolutePath)
        val id=Uuid.random().toString()
        try {
            val sessions=session(owner).also {it.restore()}
            val repository=TransferRepository(database,files,network,sessions,owner)
            val attachment=Attachment(id,"file.txt","text/plain",4,"a".repeat(64),"ready")
            files.write(account,id,0,byteArrayOf(1,2,3,4))
            database.transfers().put(TransferEntity(account,id,chatId,"DOWNLOAD","file.txt","text/plain",4,
                attachment.sha256,state="READY",remoteId=id))
            val secondChat=Uuid.random().toString()
            repository.enqueueDownload(account,secondChat,attachment)
            assertTrue(repository.observe(account,secondChat).first().any {it.remoteId==id},
                "File exists, but second chat/gallery cannot observe it")
        } finally {files.remove(account,id);owner.cancel();network.close();database.close()}
    }
    @Test fun retryAfterIntegrityFailureMustAttemptDownloadAgain()=runBlocking<Unit> {
        val database=db()
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val calls=AtomicInteger()
        val expected=byteArrayOf(4,3,2,1)
        val network=MessengerNetwork(messengerHttpClient(MockEngine {
            calls.incrementAndGet()
            respond(expected,HttpStatusCode.PartialContent,headersOf(HttpHeaders.ETag to listOf("etag"),HttpHeaders.ContentRange to listOf("bytes 0-3/4")))
        }))
        val files=ManagedFiles(RuntimeEnvironment.getApplication().cacheDir.resolve("review-files").absolutePath)
        val id=Uuid.random().toString()
        var repository:TransferRepository?=null
        try {
            val sessions=session(owner).also {it.restore()}
            val transfers=TransferRepository(database,files,network,sessions,owner)
            repository=transfers
            files.write(account,id,0,byteArrayOf(1,2,3,4))
            database.transfers().put(TransferEntity(account,id,chatId,"DOWNLOAD","file.txt","text/plain",4,
                expected.toByteString().sha256().hex(),state="FAILED",remoteId=id,progress=4,error="CONNECTION_ERROR"))
            transfers.resume(account,id)
            transfers.start()
            withTimeout(5000) {transfers.observe(account,chatId).first {it.single().state=="READY"}}
            assertContentEquals(expected,files.read(account,id,0,4))
            assertTrue(calls.get()>0,"Retry rehashes the same corrupt complete file without any HTTP")
        } finally {repository?.stop();files.remove(account,id);owner.cancel();network.close();database.close()}
    }
    @Test fun boundedDownloadMustNotConsumeWhole200Response()=runBlocking<Unit> {
        val chunks=AtomicInteger()
        val producer=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val stream=ByteChannel(autoFlush=true)
        val network=MessengerNetwork(messengerHttpClient(MockEngine {
            producer.launch {
                try {repeat(64) {stream.writeFully(ByteArray(65536));chunks.incrementAndGet()}}
                finally {stream.close()}
            }
            respond(stream,HttpStatusCode.OK,headersOf(HttpHeaders.ETag,"test-version"))
        }))
        try {
            val part=network.downloadPart("example.com","synthetic",Uuid.random().toString(),1024,2047,"old-version")
            assertEquals(1025,part.bytes.size)
            assertTrue(chunks.get()<64,"All 4 MiB were buffered before returning a bounded 1025-byte read")
        } finally {network.close();producer.cancel()}
    }
}
