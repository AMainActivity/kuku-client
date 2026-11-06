package dev.messenger.data

import dev.messenger.data.network.*
import dev.messenger.data.session.SessionCoordinator
import dev.messenger.data.wire.*
import dev.messenger.domain.ServerEndpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString
import kotlin.uuid.Uuid

class TransferRepository(private val database: MessengerDatabase, val files: ManagedFiles,
    private val network: MessengerNetwork, private val sessions: SessionCoordinator, private val owner: CoroutineScope) {
    private val dao = database.transfers()
    private val downloadRequests = Mutex()
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeWork:Flow<Boolean> = sessions.state.map { it.account?.key }.distinctUntilChanged().flatMapLatest { key ->
        if(key==null) flowOf(false) else dao.observeWorkCount(key).map { it>0 }
    }.distinctUntilChanged()
    private var worker: Job? = null
    fun observe(accountId: String, chatId: String) = dao.observe(accountId, chatId)
    fun start() {
        if(worker != null) return
        worker = owner.launch {
            sessions.state.map { it.account?.key }.distinctUntilChanged().collectLatest { accountId ->
                if(accountId == null) return@collectLatest
                coroutineScope { listOf("UPLOAD","DOWNLOAD").forEach { direction ->
                    launch(Dispatchers.IO) {
                        while(isActive) {
                            val job = dao.next(accountId, direction)
                            if(job == null) { delay(1000); continue }
                            try {
                                if(job.state == "CANCELLING") cancelRemote(job)
                                else if(direction == "UPLOAD") upload(job) else download(job)
                            } catch(cancelled: CancellationException) { throw cancelled }
                            catch(failure: Exception) {
                                val retryable = failure !is RemoteFailure || failure.retryable
                                val permanentHttp = (failure as? FileHttpFailure)?.status in setOf(400,403,404,413,416)
                                if(!retryable || permanentHttp || failure is IllegalArgumentException) {
                                    if(job.state=="CANCELLING") dao.state(accountId,job.jobId,"FAILED")
                                    dao.outcome(accountId,job.jobId,"FAILED",ConversationRepository.safeError(failure))
                                }
                                else delay(5000)
                            }
                        }
                    }
                } }
            }
        }
    }
    suspend fun clearDownloadCache(accountId:String) = withContext(Dispatchers.IO) {
        dao.all(accountId).filter { it.direction=="DOWNLOAD" && it.state=="READY" }.forEach { job ->
            files.remove(accountId,job.jobId); dao.remove(accountId,job.jobId)
        }
    }
    suspend fun clearAccount(accountId:String) = withContext(Dispatchers.IO) {
        stop()
        try { dao.all(accountId).forEach { files.remove(accountId,it.jobId) }; dao.clear(accountId) }
        finally { start() }
    }
    suspend fun stop() { worker?.cancelAndJoin(); worker=null }
    suspend fun registerUpload(accountId: String, chatId: String, jobId: String, filename: String, mime: String, purpose:String = "attachment") = withContext(Dispatchers.IO) {
        require(sessions.state.value.account?.key == accountId)
        val size = files.size(accountId,jobId)
        require(purpose in setOf("attachment","avatar"))
        require(size in 1..if(purpose=="avatar") 5L*1024*1024 else ManagedFiles.MAX_BYTES)
        dao.put(TransferEntity(accountId,jobId,chatId,"UPLOAD",filename.take(255),mime.take(120),size,files.hash(accountId,jobId),purpose=purpose))
    }
    suspend fun avatarBytes(accountId:String,avatarId:String):ByteArray = withContext(Dispatchers.IO) {
        require(sessions.state.value.account?.key==accountId)
        val cached=dao.get(accountId,avatarId)
        if(cached?.state=="READY" && files.size(accountId,avatarId)==cached.size)
            return@withContext files.read(accountId,avatarId,0,cached.size)
        val part=http { authority,token -> network.downloadPart(authority,token,avatarId,0,5L*1024*1024-1,null) }
        val bytes=part.bytes
        require(bytes.isNotEmpty() && bytes.size<=5*1024*1024)
        if(part.status==206) require(part.range=="bytes 0-${bytes.size-1}/${bytes.size}")
        files.write(accountId,avatarId,0,bytes)
        dao.put(TransferEntity(accountId,avatarId,"avatar-cache","DOWNLOAD","avatar.jpg","image/jpeg",
            bytes.size.toLong(),bytes.toByteString().sha256().hex(),state="READY",remoteId=avatarId,purpose="avatar",etag=part.etag))
        bytes
    }
    suspend fun enqueueDownload(accountId: String, chatId: String, attachment: Attachment) = downloadRequests.withLock {
        require(sessions.state.value.account?.key == accountId)
        require(attachment.size in 1..ManagedFiles.MAX_BYTES && attachment.status == "ready")
        val id = attachment.attachmentId
        val existing=dao.get(accountId,id)
        if(existing?.state in setOf("QUEUED","RUNNING","CANCELLING")) return@withLock
        if(existing?.state == "READY" && files.size(accountId,id)==attachment.size) return@withLock
        dao.put(TransferEntity(accountId,id,chatId,"DOWNLOAD",attachment.filename,attachment.mime,
            attachment.size,attachment.sha256,remoteId=id))
    }
    suspend fun prepareShare(accountId:String,chatId:String,attachments:List<Attachment>):List<TransferEntity> {
        val unique=attachments.distinctBy { it.attachmentId }
        if(unique.isEmpty()) return emptyList()
        unique.forEach { enqueueDownload(accountId,chatId,it) }
        val ids=unique.map { it.attachmentId }.toSet()
        val rows=dao.observeJobs(accountId,ids.toList()).first { jobs ->
            jobs.size!=ids.size || jobs.any { it.state in setOf("FAILED","CANCELLED") } || jobs.all { it.state=="READY" }
        }
        check(rows.size==ids.size && rows.all { it.state=="READY" }) { "SHARE_FILES_UNAVAILABLE" }
        val byId=rows.associateBy { it.jobId }
        return unique.map { requireNotNull(byId[it.attachmentId]) }
    }
    suspend fun pause(accountId: String, jobId: String) = dao.state(accountId,jobId,"PAUSED")
    suspend fun resume(accountId: String, jobId: String) {
        // A terminal remote upload requires a new mutation identity; local job identity stays in the draft/outbox.
        if(dao.restartExpired(accountId,jobId,Uuid.random().toString(),Uuid.random().toString(),Uuid.random().toString())==0)
            dao.state(accountId,jobId,"QUEUED")
    }
    suspend fun cancel(accountId: String, jobId: String) = dao.state(accountId,jobId,"CANCELLING")
    private suspend fun active(job: TransferEntity): Boolean =
        sessions.state.value.account?.key == job.accountId &&
        dao.get(job.accountId,job.jobId)?.state in setOf("QUEUED","RUNNING")
    private suspend fun <T> http(work: suspend (String,String)->T): T {
        suspend fun perform(): T {
            val saved = sessions.transferCredentials()
            val endpoint = requireNotNull(ServerEndpoint.parse(saved.host,saved.port.toString()))
            return work(endpoint.authority,saved.credentials.accessToken)
        }
        try { return perform() }
        catch(failure: FileHttpFailure) {
            if(failure.status != 401) throw failure
            sessions.disconnect()
            return perform()
        }
    }
    private suspend fun upload(job: TransferEntity) {
        if(!active(job)) return
        require(files.size(job.accountId,job.jobId)==job.size)
        var remoteId = job.remoteId
        if(remoteId == null) {
            remoteId = sessions.request(MoreCommands.UploadsCreate,UploadCreateParams(job.filename,job.mime,job.size,job.sha256,job.purpose),
                Uuid.parse(job.createId ?: job.jobId)).uploadId
            dao.remote(job.accountId,job.jobId,remoteId)
        }
        val status = sessions.request(MoreCommands.UploadsStatus,UploadParams(remoteId))
        if(status.status=="ready") { dao.outcome(job.accountId,job.jobId,"READY",null); return }
        if(status.status in setOf("cancelled","expired","failed"))
            throw RemoteFailure(if(status.status=="expired") "UPLOAD_EXPIRED" else "UPLOAD_TERMINAL",false,kotlinx.serialization.json.JsonObject(emptyMap()))
        require(status.size==job.size && status.sha256==job.sha256 && status.partBytes in 1..5*1024*1024)
        val parts = status.parts.associateBy { it.number }
        var confirmed = status.confirmedBytes
        dao.progress(job.accountId,job.jobId,confirmed,null)
        for(number in 1..status.partCount) {
            if(!active(job)) return
            val offset=(number-1).toLong()*status.partBytes
            val count=minOf(status.partBytes.toLong(),job.size-offset)
            val data=files.read(job.accountId,job.jobId,offset,count)
            val hash=data.toByteString().sha256().hex()
            val part=parts[number]
            if(part!=null) { require(part.size.toLong()==count && part.sha256==hash); continue }
            http { authority,token -> network.putPart(authority,token,remoteId,number,hash,data) }
            confirmed+=count
            dao.progress(job.accountId,job.jobId,confirmed,null)
        }
        if(!active(job)) return
        val completed=sessions.request(MoreCommands.UploadsComplete,UploadParams(remoteId),Uuid.parse(job.completeId))
        if(completed.status=="ready") dao.outcome(job.accountId,job.jobId,"READY",null)
        else delay(1000)
    }
    private suspend fun download(job: TransferEntity) {
        var offset=files.size(job.accountId,job.jobId)
        require(offset<=job.size)
        var etag=job.etag
        while(offset<job.size && active(job)) {
            val end=minOf(offset+5L*1024*1024-1,job.size-1)
            val part=http { authority,token -> network.downloadPart(authority,token,requireNotNull(job.remoteId),offset,end,etag) }
            if(part.status==200 && offset>0) {
                files.remove(job.accountId,job.jobId); dao.progress(job.accountId,job.jobId,0,null)
                return
            }
            require(part.bytes.size.toLong()==end-offset+1)
            if(part.status==206) require(part.range=="bytes $offset-$end/${job.size}")
            else require(offset==0L && end==job.size-1)
            require(part.etag!=null && (etag==null || etag==part.etag))
            etag=part.etag
            // Persist validator before durable bytes. Length is authoritative after a process crash.
            dao.progress(job.accountId,job.jobId,offset,etag)
            files.write(job.accountId,job.jobId,offset,part.bytes)
            offset+=part.bytes.size
            dao.progress(job.accountId,job.jobId,offset,etag)
        }
        if(offset==job.size && active(job)) {
            require(files.hash(job.accountId,job.jobId)==job.sha256)
            dao.outcome(job.accountId,job.jobId,"READY",null)
        }
    }
    private suspend fun cancelRemote(job: TransferEntity) {
        if(job.direction=="UPLOAD" && job.remoteId!=null) {
            try { sessions.request(MoreCommands.UploadsCancel,UploadParams(job.remoteId),Uuid.parse(job.cancelId)) }
            catch(failure:RemoteFailure) {
                // Cancelling a local transfer never deletes an already published attachment.
                if(failure.code!="FILE_IN_USE") throw failure
            }
        }
        files.remove(job.accountId,job.jobId)
        dao.state(job.accountId,job.jobId,"CANCELLED")
    }
}
