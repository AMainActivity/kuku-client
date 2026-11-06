package dev.messenger.data.session

import dev.messenger.data.wire.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.time.TimeSource

/** Per-account command admission. Never owns a socket, reader, or transport semaphore. */
internal class RateLimitGate(
    private val nowMillis: () -> Long = monotonicClock(),
) {
    private val lock = Mutex()
    private val deadlines = mutableMapOf<Pair<String, String>, Long>()

    suspend fun <R> run(account: String, command: Command<*, *>, operation: String, request: suspend () -> R): R {
        val scope = scope(command) ?: return request()
        val localKey = "operation:$operation"
        while (true) {
            while (true) {
                val remaining = lock.withLock {
                    val now = nowMillis()
                    deadlines.entries.removeAll { it.value <= now }
                    maxOf(deadlines[account to scope] ?: now, deadlines[account to localKey] ?: now) - now
                }
                if (remaining <= 0) break
                delay(remaining)
            }
            try {
                return request()
            } catch (failure: RemoteFailure) {
                if (failure.code != "RATE_LIMITED") throw failure
                val returnedScope = (failure.details["scope"] as? JsonPrimitive)?.contentOrNull
                val key = returnedScope?.takeIf { it in SCOPES } ?: localKey
                val seconds = (failure.details["retryAfterSeconds"] as? JsonPrimitive)
                    ?.longOrNull?.takeIf { it > 0 } ?: FALLBACK_RETRY_SECONDS
                lock.withLock {
                    val now = nowMillis()
                    val wait = seconds.coerceAtMost((Long.MAX_VALUE - now) / 1000) * 1000
                    deadlines[account to key] = maxOf(deadlines[account to key] ?: now, now + wait)
                    deadlines[account to localKey] = maxOf(deadlines[account to localKey] ?: now, now + wait)
                }
                // Admission and replay policy are independent: MFA/logout share server budgets,
                // but an authentication command must return the rejection for explicit user action.
                if (command.name.startsWith("auth.")) throw failure
                // No mutation identity or payload changes between attempts.
            }
        }
    }

    companion object {
        private const val FALLBACK_RETRY_SECONDS = 15L
        private val SCOPES = setOf("sync", "messages", "read", "write")
        private val EXEMPT_COMMANDS = setOf(
            "server.info", "ping", "auth.email.start", "auth.email.verify", "auth.refresh", "auth.resume",
        )
        private fun monotonicClock(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
        private fun scope(command: Command<*, *>): String? = when {
            command.name in EXEMPT_COMMANDS -> null
            command.name in setOf("sync.start", "sync.pull", "sync.ack", "receipts.set") -> "sync"
            command.name in setOf("messages.send", "messages.forward") -> "messages"
            command.mutation -> "write"
            else -> "read"
        }
    }
}