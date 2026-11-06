package dev.messenger.data

import dev.messenger.data.wire.*
import dev.messenger.domain.ServerEndpoint
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Explicit opt-in: checks discovery/hello/ping only; never sends mail or authenticates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class LiveConnectionProbeTest {
    @Test fun discoveryHelloAndPing() {
        val host=System.getenv("KUKU_LIVE_PROBE_HOST")
        assumeTrue("Live probe requires explicit host",!host.isNullOrBlank())
        runBlocking {
            val owner=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val network=androidNetwork()
            try {
                val endpoint=requireNotNull(ServerEndpoint.parse(requireNotNull(host),"443"))
                println("PROBE discovery")
                network.discover(endpoint)
                println("PROBE websocket hello")
                val connection=network.connect(endpoint,owner)
                try { connection.request(Commands.Ping,EmptyParams());println("PROBE ping OK") }
                finally { connection.close() }
            } finally { owner.cancel();network.close() }
        }
    }
}