package dev.messenger.data.application

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.domain.model.Chat
import dev.messenger.domain.model.Page
import dev.messenger.domain.model.MemberInfo
import dev.messenger.data.ConversationRepository
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class DefaultChatCatalog(private val source:ConversationRepository, session:SessionAccess, private val commands:RepositoryCommands):ChatCatalog {
    override val connection=session.state
    override fun chats(key:String)=source.chats(key).map {it.map {row -> row.toModel()}}
    override fun chat(key:String,chatId:String)=source.chat(key,chatId).map {it?.toModel()}
    override suspend fun synchronize()=applicationCall {source.synchronize()}
    override suspend fun newDirect(userId:String)=applicationCall {source.newDirect(userId).toModel()}
    override suspend fun preferencesSet(chatId: String, expectedVersion: Long, pinned: Boolean, pinRank: Int, muted: Boolean):Unit {  commands.execute(MoreCommands.PreferencesSet,PreferenceParams(chatId,expectedVersion,pinned,pinRank,muted)); }
    override suspend fun receiptsList(chatId: String, cursor: String?, limit: Int, query: String):Page<MemberInfo> = applicationCall { commands.execute(MoreCommands.ReceiptsList,ChatPageParams(chatId,cursor,limit,query)).let {Page(it.items.map {row -> row.toModel()},it.nextCursor)} }
    override suspend fun chatsGet(chatId: String):Chat = applicationCall { commands.execute(Commands.ChatsGet,ChatParams(chatId)).toModel() }
    override suspend fun chatsDirect(userId: String):Chat = applicationCall { commands.execute(Commands.ChatsDirect,DirectParams(userId)).toModel() }
}
