package dev.messenger.data.network

import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

/** One owner and one reader per physical socket. Implementations must unblock receive on close. */
interface TextSocket {
    suspend fun receive(): String?
    suspend fun send(text: String)
    fun close()
}
enum class ConnectionStatus { OPEN, CLOSED, AUTH_REQUIRED }

class RpcConnection internal constructor(
    private val socket: TextSocket,
    parent: CoroutineScope,
    override val serverId: String,
    private val timeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
    private val onFailure:(Exception)->Unit = {},
) : dev.messenger.data.session.RpcChannel {
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private val mutex = Mutex()
    private val writer = Mutex()
    private val capacity = Semaphore(MAX_IN_FLIGHT)
    private val pending = mutableMapOf<String, CompletableDeferred<JsonElement>>()
    private val mutableStatus = MutableStateFlow(ConnectionStatus.OPEN)
    override val status: StateFlow<ConnectionStatus> = mutableStatus.asStateFlow()
    // This is only a conflated hint. Durable updates come from sync.pull.
    private val mutableWatermark = MutableStateFlow<Long?>(null)
    override val highWatermark: StateFlow<Long?> = mutableWatermark.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var failure: Exception = ConnectionLost()
            try {
                while (true) {
                    val text = socket.receive() ?: run {onFailure(ConnectionLost());break}
                    when (val frame = decodeIncoming(text)) {
                        is Incoming.Response -> {
                            val requestId = frame.requestId ?: throw ProtocolFailure("UNCORRELATED_RESPONSE")
                            val waiter = mutex.withLock { pending.remove(requestId) }
                            // Late responses after timeout/cancellation are not another request's response.
                            if (frame.error != null) waiter?.completeExceptionally(frame.error)
                            else waiter?.complete(frame.data ?: throw ProtocolFailure("MISSING_RESULT"))
                        }
                        is Incoming.Event -> when (frame.name) {
                            "auth.required" -> {
                                mutableStatus.value = ConnectionStatus.AUTH_REQUIRED
                                throw ProtocolFailure("AUTH_REQUIRED")
                            }
                            "sync.available" -> {
                                val value = frame.data["highWatermark"] as? JsonPrimitive
                                val watermark = value?.takeUnless { it.isString }?.longOrNull
                                    ?: throw ProtocolFailure("INVALID_WATERMARK")
                                if (watermark !in 0..MAX_SAFE_INTEGER) throw ProtocolFailure("INVALID_WATERMARK")
                                mutableWatermark.value = watermark
                            }
                            else -> Unit // Unknown hints are not the durable journal.
                        }
                        is Incoming.Hello -> throw ProtocolFailure("UNEXPECTED_HELLO")
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: ProtocolFailure) {
                if (error.code == "AUTH_REQUIRED") mutableStatus.value = ConnectionStatus.AUTH_REQUIRED
                onFailure(error)
                failure = error
            }
            catch (error: Exception) {onFailure(error); failure = error }
            finally { withContext(NonCancellable) { shutdown(failure) } }
        }
        scope.launch {
            try {
                while (isActive) {
                    delay(PING_INTERVAL_MILLIS)
                    request(Commands.Ping, EmptyParams())
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {onFailure(failure); shutdown(ConnectionLost()) }
        }
    }

    override suspend fun <P, R> request(command: Command<P, R>, params: P, operationId: Uuid?): R =
        capacity.withPermit {
            val requestId = Uuid.random()
            val text = encodeRequest(command, params, requestId, operationId)
            val waiter = CompletableDeferred<JsonElement>()
            mutex.withLock {
                if (mutableStatus.value != ConnectionStatus.OPEN) throw ConnectionLost()
                pending[requestId.toString()] = waiter
            }
            try {
                val result = withTimeoutOrNull(timeoutMillis) {
                    writer.withLock {
                        try { socket.send(text) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { shutdown(ConnectionLost()); throw ConnectionLost() }
                    }
                    waiter.await()
                } ?: throw RequestTimeout(command.mutation || command.name.startsWith("auth.email."))
                try { wireJson.decodeFromJsonElement(command.result, result) }
                catch (_: Exception) { throw ProtocolFailure("INVALID_COMMAND_RESULT") }
            } finally {
                withContext(NonCancellable) { mutex.withLock { pending.remove(requestId.toString()) } }
                waiter.cancel()
            }
        }

    private suspend fun shutdown(failure: Exception) {
        val abandoned = mutex.withLock {
            if (mutableStatus.value == ConnectionStatus.OPEN) mutableStatus.value = ConnectionStatus.CLOSED
            pending.values.toList().also { pending.clear() }
        }
        abandoned.forEach { it.completeExceptionally(failure) }
        socket.close()
        lifetime.cancel()
    }

    override suspend fun close() {
        shutdown(ConnectionLost())
        lifetime.join()
    }

    companion object {
        const val REQUEST_TIMEOUT_MILLIS = 30_000L
        const val PING_INTERVAL_MILLIS = 25_000L
        const val MAX_IN_FLIGHT = 64
    }
}
