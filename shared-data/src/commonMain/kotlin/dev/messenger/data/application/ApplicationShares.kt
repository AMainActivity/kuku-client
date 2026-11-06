package dev.messenger.data.application

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.data.ShareInboxRepository
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ApplicationShares(private val source:ShareInboxRepository):IncomingShareRepository {
    override val pending=source.pending.map {rows -> rows.map {IncomingShare(it.shareId,it.text,wireJson.decodeFromString<List<dev.messenger.data.SharedFile>>(it.filesJson).size)}}
    override suspend fun accept(id:String,accountId:String,chatId:String)=applicationCall {source.accept(id,accountId,chatId)}
    override suspend fun discard(id:String) {applicationCall {source.discard(id)}}
}
