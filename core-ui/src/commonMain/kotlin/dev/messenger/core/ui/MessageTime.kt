package dev.messenger.core.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.Instant

/** UTC remains unchanged on the wire and in Room; only presentation uses the device zone. */
fun formatMessageTimestamp(timestamp:String,nowMillis:Long):String {
    val instant=try {Instant.parse(timestamp)} catch(_:IllegalArgumentException) {return "—"}
    return formatLocalMessageTime(instant.toEpochMilliseconds(),nowMillis)
}
internal expect fun formatLocalMessageTime(epochMillis:Long,nowMillis:Long):String

/** One clock per screen, suspended while hidden; refreshes date labels after midnight/resume. */
@Composable fun rememberMessageClock():State<Long> {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    return produceState(Clock.System.now().toEpochMilliseconds(),lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while(true) {
                value=Clock.System.now().toEpochMilliseconds()
                delay(MINUTE_MILLIS-value.mod(MINUTE_MILLIS))
            }
        }
    }
}
private const val MINUTE_MILLIS=60_000L