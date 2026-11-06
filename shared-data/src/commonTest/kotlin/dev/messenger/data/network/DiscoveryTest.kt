package dev.messenger.data.network

import dev.messenger.data.wire.*
import dev.messenger.domain.ServerEndpoint
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.test.*

class DiscoveryTest {
    @Test fun discoveryUsesHttpsAndDoesNotFollowRedirect() = runTest {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals("https", request.url.protocol.name)
            assertEquals("/v1/server-info", request.url.encodedPath)
            respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://elsewhere.example/v1/server-info"))
        }
        val network = MessengerNetwork(messengerHttpClient(engine))
        try {
            assertFailsWith<ProtocolFailure> { withContext(Dispatchers.Default) { network.discover(requireNotNull(ServerEndpoint.parse("example.com", "443"))) } }
            assertEquals(1, calls)
        } finally { network.close() }
    }

    @Test fun oversizedDiscoveryIsRejected() = runTest {
        val network = MessengerNetwork(messengerHttpClient(MockEngine {
            respond(" ".repeat(MAX_RECEIVE_BYTES + 1), HttpStatusCode.OK)
        }))
        try {
            val error = assertFailsWith<ProtocolFailure> {
                withContext(Dispatchers.Default) { network.discover(requireNotNull(ServerEndpoint.parse("example.com", "443"))) }
            }
            assertEquals("FRAME_TOO_LARGE", error.code)
        } finally { network.close() }
    }
}
