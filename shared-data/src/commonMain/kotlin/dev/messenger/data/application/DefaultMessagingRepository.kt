package dev.messenger.data.application

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.data.ConversationRepository
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class DefaultMessagingRepository(private val source:ConversationRepository,catalog:ChatCatalog,private val commands:RepositoryCommands):MessagingRepository,ChatCatalog by catalog {
    override val error=source.error
    override val historyGeneration=source.historyGeneration
    override fun members(key:String,chatId:String)=source.members(key,chatId).map {it.map {row -> row.toModel()}}
    override suspend fun loadMembers(key:String,chatId:String)=applicationCall {source.loadMembers(key,chatId)}
    override suspend fun initialUnreadCursor(key:String,chatId:String)=applicationCall {source.initialUnreadCursor(key,chatId)}
    override fun observeHistoryWindow(key:String,chatId:String,lower:Long,upper:Long?,limit:Int)=source.observeHistoryWindow(key,chatId,lower,upper,limit).map {it.map {row -> row.toModel()}}
    override suspend fun historyPage(key:String,chatId:String,direction:String,anchor:Long?,limit:Int)=applicationCall {source.historyPage(key,chatId,direction,anchor,limit).toModel()}
    override fun replyTargets(key:String,chatId:String,ids:List<String>)=source.replyTargets(key,chatId,ids).map {it.mapValues {entry -> entry.value.toModel()}}
    override suspend fun messageSequence(key:String,chatId:String,messageId:String)=applicationCall {source.messageSequence(key,chatId,messageId)}
    override suspend fun send(key:String,chatId:String,text:String,replyTo:String?,files:List<String>) {applicationCall {source.send(key,chatId,text,replyTo,files)}}
    override suspend fun read(key:String,chatId:String,seq:Long)=applicationCall {source.read(key,chatId,seq)}
    override suspend fun retry(key:String,operationId:String)=applicationCall {source.retry(key,operationId)}
    override suspend fun logout()=applicationCall {source.logout()}
    override suspend fun messagesEdit(messageId: String, expectedVersion: Long, text: String):Unit {  commands.execute(MoreCommands.MessagesEdit,EditParams(messageId,expectedVersion,text)); }
    override suspend fun messagesDelete(messageId: String, expectedVersion: Long):Unit {  commands.execute(MoreCommands.MessagesDelete,DeleteParams(messageId,expectedVersion)); }
    override suspend fun messagesForward(chatId: String, messageIds: List<String>):Unit {  commands.execute(MoreCommands.MessagesForward,ForwardParams(chatId,messageIds)); }
}
