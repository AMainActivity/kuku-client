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
