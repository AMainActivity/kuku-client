package dev.messenger.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import okio.*
import okio.Path.Companion.toPath
import kotlin.uuid.Uuid

@Entity(tableName = "transfers", primaryKeys = ["accountId", "jobId"])
data class TransferEntity(
    val accountId: String, val jobId: String, val chatId: String,
    val direction: String, val filename: String, val mime: String, val size: Long,
    val sha256: String, val state: String = "QUEUED", val remoteId: String? = null,
    val progress: Long = 0, val etag: String? = null, val error: String? = null,
    @ColumnInfo(defaultValue = "'attachment'") val purpose:String = "attachment",
    @ColumnInfo(defaultValue = "NULL") val createId:String? = null,
    val completeId: String = Uuid.random().toString(), val cancelId: String = Uuid.random().toString(),
)
@Entity(tableName = "transfer_chat_links", primaryKeys = ["accountId", "chatId", "jobId"])
data class TransferChatLink(val accountId: String, val chatId: String, val jobId: String)

@Dao abstract class TransfersDao {
    @Upsert abstract suspend fun link(row: TransferChatLink)
    @Query("DELETE FROM transfer_chat_links WHERE accountId=:accountId")
    abstract suspend fun clearLinks(accountId: String)
    @Query("SELECT COUNT(*) FROM transfers WHERE accountId=:accountId AND state IN ('QUEUED','RUNNING','CANCELLING')")
    abstract fun observeWorkCount(accountId:String):Flow<Long>
    @Query("SELECT COUNT(*) FROM transfers WHERE accountId=:accountId AND state IN ('READY','CANCELLED')")
    abstract fun observeCompleted(accountId:String):Flow<Long>
    @Query("SELECT * FROM transfers WHERE accountId=:accountId")
    abstract suspend fun all(accountId:String):List<TransferEntity>
    @Query("DELETE FROM transfers WHERE accountId=:accountId AND jobId=:jobId")
    protected abstract suspend fun removeRow(accountId:String,jobId:String)
    @Query("DELETE FROM transfer_chat_links WHERE accountId=:accountId AND jobId=:jobId")
    protected abstract suspend fun removeLinks(accountId:String,jobId:String)
    @Transaction open suspend fun remove(accountId:String,jobId:String) {
        removeLinks(accountId,jobId)
        removeRow(accountId,jobId)
    }
    @Query("SELECT * FROM transfers WHERE accountId=:accountId AND jobId IN (:jobIds)")
    abstract fun observeJobs(accountId:String,jobIds:List<String>):Flow<List<TransferEntity>>
    @Upsert abstract suspend fun put(value: TransferEntity)
    @Query("SELECT * FROM transfers WHERE accountId=:accountId AND (chatId=:chatId OR jobId IN (SELECT jobId FROM transfer_chat_links WHERE accountId=:accountId AND chatId=:chatId)) ORDER BY jobId")
    abstract fun observe(accountId: String, chatId: String): Flow<List<TransferEntity>>
    @Query("SELECT * FROM transfers WHERE accountId=:accountId AND direction=:direction AND state IN ('QUEUED','RUNNING','CANCELLING') ORDER BY jobId LIMIT 1")
    abstract suspend fun next(accountId: String, direction: String): TransferEntity?
    @Query("SELECT * FROM transfers WHERE accountId=:accountId AND jobId=:jobId")
    abstract suspend fun get(accountId: String, jobId: String): TransferEntity?
    @Query("UPDATE transfers SET state=:state, error=NULL WHERE accountId=:accountId AND jobId=:jobId")
    abstract suspend fun state(accountId: String, jobId: String, state: String)
    @Query("UPDATE transfers SET remoteId=:remoteId WHERE accountId=:accountId AND jobId=:jobId")
    abstract suspend fun remote(accountId: String, jobId: String, remoteId: String)
    @Query("UPDATE transfers SET progress=:progress, etag=:etag WHERE accountId=:accountId AND jobId=:jobId")
    abstract suspend fun progress(accountId: String, jobId: String, progress: Long, etag: String?)
    @Query("UPDATE transfers SET state=:state, error=:error WHERE accountId=:accountId AND jobId=:jobId AND state NOT IN ('PAUSED','CANCELLING','CANCELLED')")
    abstract suspend fun outcome(accountId: String, jobId: String, state: String, error: String?)
    @Query("UPDATE transfers SET state='QUEUED',error=NULL,remoteId=NULL,progress=0,etag=NULL,createId=:createId,completeId=:completeId,cancelId=:cancelId WHERE accountId=:accountId AND jobId=:jobId AND direction='UPLOAD' AND state='FAILED' AND error IN ('UPLOAD_EXPIRED','UPLOAD_TERMINAL')")
    abstract suspend fun restartExpired(accountId:String,jobId:String,createId:String,completeId:String,cancelId:String):Int
    @Query("DELETE FROM transfers WHERE accountId=:accountId")
    abstract suspend fun clear(accountId: String)
}

/** Opaque UUID names only; never use a remote filename as a filesystem path. */
class ManagedFiles(private val root: String):dev.messenger.domain.FileStorage {
    private val fs = FileSystem.SYSTEM
    override fun path(accountId: String, jobId: String): String {
        val ids = accountId.split(':')
        require(ids.size == 2)
        ids.forEach { Uuid.parse(it) }; Uuid.parse(jobId)
        val directory = root.toPath() / ids[0] / ids[1]
        fs.createDirectories(directory)
        return (directory / jobId).toString()
    }
    override fun size(accountId: String, jobId: String) = fs.metadataOrNull(path(accountId, jobId).toPath())?.size ?: 0L
    override fun hash(accountId: String, jobId: String): String {
        val hashing = HashingSource.sha256(fs.source(path(accountId, jobId).toPath()))
        hashing.buffer().use { source -> val buffer = Buffer(); while(source.read(buffer, 65536) != -1L) buffer.clear() }
        return hashing.hash.hex()
    }
    override fun read(accountId: String, jobId: String, offset: Long, count: Long): ByteArray =
        fs.openReadOnly(path(accountId, jobId).toPath()).use { handle ->
            handle.source(offset).buffer().use { it.readByteArray(count) }
        }
    override fun write(accountId: String, jobId: String, offset: Long, bytes: ByteArray) {
        fs.openReadWrite(path(accountId, jobId).toPath()).use { handle ->
            if(offset == 0L) handle.resize(0)
            handle.write(offset, bytes, 0, bytes.size)
            handle.flush()
        }
    }
    override fun remove(accountId: String, jobId: String) = fs.delete(path(accountId, jobId).toPath(), mustExist = false)
    companion object { const val MAX_BYTES = 512L * 1024 * 1024 }
}

