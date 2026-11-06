package dev.messenger.data

import dev.messenger.data.wire.ConnectionLost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.selects.onTimeout

/** One consumer owns journal synchronization; callers only enqueue work and await its result. */
internal class SyncRequests {
    private data class Request(val accountKey: String, val result: CompletableDeferred<Unit>)
    private val requests = Channel<Request>(
        Channel.UNLIMITED,
        onUndeliveredElement = { it.result.cancel() },
    )
    private val hints = Channel<Unit>(Channel.CONFLATED)

    fun wake() { hints.trySend(Unit) }

    suspend fun request(accountKey: String) {
        val result = CompletableDeferred<Unit>()
        try {
            requests.send(Request(accountKey, result))
            result.await()
        } finally {
            result.cancel()
        }
    }

    /** Called only by the journal consumer. Requests arriving during work belong to the next pass. */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun runNext(accountKey: String, safetyIntervalMillis: Long, synchronize: suspend () -> Unit) {
        val first = select<Request?> {
            requests.onReceive { it }
            hints.onReceive { null }
            onTimeout(safetyIntervalMillis) { null }
        }
        val batch = mutableListOf<Request>()
        fun include(request: Request) {
            if (request.accountKey != accountKey) request.result.completeExceptionally(ConnectionLost())
            else if (request.result.isActive) batch.add(request)
        }
        first?.let(::include)
        while (true) include(requests.tryReceive().getOrNull() ?: break)
        // All hints received before this pass are covered by its pull.
        hints.tryReceive()
        try {
            synchronize()
            batch.forEach { it.result.complete(Unit) }
        } catch (failure: Throwable) {
            batch.forEach { it.result.completeExceptionally(failure) }
            throw failure
        }
    }

    fun close() {
        requests.cancel()
        hints.cancel()
    }
}