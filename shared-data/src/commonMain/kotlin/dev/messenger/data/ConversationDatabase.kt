package dev.messenger.data

import androidx.room.*
import dev.messenger.data.wire.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*

@Entity(tableName = "chats", primaryKeys = ["accountId", "chatId"])
data class ChatEntity(val accountId: String, val chatId: String, val json: String,
    val pinned: Boolean, val pinRank: Int, val lastAt: String, val generation: String)
@Entity(tableName = "history", primaryKeys = ["accountId", "messageId"],
    indices = [Index(value = ["accountId", "chatId", "seq"], unique = true)])
data class MessageEntity(val accountId: String, val messageId: String, val chatId: String,
    val clientMessageId: String, val seq: Long, val version: Long, val json: String)
@Entity(tableName = "sync_state")
data class SyncEntity(@PrimaryKey val accountId: String, val cursor: Long, val generation: String?)
/** Per-chat fence survives revocation and rejoining; request stamps are captured before network IO. */
@Entity(tableName = "chat_cache_state", primaryKeys = ["accountId", "chatId"])
data class ChatCacheState(val accountId: String, val chatId: String, val revision: Long,
    val accessRevision: Long, val revoked: Boolean,
    @ColumnInfo(defaultValue = "NULL") val revokeReason:String? = null)
@Entity(tableName = "catalog_stage", primaryKeys = ["accountId", "chatId"])
data class CatalogEntity(val accountId: String, val chatId: String, val json: String)

@Entity(tableName="receipts",primaryKeys=["accountId","chatId","kind"])
data class ReceiptEntity(val accountId:String,val chatId:String,val kind:String,val throughSeq:Long,val operationId:String,val pending:Boolean)

@Entity(tableName="members_cache",primaryKeys=["accountId","chatId","userId"])
data class MemberCacheEntity(val accountId:String,val chatId:String,val userId:String,val json:String)

/** Changes only when a complete membership snapshot replaces the cache, or access is revoked. */
@Entity(tableName = "member_cache_revisions", primaryKeys = ["accountId", "chatId"])
data class MemberCacheRevisionEntity(val accountId: String, val chatId: String, val revision: Long)

@Entity(tableName="mutation_journal",primaryKeys=["accountId","requestKey"])
data class MutationEntity(val accountId:String,val requestKey:String,val operationId:String)

/** User versions and membership versions belong to different server records. */
@Entity(tableName = "user_profiles", primaryKeys = ["accountId", "userId"])
data class UserProfileEntity(val accountId: String, val userId: String, val json: String)

@Dao
abstract class ConversationsDao {
    @Query("SELECT COALESCE(MAX(revision),0) FROM chat_cache_state WHERE accountId=:accountId")
    abstract suspend fun cacheStamp(accountId: String): Long
    @Query("SELECT * FROM chat_cache_state WHERE accountId=:accountId AND chatId=:chatId")
    abstract suspend fun cacheState(accountId: String, chatId: String): ChatCacheState?
    @Query("SELECT * FROM chat_cache_state WHERE accountId=:accountId AND revoked=1")
    abstract fun observeRevoked(accountId:String):Flow<List<ChatCacheState>>
    @Query("DELETE FROM drafts WHERE accountId=:accountId AND chatId=:chatId")
    protected abstract suspend fun removeDraft(accountId:String,chatId:String)
    @Query("DELETE FROM receipts WHERE accountId=:accountId AND chatId=:chatId")
    protected abstract suspend fun removeReceipts(accountId:String,chatId:String)
    @Query("DELETE FROM catalog_stage WHERE accountId=:accountId AND chatId=:chatId")
    protected abstract suspend fun removeStagedChat(accountId:String,chatId:String)
    @Query("DELETE FROM transfer_chat_links WHERE accountId=:accountId AND chatId=:chatId")
    protected abstract suspend fun removeTransferLinks(accountId:String,chatId:String)
    @Query("""
        UPDATE transfers SET state='FAILED', error='CHAT_UNAVAILABLE'
        WHERE accountId=:accountId
          AND (chatId=:chatId
            OR jobId IN (SELECT jobId FROM transfer_chat_links WHERE accountId=:accountId AND chatId=:chatId)
            OR EXISTS (SELECT 1 FROM drafts d WHERE d.accountId=:accountId AND d.chatId=:chatId AND instr(d.filesJson,transfers.jobId)>0)
            OR EXISTS (SELECT 1 FROM outbox o WHERE o.accountId=:accountId AND o.chatId=:chatId AND instr(o.contentJson,transfers.jobId)>0))
          AND jobId NOT IN (SELECT jobId FROM transfer_chat_links WHERE accountId=:accountId AND chatId<>:chatId)
          AND NOT EXISTS (SELECT 1 FROM drafts d WHERE d.accountId=:accountId AND d.chatId<>:chatId AND instr(d.filesJson,transfers.jobId)>0)
          AND NOT EXISTS (
            SELECT 1 FROM outbox o WHERE o.accountId=:accountId AND o.chatId<>:chatId
              AND o.state IN ('WAITING_FOR_FILES','FAILED') AND instr(o.contentJson,transfers.jobId)>0
              AND NOT EXISTS (SELECT 1 FROM chat_cache_state c WHERE c.accountId=o.accountId AND c.chatId=o.chatId AND c.revoked=1)
          )
    """)
    protected abstract suspend fun stopChatTransfers(accountId:String,chatId:String)
    @Upsert protected abstract suspend fun putCacheState(row: ChatCacheState)
    @Query("DELETE FROM chat_cache_state WHERE accountId=:accountId")
    protected abstract suspend fun clearCacheStates(accountId: String)
    @Query("SELECT * FROM chats WHERE accountId=:accountId AND chatId=:chatId")
    abstract suspend fun cachedChat(accountId: String, chatId: String): ChatEntity?

    @Query("SELECT revision FROM member_cache_revisions WHERE accountId=:accountId AND chatId=:chatId")
    protected abstract suspend fun storedMemberRevision(accountId: String, chatId: String): Long?
    open suspend fun memberRevision(accountId: String, chatId: String): Long =
        storedMemberRevision(accountId, chatId) ?: 0
    @Upsert protected abstract suspend fun putMemberRevision(row: MemberCacheRevisionEntity)
    @Query("DELETE FROM member_cache_revisions WHERE accountId=:accountId")
    protected abstract suspend fun clearMemberRevisions(accountId: String)
    protected suspend fun advanceMemberRevision(accountId: String, chatId: String) {
        putMemberRevision(MemberCacheRevisionEntity(accountId, chatId, memberRevision(accountId, chatId) + 1))
    }
    @Query("SELECT * FROM user_profiles WHERE accountId=:accountId AND userId=:userId")
    abstract suspend fun userProfile(accountId: String, userId: String): UserProfileEntity?
    @Upsert protected abstract suspend fun putUserProfile(row: UserProfileEntity)
    @Query("DELETE FROM user_profiles WHERE accountId=:accountId")
    protected abstract suspend fun clearUserProfiles(accountId: String)
    @Query("SELECT * FROM account_profiles WHERE accountId=:accountId")
    abstract suspend fun ownProfile(accountId: String): AccountProfileEntity?
    @Upsert protected abstract suspend fun putOwnProfile(row: AccountProfileEntity)
    @Query("SELECT * FROM chats WHERE accountId=:accountId")
    abstract suspend fun cachedChats(accountId: String): List<ChatEntity>
    @Query("SELECT * FROM members_cache WHERE accountId=:accountId")
    abstract suspend fun cachedMembers(accountId: String): List<MemberCacheEntity>
    @Query("SELECT * FROM members_cache WHERE accountId=:accountId AND userId=:userId")
    protected abstract suspend fun userMemberships(accountId: String, userId: String): List<MemberCacheEntity>
    @Query("DELETE FROM members_cache WHERE accountId=:accountId AND chatId=:chatId")
    protected abstract suspend fun removeMembers(accountId: String, chatId: String)
    @Query("DELETE FROM members_cache WHERE accountId=:accountId AND chatId=:chatId AND userId NOT IN (:userIds)")
    protected abstract suspend fun removeAbsentMembers(accountId: String, chatId: String, userIds: List<String>)

    @Transaction
    open suspend fun cacheUser(accountId: String, incoming: User): User {
        val old = userProfile(accountId, incoming.userId)?.let { wireJson.decodeFromString<User>(it.json) }
        val user = if (old != null && old.version > incoming.version) old else incoming
        if (old == user) return user
        putUserProfile(UserProfileEntity(accountId, user.userId, wireJson.encodeToString(user)))
        for (row in cachedChats(accountId)) {
            val chat = wireJson.decodeFromString<Chat>(row.json)
            if (chat.peer?.userId == user.userId) {
                putChat(row.copy(json = wireJson.encodeToString(chat.withPeer(user))))
            }
        }
        putMembers(userMemberships(accountId, user.userId).map { row ->
            val member = wireJson.decodeFromString<MemberInfo>(row.json).withUser(user)
            row.copy(json = wireJson.encodeToString(member))
        })
        ownProfile(accountId)?.let { row ->
            val me = wireJson.decodeFromString<Me>(row.json)
            if (me.userId == user.userId && user.version >= me.version) {
                putOwnProfile(row.copy(json = wireJson.encodeToString(me.withUser(user))))
            }
        }
        return user
    }

    @Transaction
    open suspend fun cacheMe(accountId: String, incoming: Me) {
        val old = ownProfile(accountId)?.let { wireJson.decodeFromString<Me>(it.json) }
        val me = if (old != null && old.version > incoming.version) old else incoming
        val user = cacheUser(accountId, User(me.userId, me.displayName, me.kind, me.handle,
            me.description, me.avatarId, me.version, me.deleted))
        putOwnProfile(AccountProfileEntity(accountId, wireJson.encodeToString(me.withUser(user))))
    }

    @Transaction
    open suspend fun replaceMembers(accountId: String, chatId: String, items: List<MemberInfo>, profiles: List<User> = emptyList()) {
        profiles.forEach { cacheUser(accountId, it) }
        cacheMembers(items.map { MemberCacheEntity(accountId, chatId, it.userId, wireJson.encodeToString(it)) })
        removeAbsentMembers(accountId, chatId, items.map { it.userId })
        advanceMemberRevision(accountId, chatId)
    }

    /** Ignore only responses superseded by a complete snapshot or revocation of this chat. */
    @Transaction
    open suspend fun cacheMemberResponse(accountId: String, chatId: String, items: List<MemberInfo>,
        expectedRevision: Long, complete: Boolean, profiles: List<User> = emptyList()) {
        if (memberRevision(accountId, chatId) != expectedRevision) return
        profiles.forEach { cacheUser(accountId, it) }
        if (complete) replaceMembers(accountId, chatId, items)
        else cacheMembers(items.map { MemberCacheEntity(accountId, chatId, it.userId, wireJson.encodeToString(it)) })
    }
    @Query("SELECT * FROM mutation_journal WHERE accountId=:accountId AND requestKey=:requestKey")
    abstract suspend fun mutation(accountId:String,requestKey:String):MutationEntity?
    @Insert abstract suspend fun rememberMutation(value:MutationEntity)
    @Query("DELETE FROM mutation_journal WHERE accountId=:accountId AND requestKey=:requestKey AND operationId=:operationId")
    abstract suspend fun forgetMutation(accountId:String,requestKey:String,operationId:String)
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
                val merged = if (old == null) fresh else (if (old.version > fresh.version) old else fresh).copy(
                    deliveredSeq = maxOf(old.deliveredSeq, fresh.deliveredSeq),
                    readSeq = maxOf(old.readSeq, fresh.readSeq))
                val profile = userProfile(row.accountId, row.userId)?.let { wireJson.decodeFromString<User>(it.json) }
                row.copy(json = wireJson.encodeToString(if (profile == null) merged else merged.withUser(profile)))
            }
        }
        putMembers(merged)
    }
    @Query("SELECT * FROM members_cache WHERE accountId=:accountId AND chatId=:chatId ORDER BY userId")
    abstract fun observeMembers(accountId:String,chatId:String):Flow<List<MemberCacheEntity>>
    @Query("SELECT * FROM members_cache WHERE accountId=:accountId AND chatId=:chatId ORDER BY userId")
    abstract suspend fun members(accountId:String,chatId:String):List<MemberCacheEntity>
    @Query("SELECT * FROM members_cache WHERE accountId=:accountId AND chatId=:chatId AND userId=:userId")
    abstract suspend fun member(accountId:String, chatId:String, userId:String):MemberCacheEntity?
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
    open suspend fun installCatalog(accountId: String, watermark: Long, generation: String, requestStamp: Long? = null) {
        for (row in staged(accountId)) cacheChat(accountId, wireJson.decodeFromString<Chat>(row.json), generation, requestStamp, allowRestore = true)
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
    open suspend fun cacheChat(accountId: String, chat: Chat, generation: String? = null,
        requestStamp: Long? = null, allowRestore: Boolean = false): Boolean {
        val state = cacheState(accountId, chat.chatId)
        if (requestStamp != null && state != null && requestStamp < state.accessRevision) return false
        if (state?.revokeReason == "group_deleted" || (state?.revoked == true && !allowRestore)) return false
        chat.peer?.let { cacheUser(accountId, it) }
        val old = cachedChat(accountId, chat.chatId)?.let { wireJson.decodeFromString<Chat>(it.json) }
        val freshSnapshot = requestStamp == null || state == null || requestStamp >= state.revision
        var merged = if (old == null) chat else {
            val latest = listOfNotNull(old.lastMessage, chat.lastMessage)
                .maxWithOrNull(compareBy<Message> { it.seq }.thenBy { it.version })
            (if (old.version > chat.version) old else chat).copy(
                membership = if (old.membership.version > chat.membership.version) old.membership else chat.membership,
                preferences = if (old.preferences.version > chat.preferences.version) old.preferences else chat.preferences,
                unreadCount = if (freshSnapshot) chat.unreadCount else old.unreadCount,
                lastMessage = latest)
        }
        merged.lastMessage?.let { latest ->
            val cached = message(accountId, latest.messageId)?.let { wireJson.decodeFromString<Message>(it.json) }
            if (cached != null && cached.version > latest.version) merged = merged.copy(lastMessage = cached)
        }
        if ((merged.lastMessage?.seq ?: Long.MAX_VALUE) < merged.membership.joinedSeq) merged = merged.copy(lastMessage = null)
        merged.peer?.let { merged = merged.withPeer(cacheUser(accountId, it)) }
        pruneBeforeJoin(accountId, merged.chatId, merged.membership.joinedSeq)
        val stamp = generation ?: syncState(accountId)?.generation ?: ""
        putChat(ChatEntity(accountId, merged.chatId, wireJson.encodeToString(merged),
            merged.preferences.pinned, merged.preferences.pinRank, merged.lastMessage?.createdAt.orEmpty(), stamp))
        putCacheState(ChatCacheState(accountId, chat.chatId, cacheStamp(accountId) + 1,
            state?.accessRevision ?: 0, false))
        merged.lastMessage?.let { cacheMessages(accountId, listOf(it)) }
        return true
    }
    @Transaction
    open suspend fun cacheMessages(accountId: String, messages: List<Message>, requestStamp: Long? = null) {
        for (item in messages) {
            val state = cacheState(accountId, item.chatId)
            if (state?.revoked == true || (requestStamp != null && state != null && requestStamp < state.accessRevision)) continue
            val chat = cachedChat(accountId, item.chatId)?.let { wireJson.decodeFromString<Chat>(it.json) }
            if (chat != null && item.seq < chat.membership.joinedSeq) continue
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
    open suspend fun revoke(accountId: String, chatId: String, reason:String? = null) {
        val revision = cacheStamp(accountId) + 1
        val previousReason=cacheState(accountId,chatId)?.revokeReason
        val permanentReason=if(previousReason=="group_deleted") previousReason else reason ?: previousReason
        putCacheState(ChatCacheState(accountId, chatId, revision, revision, true, permanentReason))
        stopChatTransfers(accountId,chatId)
        removeDraft(accountId,chatId)
        removeReceipts(accountId,chatId)
        removeStagedChat(accountId,chatId)
        removeTransferLinks(accountId,chatId)
        removeChat(accountId, chatId)
        removeHistory(accountId, chatId)
        removeMembers(accountId, chatId)
        advanceMemberRevision(accountId, chatId)
        failChatPending(accountId, chatId)
    }
    @Transaction
    open suspend fun applyPage(accountId: String, expectedCursor: Long, page: SyncPage,
        chats: List<Chat>, messages: List<Message>, revoked: Set<String>,
        memberSnapshots: Map<String, List<MemberInfo>> = emptyMap(), me: Me? = null, profiles: List<User> = emptyList(), requestStamp: Long? = null) {
        val state = requireNotNull(syncState(accountId))
        require(state.cursor == expectedCursor)
        require(page.nextCursor >= expectedCursor)
        require(page.items.all { it.cursor > expectedCursor })
        require(page.items.zipWithNext().all { (a,b) -> a.cursor < b.cursor })
        require(page.items.lastOrNull()?.cursor?.let { it == page.nextCursor } ?: (page.nextCursor == expectedCursor))
        chats.filterNot { it.chatId in revoked }.forEach { cacheChat(accountId, it, requestStamp = requestStamp, allowRestore = true) }
        for (event in page.items.filter { it.type == "user.updated" }) {
            cacheUser(accountId, wireJson.decodeFromJsonElement<User>(event.data))
        }
        profiles.forEach { cacheUser(accountId, it) }
        me?.let { cacheMe(accountId, it) }
        for ((chatId, items) in memberSnapshots.filterKeys { it !in revoked }) {
            val cache = cacheState(accountId, chatId)
            if (cache?.revoked != true && (requestStamp == null || cache == null || requestStamp >= cache.accessRevision)) {
                replaceMembers(accountId, chatId, items)
            }
        }
        cacheMessages(accountId, messages.filterNot { it.chatId in revoked }, requestStamp)
        for (event in page.items.filter { it.type == "receipt.updated" }) {
            val update = wireJson.decodeFromJsonElement<ReceiptUpdate>(event.data)
            if (update.chatId in revoked || update.kind !in setOf("read", "delivered")) continue
            require(update.throughSeq >= 0)
            val row = member(accountId, update.chatId, update.userId) ?: continue
            val old = wireJson.decodeFromString<MemberInfo>(row.json)
            val next = old.copy(
                deliveredSeq = maxOf(old.deliveredSeq, update.throughSeq),
                readSeq = if (update.kind == "read") maxOf(old.readSeq, update.throughSeq) else old.readSeq,
            )
            putMembers(listOf(row.copy(json = wireJson.encodeToString(next))))
        }
        revoked.forEach { chatId ->
            val reason = page.items.lastOrNull { it.type == "chat.accessRevoked" && it.data["chatId"]?.jsonPrimitive?.contentOrNull == chatId }?.data?.get("reason")?.jsonPrimitive?.contentOrNull
            revoke(accountId,chatId,reason)
        }
        putState(state.copy(cursor = page.nextCursor))
    }

    @Query("DELETE FROM chats WHERE accountId=:accountId") protected abstract suspend fun clearChats(accountId: String)
    @Query("DELETE FROM history WHERE accountId=:accountId") protected abstract suspend fun clearHistory(accountId: String)
    @Query("DELETE FROM sync_state WHERE accountId=:accountId") protected abstract suspend fun clearSync(accountId: String)
    @Query("DELETE FROM drafts WHERE accountId=:accountId") protected abstract suspend fun clearDrafts(accountId: String)
    @Query("DELETE FROM outbox WHERE accountId=:accountId") protected abstract suspend fun clearOutbox(accountId: String)
    @Transaction open suspend fun clearAccount(accountId: String) {
        clearChats(accountId); clearHistory(accountId); clearSync(accountId); clearCacheStates(accountId)
        clearMutations(accountId)
        clearMembers(accountId)
        clearMemberRevisions(accountId)
        clearUserProfiles(accountId)
        clearReceipts(accountId)
        clearDrafts(accountId); clearOutbox(accountId); clearStage(accountId)
    }
}

private fun Chat.withPeer(user: User) = Chat(chatId, kind, title, description, avatarId, version,
    membership, lastMessage, unreadCount, user, preferences)

private fun MemberInfo.withUser(user: User) = copy(displayName = user.displayName, kind = user.kind, handle = user.handle)

private fun Me.withUser(user: User) = copy(displayName = user.displayName, kind = user.kind, handle = user.handle,
    description = user.description, avatarId = user.avatarId, version = user.version, deleted = user.deleted)
