package dev.messenger.data

import dev.messenger.data.session.*
import dev.messenger.data.wire.*
import dev.messenger.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import dev.messenger.data.network.ConnectionStatus
import dev.messenger.data.network.reportConnectionFailure
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.time.Clock
import kotlin.uuid.Uuid
import okio.ByteString.Companion.encodeUtf8

class ConversationRepository(
    private val database: MessengerDatabase, private val sessions: SessionCoordinator, private val owner: CoroutineScope,
) {
    var onAccountRemoved: (suspend (String) -> Unit)? = null
    val connection = sessions.state
    private val dao = database.conversations()
    // Protects only local draft enqueue and mutation identity reservation; no network waits.
    private val localWriteMutex = Mutex()
    private var acknowledgedChannel: RpcChannel? = null
    private var acknowledgedCursor: Long? = null
    private var worker: Job? = null
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val resyncGeneration=MutableStateFlow(0L)
    val historyGeneration=resyncGeneration.asStateFlow()
    private val mutableSyncing = MutableStateFlow(false)
    val syncing = mutableSyncing.asStateFlow()

    private val reconnectWait = ReconnectWait()
    fun networkUnavailable() { reconnectWait.networkUnavailable() }
    fun networkAvailable() { reconnectWait.networkAvailable(); syncRequests.value?.wake() }
    private val syncRequests = MutableStateFlow<SyncRequests?>(null)
    fun requestCatchUp() { syncRequests.value?.wake() }
    fun start() {
        if (worker != null) return
        val requests = SyncRequests()
        syncRequests.value = requests
        worker = owner.launch {
            sessions.state.map { it.account?.key }.distinctUntilChanged().collectLatest { key ->
                if (key == null) return@collectLatest
                reconnectWait.connected()
                while (isActive && sessions.state.value.account?.key == key) {
                    reconnectWait.attemptStarted()
                    var observed:RpcChannel?=null
                    try {
                        val connection = sessions.connection()
                        observed=connection
                        coroutineScope {
                            val outboxWake = Channel<Unit>(Channel.CONFLATED)
                            val receiptsWake = Channel<Unit>(Channel.CONFLATED)
                            launch { connection.highWatermark.filterNotNull().collect { requests.wake() } }
                            launch {
                                connection.status.first { it != ConnectionStatus.OPEN }
                                throw ConnectionLost()
                            }
                            launch { database.messages().observeOutbox(key).distinctUntilChanged().collect { outboxWake.trySend(Unit) } }
                            launch { dao.observePendingReceipts(key).distinctUntilChanged().collect { receiptsWake.trySend(Unit) } }
                            launch { database.transfers().observeCompleted(key).distinctUntilChanged().collect { outboxWake.trySend(Unit) } }
                            launch {
                                while (isActive) {
                                    outboxWake.receive()
                                    drainOutbox(key)
                                }
                            }
                            launch {
                                while (isActive) {
                                    receiptsWake.receive()
                                    drainReceipts(key)
                                }
                            }
                            requests.wake()
                            while (isActive) {
                                requests.runNext(key, JOURNAL_SAFETY_CHECK_MILLIS) { synchronizeJournal(key) }
                                mutableError.value = null
                                reconnectWait.connected()
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        reportConnectionFailure(failure)
                        if (failure !is RemoteFailure) observed?.let { sessions.disconnect(it) }
                        mutableError.value = safeError(failure)
                        val retryAfter = (failure as? RemoteFailure)?.details?.get("retryAfterSeconds")?.jsonPrimitive?.longOrNull
                        reconnectWait.afterFailure(retryAfter)
                    }
                }
            }
        }.also { job ->
            job.invokeOnCompletion {
                requests.close()
                syncRequests.compareAndSet(requests, null)
            }
        }
    }
    suspend fun stop() { worker?.cancelAndJoin(); worker = null }
    private fun requireAccount(key: String) {
        check(sessions.state.value.account?.key == key) { "Account changed" }
    }

    suspend fun synchronize(key: String = requireNotNull(sessions.state.value.account).key) = coroutineScope {
        requireAccount(key)
        val requests = syncRequests.value ?: throw ConnectionLost()
        val pending = async { requests.request(key) }
        val accountChanged = async { sessions.state.first { it.account?.key != key } }
        try {
            select<Unit> {
                pending.onAwait { }
                accountChanged.onAwait { throw CancellationException("Account changed") }
            }
        } finally {
            pending.cancel()
            accountChanged.cancel()
        }
    }

    private suspend fun synchronizeJournal(key: String) {
        requireAccount(key)
        mutableSyncing.value = true
        try {
            repairCachedProfiles(key)
            if (dao.syncState(key) == null) catalog(key)
            try { replay(key) }
            catch (error: RemoteFailure) {
                if (error.code != "RESYNC_REQUIRED") throw error
                catalog(key)
                repairCachedProfiles(key, force = true)
                replay(key)
                resyncGeneration.value += 1
            }
            dao.finishCatalog(key)
        } finally { mutableSyncing.value = false }
    }

    /** Old clients acknowledged profile events without applying them. Repair only unversioned cached users. */
    private suspend fun repairCachedProfiles(key: String, force: Boolean = false) {
        val members = dao.cachedMembers(key)
        val peers = dao.cachedChats(key).mapNotNull { wireJson.decodeFromString<Chat>(it.json).peer?.userId }
        val own = dao.ownProfile(key)?.let { wireJson.decodeFromString<Me>(it.json).userId }
        val missing = (members.map { it.userId } + peers + listOfNotNull(own)).distinct()
            .filter { force || dao.userProfile(key, it) == null }
        if (missing.isEmpty()) return
        // Reconcile old membership snapshots too, before recording repaired user versions.
        for (chatId in members.filter { it.userId in missing }.map { it.chatId }.distinct()) {
            try {
                val requestStamp = dao.cacheStamp(key)
                val chat = sessions.request(Commands.ChatsGet, ChatParams(chatId))
                val snapshot = if (chat.membership.status == "active") fetchMembers(key, chatId) else emptyList()
                val profiles = resolveMemberProfiles(key, snapshot)
                requireAccount(key)
                dao.replaceMembers(key, chatId, snapshot, profiles)
                dao.cacheChat(key, chat, requestStamp = requestStamp, allowRestore = true)
            } catch (failure: RemoteFailure) {
                if (failure.code != "CHAT_UNAVAILABLE") throw failure
                requireAccount(key)
                dao.revoke(key, chatId)
            }
        }
        for (userId in missing) {
            if (userId == key.substringAfter(':')) {
                val me = sessions.request(MoreCommands.UsersMe, EmptyParams())
                requireAccount(key)
                dao.cacheMe(key, me)
            } else {
                val user = sessions.request(MoreCommands.UsersGet, DirectParams(userId))
                requireAccount(key)
                dao.cacheUser(key, user)
            }
        }
    }

    private suspend fun catalog(key: String) {
        val requestStamp = dao.cacheStamp(key)
        val start = sessions.request(Commands.SyncStart, EmptyParams())
        dao.clearStage(key)
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = sessions.request(Commands.ChatsList, PageParams(cursor, 100))
            requireAccount(key)
            dao.stage(page.items.map { CatalogEntity(key, it.chatId, wireJson.encodeToString(it)) })
            cursor = page.nextCursor
            if (cursor != null && !seen.add(cursor)) throw ProtocolFailure("CATALOG_CURSOR_LOOP")
        } while (cursor != null)
        dao.installCatalog(key, start.cursor, Uuid.random().toString(), requestStamp)
    }
    private suspend fun replay(key: String) {
        do {
            val cursor = requireNotNull(dao.syncState(key)).cursor
            val requestStamp = dao.cacheStamp(key)
            val page = sessions.request(Commands.SyncPull, SyncParams(cursor))
            val changed = linkedSetOf<String>()
            val revoked = linkedSetOf<String>()
            val memberChats = linkedSetOf<String>()
            val messages = mutableListOf<Message>()
            for (event in page.items) {
                val chatId = event.data["chatId"]?.jsonPrimitive?.contentOrNull

                when {
                    event.type == "receipt.updated" -> {
                        val receipt = wireJson.decodeFromJsonElement<ReceiptUpdate>(event.data)
                        if (receipt.kind in setOf("read", "delivered")) {
                            if (dao.member(key, receipt.chatId, receipt.userId) == null) memberChats.add(receipt.chatId)
                            if (receipt.userId == key.substringAfter(':') && receipt.kind == "read") changed.add(receipt.chatId)
                        }
                    }
                    event.type == "chat.accessRevoked" -> chatId?.let { revoked.add(it) }
                    event.type == "message.created" || event.type == "message.updated" -> {
                        val message = wireJson.decodeFromJsonElement<Message>(event.data)
                        messages.add(message); changed.add(message.chatId)
                    }
                    chatId != null -> {
                        changed.add(chatId)
                        if (event.type in setOf("group.membershipChanged", "group.invited", "chat.created")) memberChats.add(chatId)
                    }
                }
            }
            val chats = mutableListOf<Chat>()
            for (chatId in changed - revoked) {
                try { chats.add(sessions.request(Commands.ChatsGet, ChatParams(chatId))) }
                catch (error: RemoteFailure) {
                    if (error.code == "CHAT_UNAVAILABLE") revoked.add(chatId) else throw error
                }
            }
            requireAccount(key)
            val snapshots = mutableMapOf<String, List<MemberInfo>>()
            for (chatId in memberChats - revoked) {
                // Invitees may see the group but cannot list its members until accepting.
                if (chats.firstOrNull { it.chatId == chatId }?.membership?.status == "invited") {
                    snapshots[chatId] = emptyList()
                    continue
                }
                try { snapshots[chatId] = fetchMembers(key, chatId) }
                catch (failure: RemoteFailure) {
                    if (failure.code == "CHAT_UNAVAILABLE") revoked.add(chatId) else throw failure
                }
            }
            val profiles = resolveMemberProfiles(key, snapshots.values.flatten())
            val ownChanged = page.items.any { it.type == "user.updated" &&
                it.data["userId"]?.jsonPrimitive?.contentOrNull == key.substringAfter(':') }
            val me = if (ownChanged) sessions.request(MoreCommands.UsersMe, EmptyParams()) else null
            requireAccount(key)
            dao.applyPage(key, cursor, page, chats, messages, revoked, snapshots, me, profiles, requestStamp)
            // ACK is deliberately after the completed Room transaction, including unknown events.
            val channel = sessions.connection()
            if (acknowledgedChannel !== channel || acknowledgedCursor != page.nextCursor) {
                sessions.request(Commands.SyncAck, AckParams(page.nextCursor))
                acknowledgedChannel = channel
                acknowledgedCursor = page.nextCursor
            }
            if (page.hasMore && page.nextCursor <= cursor) throw ProtocolFailure("SYNC_CURSOR_LOOP")
        } while (page.hasMore)
    }

    private suspend fun drainReceipts(key: String) {
        requireAccount(key)
        for(receipt in dao.pendingReceipts(key)) {
            try {
                sessions.request(MoreCommands.ReceiptsSet,ReceiptParams(receipt.chatId,receipt.kind,receipt.throughSeq),Uuid.parse(receipt.operationId))
                dao.acknowledgeReceipt(key,receipt.operationId)
            } catch(failure: RemoteFailure) {
                if(failure.retryable) throw failure
                dao.acknowledgeReceipt(key,receipt.operationId)
            }
        }
    }
    private suspend fun drainOutbox(key: String) {
        requireAccount(key)
        for (pending in database.messages().waiting(key)) {
            val content = wireJson.decodeFromString<PlainContent>(requireNotNull(pending.contentJson))
            val files = content.attachmentIds.map { database.transfers().get(key,it) }
            if (files.any { it == null || it.state == "CANCELLED" }) dao.pendingState(key,pending.operationId,"FAILED")
            else if(files.all { it?.state == "READY" }) {
                val resolved = PlainContent(content.text, content.textEntities, files.map { requireNotNull(it?.remoteId) })
                database.messages().filesReady(key,pending.operationId,wireJson.encodeToString(resolved))
            }
        }
        for (pending in database.messages().ready(key, 20)) {
            try {
                if(dao.cacheState(key,pending.chatId)?.revoked==true) continue
                val requestStamp = dao.cacheStamp(key)
                val message = sessions.request(Commands.MessagesSend,
                    SendParams(pending.chatId, pending.operationId, pending.contentJson?.let { wireJson.decodeFromString<PlainContent>(it) } ?: PlainContent(pending.text), pending.replyTo),
                    Uuid.parse(pending.operationId))
                requireAccount(key)
                dao.cacheMessages(key, listOf(message), requestStamp)
                // The durable journal updates the chat summary; a read cooldown must not block the next send.
            } catch (error: RemoteFailure) {
                if (error.retryable || error.code == "UNAUTHENTICATED") throw error
                dao.pendingState(key, pending.operationId, "FAILED")
                mutableError.value = error.code
            }
        }
    }

    fun members(key:String,chatId:String):Flow<List<MemberInfo>> = dao.observeMembers(key,chatId).map { rows -> rows.map { wireJson.decodeFromString<MemberInfo>(it.json) } }
    suspend fun initialUnreadCursor(key:String,chatId:String):Long? {
        val chat=chat(key,chatId).first() ?: return null
        if(chat.unreadCount<=0) return null
        val own=dao.members(key,chatId).map {wireJson.decodeFromString<MemberInfo>(it.json)}.firstOrNull {it.userId==key.substringAfter(':')}
        return maxOf(own?.readSeq ?: 0,dao.receipt(key,chatId,"read")?.throughSeq ?: 0,chat.membership.joinedSeq-1)
    }
    suspend fun loadMembers(key:String,chatId:String) {
        requireAccount(key)
        val expectedRevision = dao.memberRevision(key, chatId)
        val members = fetchMembers(key, chatId)
        val profiles = resolveMemberProfiles(key, members)
        requireAccount(key)
        dao.cacheMemberResponse(key, chatId, members, expectedRevision, complete = true, profiles = profiles)
    }
    /**
     * MemberInfo has a membership version, but no profile version. A mismatching name may
     * be either a stale list response or a rename made while we shared no active chat.
     * Resolve that ambiguity with a versioned users.get; never guess from membership.version.
     */
    private suspend fun resolveMemberProfiles(key: String, members: List<MemberInfo>): List<User> {
        val profiles = mutableListOf<User>()
        for ((userId, snapshots) in members.groupBy { it.userId }) {
            val cached = dao.userProfile(key, userId)?.let { wireJson.decodeFromString<User>(it.json) } ?: continue
            if (snapshots.any { cached.displayName != it.displayName || cached.kind != it.kind || cached.handle != it.handle }) {
                profiles += sessions.request(MoreCommands.UsersGet, DirectParams(userId))
                requireAccount(key)
            }
        }
        return profiles
    }

    private suspend fun fetchMembers(key: String, chatId: String): List<MemberInfo> {
        val members = mutableListOf<MemberInfo>()
        var cursor: String? = null
        val seen = mutableSetOf<String>()
        do {
            val page = sessions.request(MoreCommands.MembersList, ChatPageParams(chatId, cursor, 100))
            requireAccount(key)
            members += page.items
            cursor = page.nextCursor
            if (cursor != null && !seen.add(cursor)) throw ProtocolFailure("MEMBER_CURSOR_LOOP")
        } while (cursor != null)
        return members
    }
    fun revokedChats(key:String):Flow<Map<String,String?>> = dao.observeRevoked(key).map { rows -> rows.associate {it.chatId to it.revokeReason} }
    fun chats(key: String): Flow<List<Chat>> = dao.observeChats(key).map { rows -> rows.map { wireJson.decodeFromString<Chat>(it.json) } }
    fun chat(key: String, chatId: String): Flow<Chat?> = dao.observeChat(key, chatId).map { it?.let { wireJson.decodeFromString<Chat>(it.json) } }
    suspend fun newDirect(userId: String): Chat =
        execute(Commands.ChatsDirect, DirectParams(userId), Uuid.random())
    suspend fun send(key: String, chatId: String, text: String, replyTo: String? = null, files: List<String> = emptyList()) = localWriteMutex.withLock {
        requireAccount(key)
        validateTextSize(text)
        require((text.isNotBlank() || files.isNotEmpty()) && files.size <= 10)
        val local = RoomLocalMessages(database.messages(), AccountId(Uuid.parse(key.substringBefore(':')), Uuid.parse(key.substringAfter(':'))))
        local.saveDraft(ChatId(Uuid.parse(chatId)), text)
        database.messages().enqueue(requireNotNull(database.messages().draft(key,chatId)), OutboxEntity(key, Uuid.random().toString(), chatId, text, Clock.System.now().toEpochMilliseconds(), if(files.isEmpty()) "READY" else "WAITING_FOR_FILES", replyTo, wireJson.encodeToString(PlainContent(text, textEntities=mentions(text,dao.members(key,chatId).map { wireJson.decodeFromString<MemberInfo>(it.json) }), attachmentIds=files))))
    }
    fun validateTextSize(text: String) {
        if (text.encodeToByteArray().size.toLong() > sessions.state.value.maxTextBytes) throw ApplicationFailure("MESSAGE_TOO_LARGE")
    }
    suspend fun read(key:String,chatId:String,seq:Long) { requireAccount(key); dao.advanceReceipt(key,chatId,"read",seq) }
    suspend fun retry(key: String, operationId: String) {
        requireAccount(key)
        val row = database.messages().operation(key,operationId) ?: return
        if(dao.cacheState(key,row.chatId)?.revoked==true) throw ApplicationFailure("CHAT_UNAVAILABLE")
        val content = row.contentJson?.let { wireJson.decodeFromString<PlainContent>(it) }
        val waiting = content?.attachmentIds?.any { database.transfers().get(key,it) != null } == true
        dao.pendingState(key, operationId, if(waiting) "WAITING_FOR_FILES" else "READY")
    }
    fun observeHistoryWindow(key:String,chatId:String,lower:Long,upper:Long?,limit:Int):Flow<List<Message>> =
        dao.observeWindow(key,chatId,lower,upper,limit).map { rows -> rows.map {wireJson.decodeFromString<Message>(it.json)} }
    suspend fun historyPage(key:String,chatId:String,direction:String,anchor:Long?,limit:Int=50):History {
        requireAccount(key)
        if(!sessions.state.value.online) return cachedHistoryPage(key,chatId,direction,anchor,limit)
        val requestStamp = dao.cacheStamp(key)
        val page=sessions.request(Commands.MessagesHistory,HistoryParams(chatId,direction,anchor,limit))
        requireAccount(key);dao.cacheMessages(key,page.items,requestStamp)
        return page
    }
    suspend fun cachedHistoryPage(key:String,chatId:String,direction:String,anchor:Long?,limit:Int=50):History {
        requireAccount(key)
        val rows=if(direction=="around" && anchor!=null) (dao.cachedBefore(key,chatId,anchor,limit/2).reversed()+dao.cachedAfter(key,chatId,anchor-1,limit/2))
            else if(direction=="after") dao.cachedAfter(key,chatId,anchor ?: 0,limit)
            else dao.cachedBefore(key,chatId,anchor ?: Long.MAX_VALUE,limit).reversed()
        val items=rows.map {wireJson.decodeFromString<Message>(it.json)}
        return History(items,items.firstOrNull()?.seq,items.lastOrNull()?.seq,true,true)
    }
    fun replyTargets(key:String,chatId:String,ids:List<String>):Flow<Map<String,Message>> = channelFlow {
        if(ids.isEmpty()) {send(emptyMap());return@channelFlow}
        launch {
            dao.observeReplyTargets(key,chatId,ids).collect {rows ->
                send(rows.associate {row -> row.messageId to wireJson.decodeFromString<Message>(row.json)})
            }
        }
        // Populate missing quotes without moving/replacing the history window.
        launch {
            for(id in ids) try {messageSequence(key,chatId,id)}
            catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) { /* Unavailable/offline quote keeps its explicit placeholder. */ }
        }
    }
    suspend fun messageSequence(key:String,chatId:String,messageId:String):Long {
        val cached=dao.message(key,messageId)
        if(cached!=null) {require(cached.chatId==chatId);return cached.seq}
        val requestStamp = dao.cacheStamp(key)
        val message=sessions.request(MoreCommands.MessagesGet,MessageParams(messageId))
        require(message.chatId==chatId);requireAccount(key);dao.cacheMessages(key,listOf(message),requestStamp)
        return message.seq
    }
    fun profile(accountId:String):Flow<Me?> = database.profiles().observe(accountId).map { it?.let { row -> wireJson.decodeFromString<Me>(row.json) } }
    suspend fun logout(): Boolean {
        worker?.cancelAndJoin(); worker = null
        val key = sessions.state.value.account?.key
        return try {
            sessions.logout().also { if (key != null) { onAccountRemoved?.invoke(key); dao.clearAccount(key); database.profiles().clear(key) } }
        } finally { start() }
    }

    suspend fun <P, R> execute(command: Command<P, R>, params: P, operationId: Uuid? = null): R {
        val key = requireNotNull(sessions.state.value.account).key
        if (params is EditParams) validateTextSize(params.text)
        // Persist only a digest and operation identity. OTP/TOTP commands stay ephemeral.
        val durable = command.mutation && !command.name.startsWith("auth.") && !command.name.startsWith("users.email.")
        val requestKey = if(durable) (command.name+":"+wireJson.encodeToString(command.params,params)).encodeUtf8().sha256().hex() else null
        val stableId = if(requestKey!=null) localWriteMutex.withLock {
            val existing=dao.mutation(key,requestKey)
            if(existing!=null) Uuid.parse(existing.operationId)
            else (operationId ?: Uuid.random()).also { dao.rememberMutation(MutationEntity(key,requestKey,it.toString())) }
        } else operationId
        val memberRequest = params as? ChatPageParams
        val cachesMembers = command.name == "groups.members.list" || command.name == "receipts.list"
        val expectedRevision = if (cachesMembers && memberRequest != null) dao.memberRevision(key, memberRequest.chatId) else 0
        val requestStamp = dao.cacheStamp(key)
        val result = sessions.request(command, params, stableId)
        requireAccount(key)
        when (result) {
            is GroupDeleted -> if(result.deleted) dao.revoke(key,result.chatId,"group_deleted")
            is Me -> dao.cacheMe(key, result)
            is User -> dao.cacheUser(key, result)
            is Chat -> dao.cacheChat(key, result, requestStamp = requestStamp, allowRestore = true)
            is Message -> dao.cacheMessages(key, listOf(result), requestStamp)
            is Items<*> -> dao.cacheMessages(key, result.items.filterIsInstance<Message>(), requestStamp)
            is Page<*> -> if (cachesMembers && memberRequest != null) {
                val members = result.items.filterIsInstance<MemberInfo>()
                val profiles = resolveMemberProfiles(key, members)
                requireAccount(key)
                dao.cacheMemberResponse(key, memberRequest.chatId, members, expectedRevision,
                    complete = memberRequest.cursor == null && result.nextCursor == null && memberRequest.query.isEmpty(),
                    profiles = profiles)
            }
        }
        // A late response may only acknowledge its own journal entry, never a newer operation.
        if(requestKey!=null) dao.forgetMutation(key,requestKey,requireNotNull(stableId).toString())
        return result
    }

    companion object {
        fun mentions(text:String,members:List<MemberInfo>):List<Mention> {
            val candidates=members.filter { it.status=="active" && !it.handle.isNullOrEmpty() }.associateBy { it.handle }
            return Regex("(?<![A-Za-z0-9_])@([a-z][a-z0-9_]{2,31})(?![A-Za-z0-9_])").findAll(text).mapNotNull { match ->
                candidates[match.groupValues[1]]?.let { Mention(it.userId,text.substring(0,match.range.first).codePointCount(),match.value.codePointCount()) }
            }.take(50).toList()
        }
        const val JOURNAL_SAFETY_CHECK_MILLIS = 60_000L
        fun safeError(error: Exception): String = when (error) {
            is ConnectionLost -> "CONNECTION_LOST"
            is RemoteFailure -> error.code
            is ProtocolFailure -> error.code
            is RequestTimeout -> "TIMEOUT"
            else -> "CONNECTION_ERROR"
        }
    }
}
