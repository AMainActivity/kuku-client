package dev.messenger.data

import androidx.room.*
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "drafts", primaryKeys = ["accountId", "chatId"])
data class DraftEntity(val accountId: String, val chatId: String, val text: String, val revision: Long,
    @ColumnInfo(defaultValue = "NULL") val replyTo: String? = null,
    @ColumnInfo(defaultValue = "'[]'") val filesJson: String = "[]")

@Entity(tableName = "outbox", primaryKeys = ["accountId", "operationId"],
    indices = [Index(value = ["accountId", "state", "createdAtMillis"])])
data class OutboxEntity(
    val accountId: String,
    val operationId: String,
    val chatId: String,
    val text: String,
    val createdAtMillis: Long,
    val state: String,
    @ColumnInfo(defaultValue = "NULL") val replyTo: String? = null,
    @ColumnInfo(defaultValue = "NULL") val contentJson: String? = null,
)

@Dao
abstract class MessagesDao {
    @Query("SELECT * FROM drafts WHERE accountId=:accountId")
    abstract fun observeDrafts(accountId:String):Flow<List<DraftEntity>>
    @Query("SELECT * FROM outbox WHERE accountId=:accountId ORDER BY operationId")
    abstract fun observeOutbox(accountId:String):Flow<List<OutboxEntity>>
    @Query("SELECT * FROM drafts WHERE accountId = :accountId AND chatId = :chatId")
    abstract fun observeDraft(accountId: String, chatId: String): Flow<DraftEntity?>
    @Query("SELECT * FROM drafts WHERE accountId = :accountId AND chatId = :chatId")
    abstract suspend fun draft(accountId: String, chatId: String): DraftEntity?
    @Upsert protected abstract suspend fun putDraft(value: DraftEntity)

    @Transaction
    open suspend fun saveDraft(accountId: String, chatId: String, text: String): DraftEntity {
        val revision = (draft(accountId, chatId)?.revision ?: 0) + 1
        val old = draft(accountId, chatId)
        return DraftEntity(accountId, chatId, text, revision, old?.replyTo, old?.filesJson ?: "[]").also { putDraft(it) }
    }

    @Transaction open suspend fun saveExtras(accountId: String, chatId: String, replyTo: String?, filesJson: String) {
        val old = draft(accountId,chatId) ?: DraftEntity(accountId,chatId,"",0)
        putDraft(old.copy(replyTo=replyTo,filesJson=filesJson,revision=old.revision+1))
    }
    @Query("SELECT * FROM outbox WHERE accountId = :accountId AND chatId = :chatId ORDER BY createdAtMillis, operationId")
    abstract fun observePending(accountId: String, chatId: String): Flow<List<OutboxEntity>>
    @Query("SELECT * FROM outbox WHERE accountId = :accountId AND state = 'READY' ORDER BY createdAtMillis, operationId LIMIT :limit")
    abstract suspend fun ready(accountId: String, limit: Int): List<OutboxEntity>
    @Query("SELECT * FROM outbox WHERE accountId = :accountId AND operationId = :operationId")
    abstract suspend fun operation(accountId: String, operationId: String): OutboxEntity?
    @Query("SELECT * FROM outbox WHERE accountId=:accountId AND state='WAITING_FOR_FILES'")
    abstract suspend fun waiting(accountId: String): List<OutboxEntity>
    @Query("UPDATE outbox SET contentJson=:contentJson, state='READY' WHERE accountId=:accountId AND operationId=:operationId AND state='WAITING_FOR_FILES'")
    abstract suspend fun filesReady(accountId: String, operationId: String, contentJson: String)
    @Insert protected abstract suspend fun insertOperation(value: OutboxEntity)

    @Transaction
    open suspend fun enqueue(draft: DraftEntity, operation: OutboxEntity) {
        val existing = operation(operation.accountId, operation.operationId)
        if (existing != null) {
            require(existing == operation) { "Operation identity reused with a different payload" }
            return
        }
        require(draft(draft.accountId, draft.chatId) == draft) { "Draft changed before enqueue" }
        require(operation.accountId == draft.accountId && operation.chatId == draft.chatId)
        insertOperation(operation)
        // Keep the monotonic revision: deleting the row would permit ABA after a new draft.
        putDraft(draft.copy(text = "", revision = draft.revision + 1, replyTo = null, filesJson = "[]"))
    }
}

@Database(entities = [DraftEntity::class, OutboxEntity::class, ChatEntity::class, MessageEntity::class, SyncEntity::class, EventEntity::class, CatalogEntity::class, TransferEntity::class, ReceiptEntity::class, MemberCacheEntity::class, MutationEntity::class, ShareInboxEntity::class, AccountProfileEntity::class, UserProfileEntity::class, MemberCacheRevisionEntity::class], version = 13, exportSchema = true, autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7), AutoMigration(from = 7, to = 8), AutoMigration(from = 8, to = 9), AutoMigration(from = 9, to = 10), AutoMigration(from = 10, to = 11), AutoMigration(from = 11, to = 12), AutoMigration(from = 12, to = 13)])
@ConstructedBy(MessengerDatabaseConstructor::class)
abstract class MessengerDatabase : RoomDatabase() {
    abstract fun profiles(): AccountProfilesDao
    abstract fun shares(): ShareInboxDao
    abstract fun messages(): MessagesDao
    abstract fun transfers(): TransfersDao
    abstract fun conversations(): ConversationsDao
}
@Suppress("KotlinNoActualForExpect")
expect object MessengerDatabaseConstructor : RoomDatabaseConstructor<MessengerDatabase> {
    override fun initialize(): MessengerDatabase
}
fun buildDatabase(builder: RoomDatabase.Builder<MessengerDatabase>): MessengerDatabase =
    builder.setDriver(BundledSQLiteDriver()).setQueryCoroutineContext(Dispatchers.IO).build()
