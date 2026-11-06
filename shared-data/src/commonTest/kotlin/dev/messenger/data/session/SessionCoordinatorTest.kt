package dev.messenger.data.session

import dev.messenger.data.network.*
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCoordinatorTest {
    private val server = Uuid.random().toString()
    private val user = Uuid.random().toString()
    private val device = Uuid.random().toString()
    private val session = Uuid.random().toString()
    private fun credentials(token: String) = Credentials(session,user,device,server,token.repeat(40),token.repeat(40),"2100-01-01T00:00:00Z")
    private class Store(var value: String?) : SecureStore {
        override suspend fun read() = value
        override suspend fun write(value: String) { this.value = value }
    }
    private inner class Channel(private val handler: suspend (String, Any?, Uuid?) -> Any) : RpcChannel {
        override val serverId = server
        override val status = MutableStateFlow(ConnectionStatus.OPEN)
        override val highWatermark = MutableStateFlow<Long?>(null)
        @Suppress("UNCHECKED_CAST")
        override suspend fun <P,R> request(command: Command<P,R>, params: P, operationId: Uuid?): R =
            handler(command.name, params, operationId) as R
        override suspend fun close() { status.value = ConnectionStatus.CLOSED }
    }
    private fun store() = Store(wireJson.encodeToString(Vault(device, SavedSession("example.com",443,credentials("a")))))
    private fun denied() = RemoteFailure("UNAUTHENTICATED",false,JsonObject(emptyMap()))

    @Test fun socketStateImmediatelyReachesSessionAndOldSocketCannotOverrideNewOne()=runTest {
        val channels=mutableListOf<Channel>()
        val manager=SessionCoordinator(store(),Connector {_,_,_->
            Channel {name,_,_->
                check(name=="auth.resume")
                SessionIdentity(session,user,device,server)
            }.also {channels.add(it)}
        },backgroundScope)
        manager.connection()
        runCurrent()
        assertTrue(manager.state.value.online)
        channels.first().status.value=ConnectionStatus.AUTH_REQUIRED
        runCurrent()
        assertFalse(manager.state.value.online,"Must update without a repository request or timeout")
        manager.connection()
        runCurrent()
        assertTrue(manager.state.value.online)
        channels.first().close()
        runCurrent()
        assertTrue(manager.state.value.online,"An obsolete socket must not overwrite the new state")
        manager.disconnect(channels.first())
        assertTrue(manager.state.value.online,"Stale worker must not disconnect a replacement")
        channels.last().close()
        runCurrent()
        assertFalse(manager.state.value.online)
        manager.disconnect()
    }

    @Test fun accessTokenRefreshesBeforeExpiryWithoutClosingSocket()=runTest {
        fun timed(token:String,expires:Long)=Credentials(session,user,device,server,
            token.repeat(40),token.repeat(40),kotlin.time.Instant.fromEpochMilliseconds(expires).toString())
        val storage=Store(wireJson.encodeToString(Vault(device,SavedSession("example.com",443,timed("a",120_000)))))
        var opened=0
        var refreshes=0
        val manager=SessionCoordinator(storage,Connector {_,_,_->
            opened++
            Channel {name,_,operation->
                when(name) {
                    "auth.resume" -> SessionIdentity(session,user,device,server)
                    "auth.refresh" -> {
                        refreshes++
                        assertNotNull(operation)
                        timed("b",currentTime+120_000)
                    }
                    else -> error("Unexpected command")
                }
            }
        },backgroundScope,now={currentTime})
        val socket=manager.connection()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1,refreshes)
        assertEquals(1,opened)
        assertTrue(manager.state.value.online)
        assertSame(socket,manager.connection())
        manager.disconnect()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1,refreshes,"Disconnected sessions must not keep refreshing")
    }

    @Test fun concurrentReconnectRotatesOnceAndPersistsBeforeReturning() = runTest {
        val storage = store()
        var refreshes = 0
        val manager = SessionCoordinator(storage, Connector { _,_,expected ->
            assertEquals(server, expected)
            Channel { name,_,operation ->
                when(name) {
                    "auth.resume" -> throw denied()
                    "auth.refresh" -> {
                        refreshes++
                        val journal = wireJson.decodeFromString<Vault>(requireNotNull(storage.value)).session?.refresh
                        assertEquals(operation.toString(), journal?.operationId)
                        credentials("b")
                    }
                    else -> error("Unexpected command")
                }
            }
        }, backgroundScope)
        manager.restore()
        val first = async { manager.connection() }
        val second = async { manager.connection() }
        assertSame(first.await(), second.await())
        assertEquals(1, refreshes)
        val saved = wireJson.decodeFromString<Vault>(requireNotNull(storage.value)).session
        assertEquals("b".repeat(40), saved?.credentials?.accessToken)
        assertNull(saved?.refresh)
        manager.disconnect()
    }

    @Test fun lostRefreshResponseReusesSameOperationAndOldToken() = runTest {
        val storage = store()
        val operations = mutableListOf<Uuid?>()
        val tokens = mutableListOf<String>()
        val manager = SessionCoordinator(storage, Connector { _,_,_ ->
            Channel { name,params,operation ->
                when(name) {
                    "auth.resume" -> throw denied()
                    "auth.refresh" -> {
                        operations.add(operation)
                        tokens.add((params as RefreshParams).refreshToken)
                        if (operations.size == 1) throw ConnectionLost()
                        credentials("b")
                    }
                    else -> error("Unexpected command")
                }
            }
        }, backgroundScope, now = { 10_000 })
        assertFailsWith<ConnectionLost> { manager.connection() }
        manager.connection()
        assertEquals(2, operations.size)
        assertEquals(operations.first(), operations.last())
        assertEquals(listOf("a".repeat(40),"a".repeat(40)), tokens)
        manager.disconnect()
    }

    @Test fun expiredUncertainRefreshRequiresLoginWithoutReusingOldToken() = runTest {
        val storage = store()
        var clock = 10_000L
        var refreshes = 0
        val manager = SessionCoordinator(storage, Connector { _,_,_ ->
            Channel { name,_,_ ->
                when(name) {
                    "auth.resume" -> throw denied()
                    "auth.refresh" -> { refreshes++; throw ConnectionLost() }
                    else -> error("Unexpected command")
                }
            }
        }, backgroundScope, now = { clock })
        assertFailsWith<ConnectionLost> { manager.connection() }
        clock += 30_000
        assertEquals("REFRESH_WINDOW_EXPIRED", assertFailsWith<ProtocolFailure> { manager.connection() }.code)
        assertEquals(1, refreshes)
        assertNull(manager.state.value.account)
        assertNull(wireJson.decodeFromString<Vault>(requireNotNull(storage.value)).session)
    }

    @Test fun mismatchedResumeIdentityIsNotAccepted() = runTest {
        val manager = SessionCoordinator(store(), Connector { _,_,_ ->
            Channel { _,_,_ -> SessionIdentity(session,Uuid.random().toString(),device,server) }
        }, backgroundScope)
        assertEquals("SESSION_IDENTITY_CHANGED", assertFailsWith<ProtocolFailure> { manager.connection() }.code)
        assertFalse(manager.state.value.online)
    }
    @Test fun checkedConnectionIsReusedForEmailAndReconnectKeepsIdentity()=runTest {
        val endpoint=requireNotNull(dev.messenger.domain.ServerEndpoint.parse("example.com","443"))
        val expectedIds=mutableListOf<String?>();val calls=mutableListOf<String>()
        val manager=SessionCoordinator(Store(null),Connector { _,_,expected ->
            expectedIds.add(expected)
            Channel { name,_,_ ->
                calls.add(name)
                when(name) {
                    "ping" -> Pong("2100-01-01T00:00:00Z")
                    "auth.email.start" -> Challenge(Uuid.random().toString(),"2026-11-05T00:10:00Z",60,"allowed addresses only")
                    else -> error("Unexpected command")
                }
            }
        },backgroundScope)
        manager.prepareLogin(endpoint)
        assertEquals(listOf("ping"),calls)
        manager.startEmail(endpoint,"allowed@example.com")
        assertEquals(1,expectedIds.size)
        manager.disconnect()
        manager.startEmail(endpoint,"allowed@example.com")
        assertEquals(listOf(null,server),expectedIds)
        manager.disconnect()
    }
    @Test fun failedProbeClosesAnonymousConnectionAndSendsNoEmail()=runTest {
        val endpoint=requireNotNull(dev.messenger.domain.ServerEndpoint.parse("example.com","443"))
        val opened=Channel { name,_,_ -> assertEquals("ping",name);throw ConnectionLost() }
        val manager=SessionCoordinator(Store(null),Connector { _,_,_ -> opened },backgroundScope)
        assertFailsWith<ConnectionLost> { manager.prepareLogin(endpoint) }
        assertEquals(ConnectionStatus.CLOSED,opened.status.value)
    }    @Test fun cancelLoginClosesConnectionDiscardsCodeAndRechecksSameEndpoint()=runTest {
        val endpoint=requireNotNull(dev.messenger.domain.ServerEndpoint.parse("example.com","443"))
        val opened=mutableListOf<Channel>();val expectedIds=mutableListOf<String?>()
        val manager=SessionCoordinator(Store(null),Connector { _,_,expected ->
            expectedIds.add(expected)
            Channel { name,_,_ -> when(name) {
                "ping" -> Pong("2026-11-06T00:00:00Z")
                "auth.email.start" -> Challenge(Uuid.random().toString(),"2026-11-06T00:10:00Z",60,"allowed addresses only")
                else -> error("Cancelled challenge must never be verified")
            } }.also { opened.add(it) }
        },backgroundScope)
        manager.prepareLogin(endpoint)
        manager.startEmail(endpoint,"allowed@example.com")
        assertTrue(manager.cancelLogin())
        assertEquals(ConnectionStatus.CLOSED,opened.single().status.value)
        assertFailsWith<IllegalArgumentException> { manager.verifyEmail("12345678","test") }
        manager.prepareLogin(endpoint)
        assertEquals(2,opened.size)
        assertEquals(listOf<String?>(null,null),expectedIds)
        assertEquals(ConnectionStatus.OPEN,opened.last().status.value)
        manager.cancelLogin()
    }
    @Test fun leavingLoginAfterSuccessfulAuthenticationKeepsConnection()=runTest {
        val opened=Channel { name,_,_ ->
            assertEquals("auth.resume",name)
            SessionIdentity(session,user,device,server)
        }
        val manager=SessionCoordinator(store(),Connector { _,_,_ -> opened },backgroundScope)
        manager.connection()
        assertFalse(manager.cancelLogin())
        assertEquals(ConnectionStatus.OPEN,opened.status.value)
        assertEquals(user,manager.state.value.account?.userId)
        manager.disconnect()
    }}
