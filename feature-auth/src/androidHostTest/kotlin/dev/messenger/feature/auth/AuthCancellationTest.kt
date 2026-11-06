package dev.messenger.feature.auth

import dev.messenger.data.androidLocalStore
import dev.messenger.data.network.ConnectionStatus
import dev.messenger.data.session.*
import dev.messenger.data.wire.*
import dev.messenger.domain.ServerEndpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class AuthCancellationTest {
    @Test fun backCancelsPendingRequestClosesSocketThenNavigatesOnce()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store=androidLocalStore(RuntimeEnvironment.getApplication())
        try {
            var saved:String?=null
            val secure=object:SecureStore {
                override suspend fun read()=saved
                override suspend fun write(value:String) {saved=value}
            }
            val status=MutableStateFlow(ConnectionStatus.OPEN)
            var cancelled=false
            val channel=object:RpcChannel {
                override val serverId=Uuid.random().toString()
                override val status=status
                override val highWatermark=MutableStateFlow<Long?>(null)
                @Suppress("UNCHECKED_CAST")
                override suspend fun <P,R> request(command:Command<P,R>,params:P,operationId:Uuid?):R {
                    if(command.name=="ping") return Pong("2026-11-06T00:00:00Z") as R
                    assertEquals("auth.email.start",command.name)
                    try { awaitCancellation() } finally {cancelled=true}
                }
                override suspend fun close() {status.value=ConnectionStatus.CLOSED}
            }
            val sessions=SessionCoordinator(secure,Connector { _,_,_ -> channel },backgroundScope)
            sessions.prepareLogin(requireNotNull(ServerEndpoint.parse("example.com","443")))
            val access=dev.messenger.data.application.ApplicationSession(sessions,backgroundScope)
            val model=AuthViewModel(access)
            runCurrent()
            model.requestCode("example.com",443,"not-an-email");runCurrent()
            assertEquals("INVALID_EMAIL",model.error.value);assertFalse(model.busy.value)
            model.requestCode("example.com",443,"allowed@example.com");runCurrent()
            assertTrue(model.busy.value)
            var navigations=0
            val back={
                assertTrue(cancelled)
                assertEquals(ConnectionStatus.CLOSED,status.value)
                navigations++
                Unit
            }
            model.cancelLogin(back);model.cancelLogin(back)
            model.requestCode("example.com",443,"allowed@example.com")
            runCurrent()
            assertEquals(1,navigations)
            assertFalse(model.challengeReady.value)
            assertEquals(0L,model.resendAt.value)
            assertFalse(model.leaving.value)
            assertFalse(model.busy.value)
        } finally {store.close();Dispatchers.resetMain()}
    }
}
