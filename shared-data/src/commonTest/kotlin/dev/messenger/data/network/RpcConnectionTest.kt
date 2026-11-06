package dev.messenger.data.network

import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class RpcConnectionTest {
    private class Socket : TextSocket {
        val inbound = Channel<String>(Channel.UNLIMITED)
        val sent = Channel<String>(Channel.UNLIMITED)
        var closed = false
        override suspend fun receive() = inbound.receiveCatching().getOrNull()
        override suspend fun send(text: String) { sent.send(text) }
        override fun close() { closed = true; inbound.close(); sent.close() }
    }
    private fun response(request: String, data: JsonElement) = buildJsonObject {
        put("version", 1); put("type", "response")
        put("requestId", wireJson.parseToJsonElement(request).jsonObject.getValue("requestId"))
        put("ok", true); put("data", data)
    }.toString()

    @Test fun outOfOrderResponsesAreCorrelatedAndUnknownHintsAreHarmless() = runTest {
        val socket = Socket()
        val rpc = RpcConnection(socket, backgroundScope, Uuid.random().toString())
        try {
            val a = async { rpc.request(Commands.Ping, EmptyParams()) }
            val b = async { rpc.request(Commands.Ping, EmptyParams()) }
            val first = socket.sent.receive()
            val second = socket.sent.receive()
            socket.inbound.send("""{"version":1,"type":"event","event":"future.hint","data":{}}""")
            socket.inbound.send(response(second, buildJsonObject { put("serverTime", "second") }))
            socket.inbound.send(response(first, buildJsonObject { put("serverTime", "first") }))
            assertEquals("first", a.await().serverTime)
            assertEquals("second", b.await().serverTime)
        } finally { rpc.close() }
        assertTrue(socket.closed)
    }

    @Test fun timeoutPreservesUnknownMutationOutcomeAndLateResponseIsIgnored() = runTest {
        val socket = Socket()
        val rpc = RpcConnection(socket, backgroundScope, Uuid.random().toString(), timeoutMillis = 100)
        try {
            val id = Uuid.random().toString()
            val timed = async {
                assertFailsWith<RequestTimeout> {
                    rpc.request(Commands.MessagesSend, SendParams(id,id,PlainContent("x")), Uuid.random())
                }
            }
            val old = socket.sent.receive()
            advanceTimeBy(101)
            runCurrent()
            assertTrue(timed.await().outcomeUnknown)
            socket.inbound.send(response(old, JsonObject(emptyMap())))
            val next = async { rpc.request(Commands.Ping, EmptyParams()) }
            val request = socket.sent.receive()
            socket.inbound.send(response(request, buildJsonObject { put("serverTime", "next") }))
            assertEquals("next", next.await().serverTime)
        } finally { rpc.close() }
    }

    @Test fun socketLossCompletesAllPendingRequests() = runTest {
        val socket = Socket()
        val rpc = RpcConnection(socket, backgroundScope, Uuid.random().toString())
        val result = async { assertFailsWith<ConnectionLost> { rpc.request(Commands.Ping, EmptyParams()) } }
        socket.sent.receive()
        socket.inbound.close()
        result.await()
        rpc.close()
        assertEquals(ConnectionStatus.CLOSED, rpc.status.value)
    }

    @Test fun authRequiredClosesConnectionAndNeverRetriesCredentialsItself() = runTest {
        val socket = Socket()
        val rpc = RpcConnection(socket, backgroundScope, Uuid.random().toString())
        val result = async { assertFailsWith<ProtocolFailure> { rpc.request(Commands.Ping, EmptyParams()) } }
        socket.sent.receive()
        socket.inbound.send("""{"version":1,"type":"event","event":"auth.required","data":{}}""")
        assertEquals("AUTH_REQUIRED", result.await().code)
        assertEquals(ConnectionStatus.AUTH_REQUIRED, rpc.status.value)
        rpc.close()
    }

    @Test fun heartbeatIsApplicationPingNotOnlyWebsocketControlPing() = runTest {
        val socket = Socket()
        val rpc = RpcConnection(socket, backgroundScope, Uuid.random().toString())
        runCurrent()
        advanceTimeBy(RpcConnection.PING_INTERVAL_MILLIS)
        runCurrent()
        val request = socket.sent.receive()
        assertEquals(JsonPrimitive("ping"), wireJson.parseToJsonElement(request).jsonObject["command"])
        socket.inbound.send(response(request, buildJsonObject { put("serverTime", "now") }))
        runCurrent()
        rpc.close()
    }

    @Test fun parentCancellationReleasesTheSocket() = runTest {
        val socket = Socket()
        val owner = CoroutineScope(backgroundScope.coroutineContext + Job())
        RpcConnection(socket, owner, Uuid.random().toString())
        runCurrent()
        owner.cancel()
        runCurrent()
        assertTrue(socket.closed)
    }

    @Test fun callerTimeoutIsNotRewrittenAsMutationOutcome() = runTest {
        val socket = Socket()
        val rpc = RpcConnection(socket, backgroundScope, Uuid.random().toString(), timeoutMillis = 1000)
        try {
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(10) { rpc.request(Commands.Ping, EmptyParams()) }
            }
        } finally { rpc.close() }
    }

    @Test fun alreadyCancelledOwnerDoesNotLeakSocket() = runTest {
        val socket = Socket()
        val job = Job().also { it.cancel() }
        RpcConnection(socket, CoroutineScope(backgroundScope.coroutineContext + job), Uuid.random().toString())
        runCurrent()
        assertTrue(socket.closed)
    }

    @Test fun serverFailureRetainsRetryDetailsWithoutAutomaticRetry() = runTest {
        val socket = Socket()
        val rpc = RpcConnection(socket, backgroundScope, Uuid.random().toString())
        try {
            val result = async { assertFailsWith<RemoteFailure> { rpc.request(Commands.Ping, EmptyParams()) } }
            val request = wireJson.parseToJsonElement(socket.sent.receive()).jsonObject
            socket.inbound.send(buildJsonObject {
                put("version", 1); put("type", "response"); put("requestId", request.getValue("requestId"))
                put("ok", false)
                putJsonObject("error") {
                    put("code", "RATE_LIMITED"); put("retryable", true)
                    putJsonObject("details") { put("retryAfterSeconds", 60) }
                }
            }.toString())
            val error = result.await()
            assertEquals("RATE_LIMITED", error.code)
            assertEquals(JsonPrimitive(60), error.details["retryAfterSeconds"])
            assertTrue(error.retryable)
            assertTrue(socket.sent.tryReceive().isFailure)
        } finally { rpc.close() }
    }
}