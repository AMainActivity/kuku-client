package dev.messenger.data.session

import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RateLimitGateTest {
    private fun limited(scope: String?, seconds: Long = 10) = RemoteFailure("RATE_LIMITED", true,
        buildJsonObject { put("retryAfterSeconds", seconds); scope?.let { put("scope", it) } })

    @Test fun scopeWaitsWhileOtherCategoriesAndAccountsContinue() = runTest {
        val gate = RateLimitGate { currentTime }
        var attempts = 0
        val waiting = async {
            gate.run("a", Commands.MessagesSend, "first") {
                if (++attempts == 1) throw limited("messages")
                "sent"
            }
        }
        runCurrent()
        val sibling = async { gate.run("a", Commands.MessagesSend, "second") { "second" } }
        runCurrent()
        assertFalse(sibling.isCompleted)
        assertEquals("read", gate.run("a", Commands.ChatsList, "read") { "read" })
        assertEquals("sync", gate.run("a", Commands.SyncPull, "sync") { "sync" })
        assertEquals("ping", gate.run("a", Commands.Ping, "ping") { "ping" })
        assertEquals("other", gate.run("b", Commands.MessagesSend, "other") { "other" })
        advanceTimeBy(9999); runCurrent()
        assertEquals(1, attempts)
        assertFalse(sibling.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals("sent", waiting.await())
        assertEquals("second", sibling.await())
    }

    @Test fun inFlightFailureCanExtendExistingDeadline() = runTest {
        val gate = RateLimitGate { currentTime }
        val lateFailure = CompletableDeferred<Unit>()
        var firstAttempts = 0
        var secondAttempts = 0
        val second = async {
            gate.run("a", Commands.SyncPull, "second") {
                if (++secondAttempts == 1) { lateFailure.await(); throw limited("sync", 20) }
                Unit
            }
        }
        runCurrent()
        val first = async {
            gate.run("a", Commands.SyncPull, "first") {
                if (++firstAttempts == 1) throw limited("sync", 10)
                Unit
            }
        }
        runCurrent()
        advanceTimeBy(5000)
        lateFailure.complete(Unit); runCurrent()
        advanceTimeBy(5000); runCurrent()
        assertEquals(1, firstAttempts)
        advanceTimeBy(14999); runCurrent()
        assertFalse(first.isCompleted)
        advanceTimeBy(1); runCurrent()
        first.await(); second.await()
        assertEquals(25_000L, currentTime)
    }

    @Test fun absentAndUnknownScopeDelayOnlyOriginalOperation() = runTest {
        for (scope in listOf(null, "future")) {
            val gate = RateLimitGate { currentTime }
            var attempts = 0
            val waiting = async {
                gate.run("a", Commands.ChatsList, "original") {
                    if (++attempts == 1) throw limited(scope, 2)
                    Unit
                }
            }
            runCurrent()
            assertEquals("ok", gate.run("a", Commands.ChatsList, "different") { "ok" })
            advanceTimeBy(1999); runCurrent()
            assertEquals(1, attempts)
            advanceTimeBy(1); runCurrent()
            waiting.await()
        }
    }

    @Test fun mfaCommandsWaitForTheirExistingCategoryCooldown() = runTest {
        for ((mfa, ordinary, scope) in listOf(
            Triple(MoreCommands.MfaSetup, Commands.ChatsDirect, "write"),
            Triple(MoreCommands.MfaConfirm, Commands.ChatsDirect, "write"),
            Triple(MoreCommands.MfaStepUp, Commands.ChatsList, "read"),
        )) {
            val gate = RateLimitGate { currentTime }
            var attempts = 0
            val ordinaryRequest = async {
                gate.run("a", ordinary, "ordinary") {
                    if (++attempts == 1) throw limited(scope, 2)
                    Unit
                }
            }
            runCurrent()
            var mfaCalls = 0
            val mfaRequest = async { gate.run("a", mfa, "mfa") { mfaCalls++; Unit } }
            runCurrent()
            assertEquals(0, mfaCalls, mfa.name)
            advanceTimeBy(1999); runCurrent()
            assertFalse(mfaRequest.isCompleted, mfa.name)
            advanceTimeBy(1); runCurrent()
            mfaRequest.await(); ordinaryRequest.await()
            assertEquals(1, mfaCalls)
        }
    }

    @Test fun mfaRejectionPublishesCategoryCooldownWithoutAutomaticReplay() = runTest {
        for ((mfa, ordinary, scope) in listOf(
            Triple(MoreCommands.MfaSetup, Commands.ChatsDirect, "write"),
            Triple(MoreCommands.MfaConfirm, Commands.ChatsDirect, "write"),
            Triple(MoreCommands.MfaStepUp, Commands.ChatsList, "read"),
        )) {
            val gate = RateLimitGate { currentTime }
            var attempts = 0
            val rejection = limited(scope, 2)
            assertSame(rejection, assertFailsWith<RemoteFailure> {
                gate.run("a", mfa, "mfa") { attempts++; throw rejection }
            })
            val next = async { gate.run("a", ordinary, "ordinary") { "ok" } }
            runCurrent()
            assertFalse(next.isCompleted, mfa.name)
            advanceTimeBy(1999); runCurrent()
            assertFalse(next.isCompleted, mfa.name)
            advanceTimeBy(1); runCurrent()
            assertEquals("ok", next.await())
            assertEquals(1, attempts, "A rejected authentication command must not replay its code")
        }
    }

    @Test fun onlyContractExemptCommandsBypassReadAndWriteCooldowns() = runTest {
        val gate = RateLimitGate { currentTime }
        assertFailsWith<RemoteFailure> {
            gate.run("a", MoreCommands.MfaSetup, "setup") { throw limited("write") }
        }
        assertFailsWith<RemoteFailure> {
            gate.run("a", MoreCommands.MfaStepUp, "stepup") { throw limited("read") }
        }
        for (command in listOf(Commands.ServerInfo, Commands.Ping, Commands.EmailStart,
            Commands.EmailVerify, Commands.Refresh, Commands.Resume)) {
            assertEquals("ok", gate.run("a", command, command.name) { "ok" })
            assertEquals(0L, currentTime, command.name)
        }
        val logout = async { gate.run("a", Commands.Logout, "logout") { Unit } }
        runCurrent()
        assertFalse(logout.isCompleted, "Logout consumes the server write budget")
        logout.cancelAndJoin()
    }

    @Test fun cancellationStopsRetryAndAuthIsNotAutomaticallyReplayed() = runTest {
        val gate = RateLimitGate { currentTime }
        var attempts = 0
        val waiting = launch {
            gate.run("a", Commands.MessagesSend, "x") { attempts++; throw limited("messages") }
        }
        runCurrent()
        waiting.cancelAndJoin()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, attempts)
        assertFailsWith<RemoteFailure> {
            gate.run("a", Commands.Refresh, "refresh") { throw limited(null) }
        }
    }
}