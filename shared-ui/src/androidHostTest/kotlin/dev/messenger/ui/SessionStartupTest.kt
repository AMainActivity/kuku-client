package dev.messenger.ui

import dev.messenger.core.ui.androidUiSettings
import dev.messenger.data.*
import dev.messenger.data.network.MessengerNetwork
import dev.messenger.data.session.SecureStore
import dev.messenger.runtime.SessionRestoration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class SessionStartupTest {
    private fun container(secure:SecureStore):AppContainer {
        val context=RuntimeEnvironment.getApplication()
        return AppContainer(androidLocalStore(context),MessengerNetwork(HttpClient(MockEngine {error("Network unavailable")})),
            secure,ManagedFiles(context.cacheDir.resolve("startup-test").path),androidUiSettings(context))
    }
    @Test fun delayedStorageAndFailureDoNotBecomeSignedOutAndRetryRecovers()=runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        var fail=true
        val app=container(object:SecureStore {
            override suspend fun read():String? {
                entered.complete(Unit);release.await()
                if(fail) throw java.io.IOException("Test storage unavailable")
                return null
            }
            override suspend fun write(value:String) {}
        })
        try {
            app.setActive("ui",true)
            withTimeout(5000) {entered.await()}
            assertFalse(app.sessions.state.value.loaded)
            assertEquals(SessionRestoration.RESTORING,app.runtime.restoration.value)
            release.complete(Unit)
            withTimeout(5000) {app.runtime.restoration.first {it==SessionRestoration.FAILED}}
            assertFalse(app.sessions.state.value.loaded)
            fail=false
            app.setActive("ui",true)
            withTimeout(5000) {app.runtime.restoration.first {it==SessionRestoration.READY}}
            assertTrue(app.sessions.state.value.loaded)
            assertNull(app.sessions.state.value.account)
        } finally {release.complete(Unit);app.closeAndJoin()}
    }
    @Test fun savedAccountIsRestoredWithoutWaitingForNetwork()=runBlocking<Unit> {
        val user=java.util.UUID.randomUUID().toString()
        val server=java.util.UUID.randomUUID().toString()
        val device=java.util.UUID.randomUUID().toString()
        val session=java.util.UUID.randomUUID().toString()
        val saved="""{"deviceId":"$device","session":{"host":"example.com","port":443,"credentials":{"sessionId":"$session","userId":"$user","deviceId":"$device","serverId":"$server","accessToken":"${"a".repeat(40)}","refreshToken":"${"r".repeat(40)}","accessExpiresAt":"2100-01-01T00:00:00Z"}}}"""
        val app=container(object:SecureStore {
            override suspend fun read()=saved
            override suspend fun write(value:String) {}
        })
        try {
            app.setActive("ui",true)
            withTimeout(5000) {app.runtime.restoration.first {it==SessionRestoration.READY}}
            val state=app.sessions.state.value
            assertTrue(state.loaded)
            assertEquals("$server:$user",state.account?.key)
            assertFalse(state.online)
        } finally {app.closeAndJoin()}
    }
}
