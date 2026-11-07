package dev.messenger.data

import androidx.room.*
import dev.messenger.data.wire.wireJson
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Entity(tableName="share_inbox")
data class ShareInboxEntity(@PrimaryKey val shareId:String,val text:String,val filesJson:String,val consumed:Boolean=false)
@Serializable data class SharedFile(val jobId:String,val filename:String,val mime:String,val size:Long,val sha256:String)
@Dao abstract class ShareInboxDao {
    @Query("SELECT * FROM share_inbox WHERE consumed=0 ORDER BY rowid")
    abstract fun observe():Flow<List<ShareInboxEntity>>
    @Query("SELECT * FROM share_inbox WHERE shareId=:id")
    abstract suspend fun get(id:String):ShareInboxEntity?
    @Insert(onConflict=OnConflictStrategy.IGNORE) abstract suspend fun insert(row:ShareInboxEntity)
    @Query("UPDATE share_inbox SET consumed=1 WHERE shareId=:id")
    abstract suspend fun consume(id:String)
    @Query("SELECT * FROM drafts WHERE accountId=:accountId AND chatId=:chatId")
    protected abstract suspend fun draft(accountId:String,chatId:String):DraftEntity?
    @Query("SELECT revoked FROM chat_cache_state WHERE accountId=:accountId AND chatId=:chatId")
    protected abstract suspend fun chatRevoked(accountId:String,chatId:String):Boolean?
    @Upsert protected abstract suspend fun putDraft(row:DraftEntity)
    @Upsert protected abstract suspend fun putTransfers(rows:List<TransferEntity>)
    @Transaction open suspend fun deliver(id:String,accountId:String,chatId:String,transfers:List<TransferEntity>) {
        val item=get(id)?.takeUnless { it.consumed } ?: return
        // Byte copying precedes this transaction; access may have been revoked meanwhile.
        // Reject before writing or consuming, so the same share can target another chat.
        if(chatRevoked(accountId,chatId)==true) throw dev.messenger.domain.ApplicationFailure("CHAT_UNAVAILABLE")
        val old=draft(accountId,chatId) ?: DraftEntity(accountId,chatId,"",0)
        val previous=wireJson.decodeFromString<List<String>>(old.filesJson)
        require(previous.size+transfers.size<=10) { "SHARE_TOO_MANY_FILES" }
        val text=listOf(old.text,item.text).filter { it.isNotEmpty() }.joinToString("\n")
        require(text.length<=16000) { "SHARE_TEXT_TOO_LONG" }
        putTransfers(transfers)
        putDraft(old.copy(text=text,filesJson=wireJson.encodeToString(previous+transfers.map { it.jobId }),revision=old.revision+1))
        consume(id)
    }
}

/** Incoming content is private and durable before selecting an account or destination. */
class ShareInboxRepository(private val dao:ShareInboxDao,val files:ManagedFiles) {
    val pending=dao.observe()
    private val lock=Mutex()
    suspend fun contains(id:String)=dao.get(id)!=null
    suspend fun record(id:String,text:String,items:List<SharedFile>) {
        Uuid.parse(id)
        require(items.size<=10 && text.length<=16000 && (text.isNotBlank() || items.isNotEmpty()))
        dao.insert(ShareInboxEntity(id,text,wireJson.encodeToString(items)))
    }
    suspend fun discard(id:String)=lock.withLock {
        val row=dao.get(id) ?: return@withLock
        dao.consume(id)
        withContext(Dispatchers.IO) { wireJson.decodeFromString<List<SharedFile>>(row.filesJson).forEach { files.remove(STAGING_ACCOUNT,it.jobId) } }
    }
    suspend fun accept(id:String,accountId:String,chatId:String)=lock.withLock {
        val row=dao.get(id)?.takeUnless { it.consumed } ?: return@withLock
        val items=wireJson.decodeFromString<List<SharedFile>>(row.filesJson)
        withContext(Dispatchers.IO) {
            val copied=mutableListOf<String>()
            var committed=false
            try {
                val jobs=items.map { item ->
                    require(files.size(STAGING_ACCOUNT,item.jobId)==item.size)
                    val jobId=Uuid.random().toString(); copied.add(jobId)
                    var offset=0L
                    while(offset<item.size) {
                        ensureActive()
                        val bytes=files.read(STAGING_ACCOUNT,item.jobId,offset,minOf(65536,item.size-offset))
                        files.write(accountId,jobId,offset,bytes); offset+=bytes.size
                    }
                    require(files.hash(accountId,jobId)==item.sha256)
                    TransferEntity(accountId,jobId,chatId,"UPLOAD",item.filename,item.mime,item.size,item.sha256)
                }
                withContext(NonCancellable) { dao.deliver(id,accountId,chatId,jobs); committed=true }
            } finally { if(!committed) copied.forEach { files.remove(accountId,it) } }
            items.forEach { files.remove(STAGING_ACCOUNT,it.jobId) }
        }
    }
    companion object { const val STAGING_ACCOUNT="00000000-0000-0000-0000-000000000000:00000000-0000-0000-0000-000000000000" }
}