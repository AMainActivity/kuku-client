package dev.messenger.data

import dev.messenger.data.wire.ConnectionLost
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SyncRequestsTest {
    @Test fun queuedCallersSharePassAndLaterCallerWaitsForNextPass() = runTest {
        val queue = SyncRequests()
        try {
            val first = async { queue.request("account") }
            val second = async { queue.request("account") }
            runCurrent()
            repeat(10) { queue.wake() }
            val release = CompletableDeferred<Unit>()
            var passes = 0
            val consumer = launch { queue.runNext("account", 60_000) { passes++; release.await() } }
            runCurrent()
            val later = async { queue.request("account") }
            runCurrent()
            assertFalse(first.isCompleted)
            assertFalse(later.isCompleted)
            release.complete(Unit)
            consumer.join()
            first.await(); second.await()
            assertFalse(later.isCompleted, "An in-progress pass must not acknowledge a later request")
            queue.runNext("account", 60_000) { passes++ }
            later.await()
            assertEquals(2, passes)
            val idle = launch { queue.runNext("account", 60_000) { passes++ } }
            runCurrent()
            assertEquals(2, passes, "Conflated hints must not cause extra passes")
            idle.cancelAndJoin()
        } finally { queue.close() }
    }

    @Test fun failureReachesAllWaitingCallersAndConsumerCanRetry() = runTest {
        val queue = SyncRequests()
        try {
            val first = async { assertFailsWith<ConnectionLost> { queue.request("a") } }
            val second = async { assertFailsWith<ConnectionLost> { queue.request("a") } }
            runCurrent()
            assertFailsWith<ConnectionLost> {
                queue.runNext("a", 60_000) { throw ConnectionLost() }
            }
            first.await(); second.await()
            val next = async { queue.request("a") }
            runCurrent()
            queue.runNext("a", 60_000) { }
            next.await()
        } finally { queue.close() }
    }

    @Test fun callerCancellationDoesNotCancelSharedWorkAndCloseReleasesQueuedCallers() = runTest {
        val queue = SyncRequests()
        val cancelled = async { queue.request("a") }
        val waiting = async { queue.request("a") }
        runCurrent()
        val release = CompletableDeferred<Unit>()
        val consumer = launch { queue.runNext("a", 60_000) { release.await() } }
        runCurrent()
        cancelled.cancelAndJoin()
        assertTrue(consumer.isActive)
        release.complete(Unit)
        consumer.join()
        waiting.await()
        val queued = async { assertFailsWith<CancellationException> { queue.request("a") } }
        runCurrent()
        queue.close()
        queued.await()
    }

    @Test fun consumerCancellationReleasesInFlightCaller() = runTest {
        val queue = SyncRequests()
        try {
            val waiting = async { assertFailsWith<CancellationException> { queue.request("a") } }
            runCurrent()
            val consumer = launch { queue.runNext("a", 60_000) { awaitCancellation() } }
            runCurrent()
            consumer.cancelAndJoin()
            waiting.await()
        } finally { queue.close() }
    }

    @Test fun oldAccountRequestCannotCompleteAsNewAccountWork() = runTest {
        val queue = SyncRequests()
        try {
            val old = async { assertFailsWith<ConnectionLost> { queue.request("old") } }
            val fresh = async { queue.request("new") }
            runCurrent()
            queue.runNext("new", 60_000) { }
            old.await(); fresh.await()
        } finally { queue.close() }
    }
}