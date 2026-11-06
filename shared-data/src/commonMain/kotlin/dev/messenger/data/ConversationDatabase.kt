package dev.messenger.data

import androidx.room.*
import dev.messenger.data.wire.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chats", primaryKeys = ["accountId", "chatId"])
data class ChatEntity(val accountId: String, val chatId: String, val json: String,
    val pinned: Boolean, val pinRank: Int, val lastAt: String, val generation: String)
@Entity(tableName = "history", primaryKeys = ["accountId", "messageId"],
    indices = [Index(value = ["accountId", "chatId", "seq"], unique = true)])
data class MessageEntity(val accountId: String, val messageId: String, val chatId: String,
    val clientMessageId: String, val seq: Long, val version: Long, val json: String)
@Entity(tableName = "sync_state")
data class SyncEntity(@PrimaryKey val accountId: String, val cursor: Long, val generation: String?)
@Entity(tableName = "sync_events", primaryKeys = ["accountId", "cursor"])
data class EventEntity(val accountId: String, val cursor: Long, val type: String, val json: String)
@Entity(tableName = "catalog_stage", primaryKeys = ["accountId", "chatId"])
data class CatalogEntity(val accountId: String, val chatId: String, val json: String)

@Entity(tableName="receipts",primaryKeys=["accountId","chatId","kind"])
data class ReceiptEntity(val accountId:String,val chatId:String,val kind:String,val throughSeq:Long,val operationId:String,val pending:Boolean)

@Entity(tableName="members_cache",primaryKeys=["accountId","chatId","userId"])
data class MemberCacheEntity(val accountId:String,val chatId:String,val userId:String,val json:String)

@Entity(tableName="mutation_journal",primaryKeys=["accountId","requestKey"])
data class MutationEntity(val accountId:String,val requestKey:String,val operationId:String)

@Dao
abstract class ConversationsDao {
    @Query("SELECT * FROM mutation_journal WHERE accountId=:accountId AND requestKey=:requestKey")
    abstract suspend fun mutation(accountId:String,requestKey:String):MutationEntity?
    @Insert abstract suspend fun rememberMutation(value:MutationEntity)
    @Query("DELETE FROM mutation_journal WHERE accountId=:accountId AND requestKey=:requestKey")
    abstract suspend fun forgetMutation(accountId:String,requestKey:String)
    @Query("DELETE FROM mutation_journal WHERE accountId=:accountId")
    protected abstract suspend fun clearMutations(accountId:String)
    @Query("SELECT * FROM receipts WHERE accountId=:accountId AND pending=1 ORDER BY operationId")
    abstract fun observePendingReceipts(accountId:String):Flow<List<ReceiptEntity>>
    @Upsert protected abstract suspend fun putMembers(rows:List<MemberCacheEntity>)
    @Transaction open suspend fun cacheMembers(rows:List<MemberCacheEntity>) {
        // A history refresh and sync replay may complete in either order.
        // Receipt cursors are monotonic even if the older response arrives last.
        val merged=rows.groupBy {it.accountId to it.chatId}.flatMap { (key,incoming) ->
            val existing=members(key.first,key.second).associateBy {it.userId}
            incoming.map {row ->
                val old=existing[row.userId]?.let {wireJson.decodeFromString<MemberInfo>(it.json)}
                val fresh=wireJson.decodeFromString<MemberInfo>(row.json)
                if(old==null) row else {
                    val info=(if(old.version>fresh.version) old else fresh).copy(
                        deliveredSeq=maxOf(old.deliveredSeq,fresh.deliveredSeq),
                        readSeq=maxOf(old.readSeq,fresh.readSeq))
                    row.copy(json=wireJson.encodeToString(info))
                }
            }
        }
        putMembers(merged)
    }
    @Query("SELECT * FROM members_cache WHERE accountId=:accountId AND chatId=:chatId ORDER BY userId")
    abstract fun observeMembers(accountId:String,chatId:String):Flow<List<MemberCacheEntity>>
    @Query("SELECT * FROM members_cache WHERE accountId=:accountId AND chatId=:chatId ORDER BY userId")
    abstract suspend fun members(accountId:String,chatId:String):List<MemberCacheEntity>
    @Query("DELETE FROM members_cache WHERE accountId=:accountId")
    protected abstract suspend fun clearMembers(accountId:String)
    @Query("SELECT * FROM receipts WHERE accountId=:accountId AND chatId=:chatId AND kind=:kind")
    abstract suspend fun receipt(accountId:String,chatId:String,kind:String):ReceiptEntity?
    @Query("SELECT * FROM receipts WHERE accountId=:accountId AND pending=1")
    abstract suspend fun pendingReceipts(accountId:String):List<ReceiptEntity>
    @Upsert protected abstract suspend fun putReceipt(row:ReceiptEntity)
    @Query("UPDATE receipts SET pending=0 WHERE accountId=:accountId AND operationId=:operationId")
    abstract suspend fun acknowledgeReceipt(accountId:String,operationId:String)
    @Query("DELETE FROM receipts WHERE accountId=:accountId")
    protected abstract suspend fun clearReceipts(accountId:String)
    @Transaction open suspend fun advanceReceipt(accountId:String,chatId:String,kind:String,seq:Long) {
        val old=receipt(accountId,chatId,kind)
        if(old==null || seq>old.throughSeq) putReceipt(ReceiptEntity(accountId,chatId,kind,seq,kotlin.uuid.Uuid.random().toString(),true))
    }
    @Query("SELECT * FROM chats WHERE accountId=:accountId ORDER BY pinned DESC, CASE WHEN pinned THEN pinRank ELSE 0 END, lastAt DESC, chatId")
    abstract fun observeChats(accountId: String): Flow<List<ChatEntity>>
    @Query("SELECT * FROM chats WHERE accountId=:accountId AND chatId=:chatId")
    abstract fun observeChat(accountId: String, chatId: String): Flow<ChatEntity?>
    @Query("SELECT * FROM history WHERE accountId=:accountId AND chatId=:chatId ORDER BY seq")
    abstract fun observeHistory(accountId: String, chatId: String): Flow<List<MessageEntity>>
    @Query("SELECT * FROM (SELECT * FROM history WHERE accountId=:accountId AND chatId=:chatId AND seq>=:lower AND (:upper IS NULL OR seq<=:upper) ORDER BY seq DESC LIMIT :limit) ORDER BY seq")
    abstract fun observeWindow(accountId:String,chatId:String,lower:Long,upper:Long?,limit:Int):Flow<List<MessageEntity>>
    @Query("SELECT * FROM history WHERE accountId=:accountId AND chatId=:chatId AND seq<:anchor ORDER BY seq DESC LIMIT :limit")
    abstract suspend fun cachedBefore(accountId:String,chatId:String,anchor:Long,limit:Int):List<MessageEntity>
    @Query("SELECT * FROM history WHERE accountId=:accountId AND chatId=:chatId AND seq>:anchor ORDER BY seq LIMIT :limit")
    abstract suspend fun cachedAfter(accountId:String,chatId:String,anchor:Long,limit:Int):List<MessageEntity>
    @Query("SELECT * FROM sync_state WHERE accountId=:accountId")
    abstract suspend fun syncState(accountId: String): SyncEntity?
    @Query("SELECT * FROM history WHERE accountId=:accountId AND messageId=:messageId")
    abstract suspend fun message(accountId: String, messageId: String): MessageEntity?
    @Query("SELECT * FROM history WHERE accountId=:accountId AND chatId=:chatId AND messageId IN (:ids)")
    abstract fun observeReplyTargets(accountId:String,chatId:String,ids:List<String>):Flow<List<MessageEntity>>
    @Query("SELECT MIN(seq) FROM history WHERE accountId=:accountId AND chatId=:chatId")
    abstract suspend fun oldest(accountId: String, chatId: String): Long?
    @Query("SELECT MAX(seq) FROM history WHERE accountId=:accountId AND chatId=:chatId")
    abstract suspend fun newest(accountId:String,chatId:String):Long?
    @Query("DELETE FROM history WHERE accountId=:accountId AND chatId=:chatId AND seq < :joinedSeq")
    protected abstract suspend fun pruneBeforeJoin(accountId:String,chatId:String,joinedSeq:Long)
    @Upsert protected abstract suspend fun putChat(row: ChatEntity)
    @Upsert protected abstract suspend fun putMessage(row: MessageEntity)
    @Upsert protected abstract suspend fun putState(row: SyncEntity)
    @Upsert protected abstract suspend fun putEvents(rows: List<EventEntity>)
    @Upsert abstract suspend fun stage(rows: List<CatalogEntity>)
    @Query("SELECT * FROM catalog_stage WHERE accountId=:accountId")
    protected abstract suspend fun staged(accountId: String): List<CatalogEntity>
    @Query("DELETE FROM catalog_stage WHERE accountId=:accountId")
    abstract suspend fun clearStage(accountId: String)
    @Query("DELETE FROM outbox WHERE accountId=:accountId AND chatId=:chatId AND operationId=:clientMessageId")
    protected abstract suspend fun reconcile(accountId: String, chatId: String, clientMessageId: String)
    @Query("UPDATE outbox SET state=:state WHERE accountId=:accountId AND operationId=:operationId")
    abstract suspend fun pendingState(accountId: String, operationId: String, state: String)
    @Query("DELETE FROM chats WHERE accountId=:accountId AND chatId=:chatId")
    protected abstract suspend fun removeChat(accountId: String, chatId: String)
    @Query("DELETE FROM history WHERE accountId=:accountId AND chatId=:chatId")
    protected abstract suspend fun removeHistory(accountId: String, chatId: String)
    @Query("UPDATE outbox SET state='FAILED' WHERE accountId=:accountId AND chatId=:chatId")
    protected abstract suspend fun failChatPending(accountId: String, chatId: String)
    @Query("SELECT chatId FROM chats WHERE accountId=:accountId AND generation<>:generation")
    protected abstract suspend fun staleChats(accountId: String, generation: String): List<String>

    @Transaction
    open suspend fun installCatalog(accountId: String, watermark: Long, generation: String) {
        for (row in staged(accountId)) cacheChat(accountId, wireJson.decodeFromString<Chat>(row.json), generation)
        putState(SyncEntity(accountId, watermark, generation))
        clearStage(accountId)
    }
    @Transaction
    open suspend fun finishCatalog(accountId: String) {
        val state = syncState(accountId) ?: return
        val generation = state.generation ?: return
        staleChats(accountId, generation).forEach { revoke(accountId, it) }
        putState(state.copy(generation = null))
    }

    @Transaction
    open suspend fun cacheChat(accountId: String, chat: Chat, generation: String? = null) {
        pruneBeforeJoin(accountId,chat.chatId,chat.membership.joinedSeq)
        val stamp = generation ?: syncState(accountId)?.generation ?: ""
        putChat(ChatEntity(accountId, chat.chatId, wireJson.encodeToString(chat),
            chat.preferences.pinned, chat.preferences.pinRank, chat.lastMessage?.createdAt.orEmpty(), stamp))
        chat.lastMessage?.let { cacheMessages(accountId, listOf(it)) }
    }
    @Transaction
    open suspend fun cacheMessages(accountId: String, messages: List<Message>) {
        for (item in messages) {
            val old = message(accountId, item.messageId)
            if (old == null || item.version >= old.version) {
                putMessage(MessageEntity(accountId, item.messageId, item.chatId, item.clientMessageId,
                    item.seq, item.version, wireJson.encodeToString(item)))
            }
            advanceReceipt(accountId,item.chatId,"delivered",item.seq)
            if (item.senderId == accountId.substringAfter(':')) reconcile(accountId, item.chatId, item.clientMessageId)
        }
    }
    @Transaction
    open suspend fun revoke(accountId: String, chatId: String) {
        removeChat(accountId, chatId)
        removeHistory(accountId, chatId)
        failChatPending(accountId, chatId)
    }
    @Transaction
    open suspend fun applyPage(accountId: String, expectedCursor: Long, page: SyncPage,
        chats: List<Chat>, messages: List<Message>, revoked: Set<String>) {
        val state = requireNotNull(syncState(accountId))
        require(state.cursor == expectedCursor)
        require(page.nextCursor >= expectedCursor)
        require(page.items.all { it.cursor > expectedCursor })
        require(page.items.zipWithNext().all { (a,b) -> a.cursor < b.cursor })
        require(page.items.lastOrNull()?.cursor?.let { it == page.nextCursor } ?: (page.nextCursor == expectedCursor))
        chats.filterNot { it.chatId in revoked }.forEach { cacheChat(accountId, it) }
        cacheMessages(accountId, messages.filterNot { it.chatId in revoked })
        messages.filterNot { it.chatId in revoked }.groupBy { it.chatId }.forEach { (chatId,items) -> advanceReceipt(accountId,chatId,"delivered",items.maxOf { it.seq }) }
        revoked.forEach { revoke(accountId, it) }
        putEvents(page.items.map { EventEntity(accountId, it.cursor, it.type, wireJson.encodeToString(it)) })
        putState(state.copy(cursor = page.nextCursor))
    }

    @Query("DELETE FROM chats WHERE accountId=:accountId") protected abstract suspend fun clearChats(accountId: String)
    @Query("DELETE FROM history WHERE accountId=:accountId") protected abstract suspend fun clearHistory(accountId: String)
    @Query("DELETE FROM sync_state WHERE accountId=:accountId") protected abstract suspend fun clearSync(accountId: String)
    @Query("DELETE FROM sync_events WHERE accountId=:accountId") protected abstract suspend fun clearEvents(accountId: String)
    @Query("DELETE FROM drafts WHERE accountId=:accountId") protected abstract suspend fun clearDrafts(accountId: String)
    @Query("DELETE FROM outbox WHERE accountId=:accountId") protected abstract suspend fun clearOutbox(accountId: String)
    @Transaction open suspend fun clearAccount(accountId: String) {
        clearChats(accountId); clearHistory(accountId); clearSync(accountId); clearEvents(accountId)
        clearMutations(accountId)
        clearMembers(accountId)
        clearReceipts(accountId)
        clearDrafts(accountId); clearOutbox(accountId); clearStage(accountId)
    }
}
