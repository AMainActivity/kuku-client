package dev.messenger.data.application

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.domain.model.Attachment
import dev.messenger.data.TransferRepository
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ApplicationAttachments(private val source:TransferRepository):AttachmentRepository {
    override val files:FileStorage=source.files
    override fun observe(accountId:String,chatId:String)=source.observe(accountId,chatId).map {it.map {row -> row.toModel()}}
    override suspend fun registerUpload(accountId:String,chatId:String,jobId:String,filename:String,mime:String,purpose:String) {applicationCall {source.registerUpload(accountId,chatId,jobId,filename,mime,purpose)}}
    override suspend fun avatarBytes(accountId:String,avatarId:String)=applicationCall {source.avatarBytes(accountId,avatarId)}
    override suspend fun enqueueDownload(accountId:String,chatId:String,attachment:Attachment) {applicationCall {source.enqueueDownload(accountId,chatId,attachment.toWire())}}
    override suspend fun prepareShare(accountId:String,chatId:String,attachments:List<Attachment>)=applicationCall {source.prepareShare(accountId,chatId,attachments.map {it.toWire()}).map {it.toModel()}}
    override suspend fun pause(accountId:String,jobId:String) {applicationCall {source.pause(accountId,jobId)}}
    override suspend fun resume(accountId:String,jobId:String)=applicationCall {source.resume(accountId,jobId)}
    override suspend fun cancel(accountId:String,jobId:String) {applicationCall {source.cancel(accountId,jobId)}}
    override suspend fun clearDownloadCache(accountId:String) {applicationCall {source.clearDownloadCache(accountId)}}
}
