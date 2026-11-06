package dev.messenger.domain

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow

/** Local account identity: independent of endpoint, email and tokens. */
data class AccountId(val serverId: Uuid, val userId: Uuid) {
    val storageKey: String get() = "$serverId:$userId"
}

data class ChatId(val value: Uuid)
data class OperationId(val value: Uuid)
data class Draft(val chatId: ChatId, val text: String, val revision: Long) {
    init { require(revision >= 0) }
}
enum class OutboxState { READY, WAITING_FOR_FILES, FAILED }
data class PendingMessage(
    val operationId: OperationId,
    val chatId: ChatId,
    val text: String,
    val createdAtMillis: Long,
    val state: OutboxState,
)
data class ChatSummary(val chatId: ChatId, val title: String)

interface LocalMessages {
    fun observeDraft(chatId: ChatId): Flow<Draft?>
    fun observePending(chatId: ChatId): Flow<List<PendingMessage>>
    suspend fun saveDraft(chatId: ChatId, text: String): Draft
    /** Atomically saves an operation and clears only the expected draft revision. */
    suspend fun enqueueDraft(draft: Draft, operationId: OperationId, createdAtMillis: Long)
    suspend fun readyToSend(limit: Int): List<PendingMessage>
}

/** No credentials are accepted here. Transport and server identity verification follow contract handoff. */
@ConsistentCopyVisibility
data class ServerEndpoint private constructor(val host: String, val port: Int) {
    val authority: String get() {
        val address=if(':' in host) "[$host]" else host
        return if(port==DEFAULT_TLS_PORT) address else "$address:$port"
    }

    companion object {
        const val DEFAULT_TLS_PORT = 443
        fun parse(host: String, port: String): ServerEndpoint? {
            val normalized=AddressValidation.normalizeHost(host) ?: return null
            val number=AddressValidation.port(port) ?: return null
            return ServerEndpoint(normalized, number)
        }
    }
}
