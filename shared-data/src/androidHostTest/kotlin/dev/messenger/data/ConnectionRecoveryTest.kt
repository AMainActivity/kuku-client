package dev.messenger.data

import androidx.room.Room
import dev.messenger.data.network.ConnectionStatus
import dev.messenger.data.session.*
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
class ConnectionRecoveryTest {
    @Test fun hintPullsJournalAndReconnectUsesCommittedCursorBeforeAck()=verifyRecovery(true)
    @Test fun closedSocketReconnectsWithoutNetworkCallback()=verifyRecovery(false)
    @Test fun lostAckIsRecoveredFromCommittedCursor() = verifyRecovery(false, true)
    @Test fun rateLimitedSyncRetainsLiveSessionAndRetriesOnSameSocket() = verifyRecovery(false, rateLimited=true)
    @Test fun messageLimitPreservesOutboxWithoutTriggeringEmptySync() = verifyRecovery(false, messageLimit=true)
    private fun verifyRecovery(networkSignal:Boolean, loseAck:Boolean=false, rateLimited:Boolean=false, messageLimit:Boolean=false)=runBlocking {
        val database=buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication()))
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val server=Uuid.random().toString(); val user=Uuid.random().toString()
        val device=Uuid.random().toString(); val session=Uuid.random().toString()
        val key="$server:$user"
        var limited = false
        val sends = java.util.concurrent.CopyOnWriteArrayList<Pair<Uuid?, SendParams>>()
        val messageRejected = CompletableDeferred<Unit>()
        val receiptSent = CompletableDeferred<Unit>()
        val pulls = java.util.concurrent.atomic.AtomicInteger()
        val mutations=mutableListOf<Uuid?>()
        val ackCount=java.util.concurrent.atomic.AtomicInteger()
        val committed=MutableStateFlow(-1L)
        val opened=MutableStateFlow(0)
        val remote=MutableStateFlow(0L)
        val reconnectCursor=MutableStateFlow(-1L)
        val channels=java.util.concurrent.CopyOnWriteArrayList<RpcChannel>()
        val vault=wireJson.encodeToString(Vault(device,SavedSession("example.com",443,
            Credentials(session,user,device,server,"a".repeat(40),"r".repeat(40),"2100-01-01T00:00:00Z"))))
        val secure=object:SecureStore {
            var value=vault
            override suspend fun read()=value
            override suspend fun write(value:String) { this.value=value }
        }
        val sessions=SessionCoordinator(secure,Connector { _,_,expected ->
            assertEquals(server,expected)
            val number=opened.value+1
            val channel=object:RpcChannel {
                override val serverId=server
                override val status=MutableStateFlow(ConnectionStatus.OPEN)
                override val highWatermark=MutableStateFlow<Long?>(null)
                @Suppress("UNCHECKED_CAST")
                override suspend fun <P,R> request(command:Command<P,R>,params:P,operationId:Uuid?):R {
                    val result:Any=when(command.name) {
                        "users.block" -> {
                            mutations.add(operationId)
                            if(mutations.size==1) throw RequestTimeout(true)
                            UserBlocked(user,true)
                        }
                        "messages.send" -> {
                            val sent = params as SendParams
                            sends.add(operationId to sent)
                            if (sends.size == 1) {
                                messageRejected.complete(Unit)
                                throw RemoteFailure("RATE_LIMITED", true, buildJsonObject {
                                    put("scope", "messages"); put("retryAfterSeconds", 1)
                                })
                            }
                            Message(Uuid.random().toString(),sent.clientMessageId,sent.chatId,user,1,1,
                                null,null,false,"2026-11-06T00:00:00Z",null,emptyList())
                        }
                        "receipts.set" -> { receiptSent.complete(Unit); ReceiptAccepted(true) }
                        "ping" -> Pong("alive")
                        "auth.resume" -> SessionIdentity(session,user,device,server)
                        "sync.start" -> SyncStart(0,"catalog-then-replay","lazy")
                        "chats.list" -> ChatPage(emptyList(),null,"chatId")
                        "sync.pull" -> {
                            pulls.incrementAndGet()
                            if (rateLimited && !limited) {
                                limited = true
                                throw RemoteFailure("RATE_LIMITED", true, buildJsonObject {
                                    put("scope", "sync"); put("retryAfterSeconds", 1)
                                })
                            }
                            val cursor=(params as SyncParams).cursor
                            if(number>1) reconnectCursor.value=cursor
                            val events=if(remote.value>cursor) listOf(SyncEvent(remote.value,"future.type",buildJsonObject { put("value","preserved") },"2100-01-01T00:00:00Z")) else emptyList()
                            SyncPage(events,remote.value,false,remote.value)
                        }
                        "sync.ack" -> {
                            val cursor=(params as AckParams).cursor
                            assertEquals(cursor,database.conversations().syncState(key)?.cursor,"ACK preceded durable page")
                            ackCount.incrementAndGet()
                            if (loseAck && number == 1 && cursor == 1L) throw ConnectionLost()
                            committed.value=cursor
                            Acknowledged(cursor)
                        }
                        else -> error("Unexpected command ${command.name}")
                    }
                    return result as R
                }
                override suspend fun close() { status.value=ConnectionStatus.CLOSED }
            }
            channels.add(channel); opened.value=number
            channel
        },owner)
        val repository=ConversationRepository(database,sessions,owner)
        try {
            sessions.restore(); repository.start()
            withTimeout(10000) { committed.first { it==0L } }
            assertEquals(1, opened.value, "A command limit must not reconnect")
            assertTrue(sessions.state.value.online)
            val initialAckCount = ackCount.get()
            repository.synchronize()
            repository.synchronize()
            assertEquals(initialAckCount, ackCount.get(), "Empty pages must not keep acknowledging the same cursor")
            if (messageLimit) {
                val before = pulls.get()
                repository.send(key, Uuid.random().toString(), "queued")
                withTimeout(10_000) { messageRejected.await() }
                val pending = database.messages().observeOutbox(key).first()
                assertEquals("READY", pending.single().state)
                assertTrue(sessions.state.value.online)
                assertEquals("alive", sessions.request(Commands.Ping, EmptyParams()).serverTime)
                withTimeout(10_000) { receiptSent.await() }
                withTimeout(10_000) { database.messages().observeOutbox(key).first { it.isEmpty() } }
                assertEquals(2, sends.size)
                assertEquals(sends[0].first, sends[1].first)
                assertEquals(sends[0].second.clientMessageId, sends[1].second.clientMessageId)
                assertEquals(wireJson.encodeToString(sends[0].second), wireJson.encodeToString(sends[1].second))
                assertEquals(before, pulls.get(), "Outbox and receipt changes must not trigger sync")
                assertEquals(1, opened.value)
            }
            remote.value=1
            (channels.first().highWatermark as MutableStateFlow).value=1
            withTimeout(10000) { committed.first { it==1L } }
            if (!loseAck) {
                channels.first().close()
                if(networkSignal) repository.networkAvailable()
            }
            withTimeout(10000) { reconnectCursor.first { it==1L } }
            assertEquals(2,opened.value)
            assertTrue(sessions.state.value.online)
            repository.stop()
            assertFailsWith<RequestTimeout> { repository.execute(MoreCommands.UserBlock,DirectParams(user),Uuid.random()) }
            val recreated=ConversationRepository(database,sessions,owner)
            recreated.execute(MoreCommands.UserBlock,DirectParams(user),Uuid.random())
            assertEquals(mutations[0],mutations[1],"Unknown mutation outcome must retain operation identity")
            recreated.execute(MoreCommands.UserBlock,DirectParams(user),Uuid.random())
            assertNotEquals(mutations[1],mutations[2],"Acknowledged operation must not capture a new intent")
        } finally { repository.stop(); sessions.disconnect(); owner.cancel(); database.close() }
    }
    @Test fun emojiMentionOffsetsUseCodePointsAndInactiveBotsAreExcluded() {
        val id=Uuid.random().toString()
        val bot=MemberInfo(id,"Bot","bot","helper","member","active",1,0,0)
        val mentions=ConversationRepository.mentions("😀 @helper",listOf(bot))
        assertEquals(2,mentions.single().offset)
        assertEquals(7,mentions.single().length)
        assertTrue(ConversationRepository.mentions("@helper",listOf(bot.copy(status="removed"))).isEmpty())
    }
}
