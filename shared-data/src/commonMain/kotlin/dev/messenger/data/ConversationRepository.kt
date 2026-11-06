package dev.messenger.data

import dev.messenger.data.session.*
import dev.messenger.data.wire.*
import dev.messenger.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
    private val lock = Mutex()
    private var worker: Job? = null
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val resyncGeneration=MutableStateFlow(0L)
    val historyGeneration=resyncGeneration.asStateFlow()
    private val mutableSyncing = MutableStateFlow(false)
    val syncing = mutableSyncing.asStateFlow()

    private val reconnectWait = ReconnectWait()
    fun networkUnavailable() { reconnectWait.networkUnavailable() }
    fun networkAvailable() { reconnectWait.networkAvailable(); wake.trySend(Unit) }
    private val wake = Channel<Unit>(Channel.CONFLATED)
    fun requestCatchUp() { wake.trySend(Unit) }
    fun start() {
        if (worker != null) return
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
                            launch { connection.highWatermark.filterNotNull().collect { wake.trySend(Unit) } }
                            launch { connection.status.collect { wake.trySend(Unit) } }
                            launch { database.messages().observeOutbox(key).distinctUntilChanged().collect { wake.trySend(Unit) } }
                            launch { dao.observePendingReceipts(key).distinctUntilChanged().collect { wake.trySend(Unit) } }
                            launch { database.transfers().observeCompleted(key).distinctUntilChanged().collect { wake.trySend(Unit) } }
                            wake.trySend(Unit)
                            while(isActive) {
                                // A lost hint must not strand the durable journal. This is a safety check
                                // on the same live socket, not a scheduled background connection.
                                withTimeoutOrNull(JOURNAL_SAFETY_CHECK_MILLIS) { wake.receive() }
                                if(connection.status.value != ConnectionStatus.OPEN) throw ConnectionLost()
                                synchronize(key)
                                drain(key)
                                mutableError.value = null
                                reconnectWait.connected()
                            }
                        }                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        reportConnectionFailure(failure)
                        observed?.let {sessions.disconnect(it)}
                        mutableError.value = safeError(failure)
                        val retryAfter = (failure as? RemoteFailure)?.details?.get("retryAfterSeconds")?.jsonPrimitive?.longOrNull
                        reconnectWait.afterFailure(retryAfter)
                    }
                }
            }
        }
    }
    suspend fun stop() { worker?.cancelAndJoin(); worker = null }
    private fun requireAccount(key: String) {
        check(sessions.state.value.account?.key == key) { "Account changed" }
    }

    suspend fun synchronize(key: String = requireNotNull(sessions.state.value.account).key) = lock.withLock {
        requireAccount(key)
        mutableSyncing.value = true
        try {
            if (dao.syncState(key) == null) catalog(key)
            try { replay(key) }
            catch (error: RemoteFailure) {
                if (error.code != "RESYNC_REQUIRED") throw error
                catalog(key)
                replay(key)
                resyncGeneration.value += 1
            }
            dao.finishCatalog(key)
        } finally { mutableSyncing.value = false }
    }

    private suspend fun catalog(key: String) {
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
        dao.installCatalog(key, start.cursor, Uuid.random().toString())
    }
    private suspend fun replay(key: String) {
        do {
            val cursor = requireNotNull(dao.syncState(key)).cursor
            val page = sessions.request(Commands.SyncPull, SyncParams(cursor))
            val changed = linkedSetOf<String>()
            val revoked = linkedSetOf<String>()
            val receiptChats = linkedSetOf<String>()
            val messages = mutableListOf<Message>()
            for (event in page.items) {
                val chatId = event.data["chatId"]?.jsonPrimitive?.contentOrNull
                if(event.type=="receipt.updated" && chatId!=null) receiptChats.add(chatId)
                when {
                    event.type == "chat.accessRevoked" -> chatId?.let { revoked.add(it) }
                    event.type == "message.created" || event.type == "message.updated" -> {
                        val message = wireJson.decodeFromJsonElement<Message>(event.data)
                        messages.add(message); changed.add(message.chatId)
                    }
                    chatId != null -> changed.add(chatId)
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
            for(chatId in receiptChats - revoked) loadMembers(key,chatId)
            dao.applyPage(key, cursor, page, chats, messages, revoked)
            // ACK is deliberately after the completed Room transaction, including unknown events.
            sessions.request(Commands.SyncAck, AckParams(page.nextCursor))
            if (page.hasMore && page.nextCursor <= cursor) throw ProtocolFailure("SYNC_CURSOR_LOOP")
        } while (page.hasMore)
    }

    suspend fun flush() { sessions.state.value.account?.key?.let { drain(it) } }
    private suspend fun drain(key: String) = lock.withLock {
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
                val message = sessions.request(Commands.MessagesSend,
                    SendParams(pending.chatId, pending.operationId, pending.contentJson?.let { wireJson.decodeFromString<PlainContent>(it) } ?: PlainContent(pending.text), pending.replyTo),
                    Uuid.parse(pending.operationId))
                requireAccount(key)
                dao.cacheMessages(key, listOf(message))
                dao.cacheChat(key, sessions.request(Commands.ChatsGet, ChatParams(pending.chatId)))
            } catch (error: RemoteFailure) {
                if (error.retryable || error.code == "UNAUTHENTICATED") throw error
                dao.pendingState(key, pending.operationId, "FAILED")
                mutableError.value = error.code
            }
        }
    }

    fun members(key:String,chatId:String):Flow<List<MemberInfo>> = dao.observeMembers(key,chatId).map { rows -> rows.map { wireJson.decodeFromString<MemberInfo>(it.json) } }
    suspend fun initialUnreadCursor(key:String,chatId:String):Long? {
        if(connection.value.online) loadMembers(key,chatId)
        val chat=chat(key,chatId).first() ?: return null
        if(chat.unreadCount<=0) return null
        val own=dao.members(key,chatId).map {wireJson.decodeFromString<MemberInfo>(it.json)}.firstOrNull {it.userId==key.substringAfter(':')} ?: return null
        return maxOf(own.readSeq,dao.receipt(key,chatId,"read")?.throughSeq ?: 0,chat.membership.joinedSeq-1)
    }
    suspend fun loadMembers(key:String,chatId:String) {
        requireAccount(key)
        var cursor:String?=null
        val seen=mutableSetOf<String>()
        do {
            val page=sessions.request(MoreCommands.MembersList,ChatPageParams(chatId,cursor,100))
            requireAccount(key)
            dao.cacheMembers(page.items.map { MemberCacheEntity(key,chatId,it.userId,wireJson.encodeToString(it)) })
            cursor=page.nextCursor
            if(cursor!=null) check(seen.add(cursor))
        } while(cursor!=null)
    }
    fun chats(key: String): Flow<List<Chat>> = dao.observeChats(key).map { rows -> rows.map { wireJson.decodeFromString<Chat>(it.json) } }
    fun chat(key: String, chatId: String): Flow<Chat?> = dao.observeChat(key, chatId).map { it?.let { wireJson.decodeFromString<Chat>(it.json) } }
    suspend fun loadHistory(key: String, chatId: String, older: Boolean = false): History = lock.withLock {
        requireAccount(key)
        val chat = sessions.request(Commands.ChatsGet, ChatParams(chatId))
        dao.cacheChat(key, chat)
        val result = sessions.request(Commands.MessagesHistory,
            HistoryParams(chatId, "before", if (older) dao.oldest(key, chatId) else null, 50))
        requireAccount(key)
        dao.cacheMessages(key, result.items)
        result
    }
    suspend fun newDirect(userId: String): Chat = lock.withLock {
        val key = requireNotNull(sessions.state.value.account).key
        sessions.request(Commands.ChatsDirect, DirectParams(userId), Uuid.random()).also {
            requireAccount(key); dao.cacheChat(key, it)
        }
    }
    suspend fun send(key: String, chatId: String, text: String, replyTo: String? = null, files: List<String> = emptyList()) = lock.withLock {
        requireAccount(key)
        require((text.isNotBlank() || files.isNotEmpty()) && text.codePointCount() <= 16384 && files.size <= 10)
        val local = RoomLocalMessages(database.messages(), AccountId(Uuid.parse(key.substringBefore(':')), Uuid.parse(key.substringAfter(':'))))
        local.saveDraft(ChatId(Uuid.parse(chatId)), text)
        database.messages().enqueue(requireNotNull(database.messages().draft(key,chatId)), OutboxEntity(key, Uuid.random().toString(), chatId, text, Clock.System.now().toEpochMilliseconds(), if(files.isEmpty()) "READY" else "WAITING_FOR_FILES", replyTo, wireJson.encodeToString(PlainContent(text, textEntities=mentions(text,dao.members(key,chatId).map { wireJson.decodeFromString<MemberInfo>(it.json) }), attachmentIds=files))))
    }
    suspend fun read(key:String,chatId:String,seq:Long) { requireAccount(key); dao.advanceReceipt(key,chatId,"read",seq) }
    suspend fun retry(key: String, operationId: String) {
        requireAccount(key)
        val row = database.messages().operation(key,operationId) ?: return
        val content = row.contentJson?.let { wireJson.decodeFromString<PlainContent>(it) }
        val waiting = content?.attachmentIds?.any { database.transfers().get(key,it) != null } == true
        dao.pendingState(key, operationId, if(waiting) "WAITING_FOR_FILES" else "READY")
    }
    fun observeHistoryWindow(key:String,chatId:String,lower:Long,upper:Long?,limit:Int):Flow<List<Message>> =
        dao.observeWindow(key,chatId,lower,upper,limit).map { rows -> rows.map {wireJson.decodeFromString<Message>(it.json)} }
    suspend fun historyPage(key:String,chatId:String,direction:String,anchor:Long?,limit:Int=50):History {
        requireAccount(key)
        if(!sessions.state.value.online) {
            val rows=if(direction=="around" && anchor!=null) (dao.cachedBefore(key,chatId,anchor,limit/2).reversed()+dao.cachedAfter(key,chatId,anchor-1,limit/2))
                else if(direction=="after") dao.cachedAfter(key,chatId,anchor ?: 0,limit)
                else dao.cachedBefore(key,chatId,anchor ?: Long.MAX_VALUE,limit).reversed()
            val items=rows.map {wireJson.decodeFromString<Message>(it.json)}
            return History(items,items.firstOrNull()?.seq,items.lastOrNull()?.seq,true,true)
        }
        val page=sessions.request(Commands.MessagesHistory,HistoryParams(chatId,direction,anchor,limit))
        requireAccount(key);dao.cacheMessages(key,page.items)
        return page
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
        val message=sessions.request(MoreCommands.MessagesGet,MessageParams(messageId))
        require(message.chatId==chatId);requireAccount(key);dao.cacheMessages(key,listOf(message))
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

    suspend fun <P, R> execute(command: Command<P, R>, params: P, operationId: Uuid? = null): R = lock.withLock {
        val key = requireNotNull(sessions.state.value.account).key
        // Persist only a digest and operation identity. OTP/TOTP commands stay ephemeral.
        val durable = command.mutation && !command.name.startsWith("auth.") && !command.name.startsWith("users.email.")
        val requestKey = if(durable) (command.name+":"+wireJson.encodeToString(command.params,params)).encodeUtf8().sha256().hex() else null
        val stableId = if(requestKey!=null) {
            val existing=dao.mutation(key,requestKey)
            if(existing!=null) Uuid.parse(existing.operationId)
            else (operationId ?: Uuid.random()).also { dao.rememberMutation(MutationEntity(key,requestKey,it.toString())) }
        } else operationId
        val result = sessions.request(command, params, stableId)
        requireAccount(key)
        when (result) {
            is Me -> database.profiles().put(AccountProfileEntity(key,wireJson.encodeToString<Me>(result)))
            is Chat -> dao.cacheChat(key, result)
            is Message -> dao.cacheMessages(key, listOf(result))
            is Items<*> -> dao.cacheMessages(key, result.items.filterIsInstance<Message>())
        }
        if(requestKey!=null) dao.forgetMutation(key,requestKey)
        result
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
