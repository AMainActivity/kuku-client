package dev.messenger.data

import dev.messenger.domain.AccountId
import dev.messenger.domain.LocalMessages

/** The composition root owns this handle; Room types stay inside the data layer. */
class LocalStore internal constructor(private val database: MessengerDatabase) {
    fun messages(accountId: AccountId): LocalMessages = RoomLocalMessages(database.messages(), accountId)
    fun conversations(sessions: dev.messenger.data.session.SessionCoordinator, owner: kotlinx.coroutines.CoroutineScope) = ConversationRepository(database, sessions, owner)
    fun transfers(files: ManagedFiles, network: dev.messenger.data.network.MessengerNetwork, sessions: dev.messenger.data.session.SessionCoordinator, owner: kotlinx.coroutines.CoroutineScope) = TransferRepository(database, files, network, sessions, owner)
    suspend fun composer(accountId: String, chatId: String): ComposerExtras {
        val draft = database.messages().draft(accountId,chatId)
        return ComposerExtras(draft?.replyTo, draft?.filesJson?.let { dev.messenger.data.wire.wireJson.decodeFromString<List<String>>(it) } ?: emptyList())
    }
    suspend fun saveComposer(accountId: String, chatId: String, replyTo: String?, files: List<String>) =
        database.messages().saveExtras(accountId,chatId,replyTo,dev.messenger.data.wire.wireJson.encodeToString(files))
    fun drafts(accountId:String)=database.messages().observeDrafts(accountId)
    fun shares(files:ManagedFiles)=ShareInboxRepository(database.shares(),files)
    fun close() = database.close()
}
data class ComposerExtras(val replyTo: String?, val files: List<String>)
