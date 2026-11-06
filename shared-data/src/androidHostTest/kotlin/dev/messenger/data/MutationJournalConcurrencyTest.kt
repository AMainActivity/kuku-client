package dev.messenger.data

import androidx.room.Room
import dev.messenger.data.network.ConnectionStatus
import dev.messenger.data.session.*
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MutationJournalConcurrencyTest {
    private data class Call(val operationId: Uuid?, val result: CompletableDeferred<UserBlocked>)

    @Test fun lateResponseCannotEraseNewerOperationIdentity() = runBlocking<Unit> {
        withTimeout(10_000) {
            val database = buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication()))
            val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val server = Uuid.random().toString()
            val user = Uuid.random().toString()
            val device = Uuid.random().toString()
            val session = Uuid.random().toString()
            val calls = Channel<Call>(Channel.UNLIMITED)
            val secure = object : SecureStore {
                var value = wireJson.encodeToString(Vault(device, SavedSession("example.com", 443,
                    Credentials(session,user,device,server,"a".repeat(40),"r".repeat(40),"2100-01-01T00:00:00Z"))))
                override suspend fun read() = value
                override suspend fun write(value:String) { this.value = value }
            }
            val sessions = SessionCoordinator(secure, Connector { _,_,_ ->
                object : RpcChannel {
                    override val serverId = server
                    override val status = MutableStateFlow(ConnectionStatus.OPEN)
                    override val highWatermark = MutableStateFlow<Long?>(null)
                    @Suppress("UNCHECKED_CAST")
                    override suspend fun <P,R> request(command:Command<P,R>, params:P, operationId:Uuid?):R =
                        when (command.name) {
                            "auth.resume" -> SessionIdentity(session,user,device,server)
                            "users.block" -> {
                                val call = Call(operationId, CompletableDeferred())
                                calls.send(call)
                                call.result.await()
                            }
                            else -> error("Unexpected command: " + command.name)
                        } as R
                    override suspend fun close() { status.value = ConnectionStatus.CLOSED }
                }
            }, owner)
            val repository = ConversationRepository(database, sessions, owner)
            try {
                sessions.restore()
                val first = async { repository.execute(MoreCommands.UserBlock, DirectParams(user)) }
                val firstCall = calls.receive()
                val second = async { repository.execute(MoreCommands.UserBlock, DirectParams(user)) }
                val secondCall = calls.receive()
                assertNotNull(firstCall.operationId)
                assertEquals(firstCall.operationId, secondCall.operationId)
                firstCall.result.complete(UserBlocked(user, true))
                first.await()

                val third = async {
                    assertFailsWith<RequestTimeout> { repository.execute(MoreCommands.UserBlock, DirectParams(user)) }
                }
                val thirdCall = calls.receive()
                assertNotEquals(firstCall.operationId, thirdCall.operationId)
                // The older response arrives after the next intent has reserved its durable identity.
                secondCall.result.complete(UserBlocked(user, true))
                second.await()
                thirdCall.result.completeExceptionally(RequestTimeout(true))
                third.await()

                // Retry through a recreated repository proves that identity survives in Room.
                val recreated = ConversationRepository(database, sessions, owner)
                val retry = async { recreated.execute(MoreCommands.UserBlock, DirectParams(user)) }
                val retryCall = calls.receive()
                assertEquals(thirdCall.operationId, retryCall.operationId)
                retryCall.result.complete(UserBlocked(user, true))
                retry.await()
            } finally {
                sessions.disconnect()
                owner.cancel()
                calls.cancel()
                database.close()
            }
        }
    }
}