package dev.messenger.data.network

import dev.messenger.data.ConversationRepository

/** No exception messages, payloads, email addresses, codes or credentials in diagnostics. */
fun reportAuthFailure(failure:Exception) {
    val types=generateSequence<Throwable>(failure) { it.cause }.take(4)
        .joinToString(" -> ") { it::class.simpleName ?: "Exception" }
    writeDiagnostic("auth failure: ${ConversationRepository.safeError(failure)}; $types")
}
internal expect fun writeDiagnostic(summary:String)

internal fun reportConnectionFailure(failure:Exception) {
    val types=generateSequence<Throwable>(failure) {it.cause}.take(4)
        .joinToString(" -> ") {it::class.simpleName ?: "Exception"}
    writeDiagnostic("connection cycle failure: "+ConversationRepository.safeError(failure)+"; "+types)
}
fun reportTokenRefreshed() {writeDiagnostic("access token refreshed")}

internal fun reportSocketClosed(code: Int?, reason: String?) {
    // Never print arbitrary server text: it may contain private payloads.
    val safeReason = when (reason) {
        "Frame rate exceeded" -> "frame_rate_exceeded"
        "Send ping at least every 30 seconds" -> "heartbeat_timeout"
        "Authentication deadline exceeded" -> "authentication_deadline"
        "User connection limit" -> "user_connection_limit"
        "JSON text frames required" -> "invalid_frame_type"
        null, "" -> "unspecified"
        else -> "other"
    }
    writeDiagnostic("websocket closed: code=" + (code?.toString() ?: "unknown") + "; reason=" + safeReason)
}