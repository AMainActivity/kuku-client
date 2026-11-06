package dev.messenger.data.application

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.domain.model.ComposerExtras
import dev.messenger.data.LocalStore
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ApplicationDrafts(private val source:LocalStore):DraftRepository {
    override fun messages(accountId:AccountId)=source.messages(accountId)
    override suspend fun composer(accountId:String,chatId:String)=applicationCall {source.composer(accountId,chatId).let {ComposerExtras(it.replyTo,it.files)}}
    override suspend fun saveComposer(accountId:String,chatId:String,replyTo:String?,files:List<String>) {applicationCall {source.saveComposer(accountId,chatId,replyTo,files)}}
    override fun drafts(accountId:String)=source.drafts(accountId).map {rows -> rows.map {DraftPreview(it.chatId,it.text,it.replyTo,wireJson.decodeFromString<List<String>>(it.filesJson))}}
}
