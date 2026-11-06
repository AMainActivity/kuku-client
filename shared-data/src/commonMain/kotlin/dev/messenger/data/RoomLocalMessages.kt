package dev.messenger.data

import dev.messenger.domain.*
import kotlinx.coroutines.flow.map
import kotlin.uuid.Uuid

/** Account is fixed for this repository's lifetime; callers cannot accidentally mix scopes. */
class RoomLocalMessages(private val dao: MessagesDao, accountId: AccountId) : LocalMessages {
    private val accountKey = accountId.storageKey
    override fun observeDraft(chatId: ChatId) =
        dao.observeDraft(accountKey, chatId.value.toString()).map { it?.toDomain() }

    override fun observePending(chatId: ChatId) =
        dao.observePending(accountKey, chatId.value.toString()).map { rows -> rows.map { it.toDomain() } }

    override suspend fun saveDraft(chatId: ChatId, text: String): Draft =
        dao.saveDraft(accountKey, chatId.value.toString(), text).toDomain()

    override suspend fun enqueueDraft(draft: Draft, operationId: OperationId, createdAtMillis: Long) {
        require(draft.text.isNotBlank())
        require(createdAtMillis >= 0)
        dao.enqueue(
            DraftEntity(accountKey, draft.chatId.value.toString(), draft.text, draft.revision),
            OutboxEntity(accountKey, operationId.value.toString(), draft.chatId.value.toString(),
                draft.text, createdAtMillis, OutboxState.READY.name),
        )
    }
    override suspend fun readyToSend(limit: Int): List<PendingMessage> {
        require(limit in 1..MAX_BATCH_SIZE)
        return dao.ready(accountKey, limit).map { it.toDomain() }
    }
    private fun DraftEntity.toDomain() = Draft(ChatId(Uuid.parse(chatId)), text, revision)
    private fun OutboxEntity.toDomain() = PendingMessage(
        OperationId(Uuid.parse(operationId)), ChatId(Uuid.parse(chatId)), text, createdAtMillis,
        OutboxState.valueOf(state),
    )
    companion object { const val MAX_BATCH_SIZE = 100 }
}
