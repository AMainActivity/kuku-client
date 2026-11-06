package dev.messenger.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Backoff belongs to the single reconnect worker; network callbacks only publish state. */
internal class ReconnectWait {
    private data class NetworkState(val available:Boolean=true,val generation:Long=0)
    private val network=MutableStateFlow(NetworkState())
    private var attemptedGeneration=0L
    private var nextDelayMillis=INITIAL_DELAY_MILLIS

    fun networkAvailable() {
        val previous=network.value
        network.value=NetworkState(true,previous.generation+1)
    }
    fun networkUnavailable() {network.value=network.value.copy(available=false)}
    fun connected() {nextDelayMillis=INITIAL_DELAY_MILLIS}

    suspend fun attemptStarted() {
        attemptedGeneration=network.first {it.available}.generation
    }

    suspend fun afterFailure(retryAfterSeconds:Long?) {
        val pause=nextDelayMillis
        nextDelayMillis=(nextDelayMillis*2).coerceAtMost(MAX_DELAY_MILLIS)
        if(retryAfterSeconds!=null && retryAfterSeconds>0) {
            // Never shorten the server's throttle, including on a new network.
            delay(retryAfterSeconds.coerceAtMost(Long.MAX_VALUE/1000)*1000)
        } else {
            withTimeoutOrNull(pause) {
                network.first {it.available && it.generation!=attemptedGeneration}
            }
        }
        val available=network.first {it.available}
        if(available.generation!=attemptedGeneration) nextDelayMillis=INITIAL_DELAY_MILLIS
    }

    companion object {
        const val INITIAL_DELAY_MILLIS=1_000L
        const val MAX_DELAY_MILLIS=15_000L
    }
}
