package dev.messenger.feature.conversation
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.core.ui.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

class ChatViewModel(val accountKey: String, val chatId: String,
    private val repository: MessagingRepository, private val store: DraftRepository, val transfers: AttachmentRepository) : ViewModel() {
    private val local = store.messages(AccountId(Uuid.parse(accountKey.substringBefore(':')), Uuid.parse(accountKey.substringAfter(':'))))
    private val mutableShareReady = MutableStateFlow<ShareBundle?>(null)
    val shareReady = mutableShareReady.asStateFlow()
    fun shareConsumed() { mutableShareReady.value=null }
    private val mutablePreparingShare = MutableStateFlow(false)
    val preparingShare = mutablePreparingShare.asStateFlow()
    private var shareJob:Job?=null
    private val mutableScrollTarget = MutableStateFlow<String?>(null)
    val scrollTarget = mutableScrollTarget.asStateFlow()
    private val mutableReceipts = MutableStateFlow<List<MemberInfo>>(emptyList())
    val receipts = mutableReceipts.asStateFlow()
    val connection = repository.connection
    val syncError = repository.error
    val chat = repository.chat(accountKey, chatId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val avatar=chat.map {it?.avatarId ?: it?.peer?.avatarId}.distinctUntilChanged().mapLatest {id ->
        if(id==null) null else try {transfers.avatarBytes(accountKey,id)} catch(cancelled:CancellationException){throw cancelled} catch(_:Exception){null}
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val members = repository.members(accountKey,chatId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val historyWindow=HistoryWindow(repository,accountKey,chatId)
    val history = historyWindow.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), emptyList())
    @OptIn(ExperimentalCoroutinesApi::class)
    val replyTargets=combine(history,connection.map {it.online}.distinctUntilChanged()) {rows,_ ->
        rows.mapNotNull {it.replyTo}.distinct().sorted()
    }.flatMapLatest {ids -> repository.replyTargets(accountKey,chatId,ids)}
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(0),emptyMap())
    val openingAnchor=historyWindow.openingAnchor
    val latestAnchor=historyWindow.latestAnchor
    val openingCursor=historyWindow.openingCursor
    val openingReady=historyWindow.openingReady
    val atTail=historyWindow.atTail
    private val mutableBottomRequest = MutableStateFlow(0L)
    val bottomRequest = mutableBottomRequest.asStateFlow()
    private val mutableMovingToBottom = MutableStateFlow(false)
    val movingToBottom = mutableMovingToBottom.asStateFlow()
    private var bottomJob:Job?=null
    fun latest() {
        bottomJob?.cancel()
        mutableMovingToBottom.value=true
        bottomJob=viewModelScope.launch {
            try {
                jumpJob?.cancelAndJoin();mutableScrollTarget.value=null
                viewportJob?.cancelAndJoin()
                historyWindow.latest {mutableBottomRequest.value+=1}
                if(historyWindow.error.value!=null) mutableMovingToBottom.value=false
            } catch(cancelled:CancellationException) {throw cancelled}
        }
    }
    fun finishBottom() {mutableMovingToBottom.value=false}
    val historyError=historyWindow.error
    val pending = local.observePending(ChatId(Uuid.parse(chatId))).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val files = transfers.observe(accountKey,chatId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutableSelectedFiles = MutableStateFlow<List<String>>(emptyList())
    val selectedFiles = mutableSelectedFiles.asStateFlow()
    private val mutableReply = MutableStateFlow<String?>(null)
    val reply = mutableReply.asStateFlow()
    val targets = repository.chats(accountKey).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutableText = MutableStateFlow("")
    val text = mutableText.asStateFlow()
    private val mutableLoaded = MutableStateFlow(false)
    val loaded = mutableLoaded.asStateFlow()
    val loading = historyWindow.loading
    val more = historyWindow.moreBefore
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun reportError(code:String?) { mutableError.value=code }
    private sealed interface Edit {
        data class Text(val value: String) : Edit
        data class Extras(val replyTo: String?, val files: List<String>) : Edit
        data class Send(val value: String, val replyTo: String?, val files: List<String>) : Edit
    }
    private val edits = Channel<Edit>(Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            mutableText.value = local.observeDraft(ChatId(Uuid.parse(chatId))).first()?.text.orEmpty()
            val extras = store.composer(accountKey,chatId)
            mutableReply.value = extras.replyTo; mutableSelectedFiles.value = extras.files
            mutableLoaded.value = true
            for (edit in edits) {
                try {
                    when (edit) {
                        is Edit.Extras -> store.saveComposer(accountKey,chatId,edit.replyTo,edit.files)
                        is Edit.Text -> local.saveDraft(ChatId(Uuid.parse(chatId)), edit.value)
                        is Edit.Send -> {
                            repository.send(accountKey, chatId, edit.value, edit.replyTo, edit.files)
                            latest()
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    mutableError.value = failureCode(failure)
                    if (edit is Edit.Send && mutableText.value.isEmpty()) {
                        mutableText.value = edit.value
                        if(mutableReply.value==null) mutableReply.value=edit.replyTo
                        if(mutableSelectedFiles.value.isEmpty()) mutableSelectedFiles.value=edit.files
                    }
                }
            }
        }
    }
    fun edit(value: String) { mutableText.value = value; edits.trySend(Edit.Text(value)) }
    fun send() {
        val value = mutableText.value
        if (value.isBlank() && mutableSelectedFiles.value.isEmpty()) return
        mutableText.value = ""; edits.trySend(Edit.Send(value, mutableReply.value, mutableSelectedFiles.value)); mutableReply.value = null; mutableSelectedFiles.value = emptyList()
    }
    private var viewportJob:Job?=null
    fun onHistoryViewport(nearOldEdge:Boolean,nearNewEdge:Boolean) {
        if(!openingReady.value || mutableMovingToBottom.value || mutableScrollTarget.value!=null || viewportJob?.isActive==true) return
        viewportJob=viewModelScope.launch {historyWindow.viewport(nearOldEdge,nearNewEdge)}
    }
    fun retry(operationId: String) { viewModelScope.launch { repository.retry(accountKey, operationId) } }
    private val pickedMutex=Mutex()
    fun picked(jobId: String, filename: String, mime: String) {
        viewModelScope.launch {
            pickedMutex.withLock {
                suspend fun discard() = withContext(NonCancellable+Dispatchers.IO) {
                    transfers.cancel(accountKey,jobId)
                    transfers.files.remove(accountKey,jobId)
                }
                try {
                    if(mutableSelectedFiles.value.size>=MAX_ATTACHMENTS) {
                        discard();mutableError.value="ATTACHMENT_LIMIT";return@withLock
                    }
                    transfers.registerUpload(accountKey,chatId,jobId,filename,mime)
                    mutableSelectedFiles.value=mutableSelectedFiles.value+jobId
                    saveExtras()
                } catch(cancelled:CancellationException) {discard();throw cancelled}
                catch(failure:Exception) {discard();mutableError.value=failureCode(failure)}
            }
        }
    }
    fun download(attachment: Attachment) { viewModelScope.launch {
        try { transfers.enqueueDownload(accountKey,chatId,attachment) }
        catch(cancelled: CancellationException) { throw cancelled }
        catch(failure: Exception) { mutableError.value = failureCode(failure) }
    } }
    fun transferAction(job: TransferSnapshot, action: String) { viewModelScope.launch {
        when(action) {
            "pause" -> transfers.pause(accountKey,job.jobId)
            "cancel" -> { transfers.cancel(accountKey,job.jobId); mutableSelectedFiles.value -= job.jobId; saveExtras() }
            else -> transfers.resume(accountKey,job.jobId)
        }
    } }
    fun selectReply(messageId: String?) { mutableReply.value = messageId; saveExtras() }
    private fun saveExtras() { edits.trySend(Edit.Extras(mutableReply.value,mutableSelectedFiles.value)) }
    private fun mutate(identity: String, work: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                work()
                if(!identity.startsWith("read:")) mutableError.value=null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableError.value = failureCode(failure) }
        }
    }
    fun update(message: Message, value: String) = mutate("edit:${message.messageId}:${message.version}:$value") {
        repository.messagesEdit(message.messageId, message.version, value)
    }
    fun delete(message: Message) = mutate("delete:${message.messageId}:${message.version}") {
        repository.messagesDelete(message.messageId, message.version)
    }
    fun forward(message: Message, target: String) = mutate("forward:${message.messageId}:$target") {
        repository.messagesForward(target, listOf(message.messageId))
    }
    fun share(messageIds:List<String>) {
        shareJob?.cancel()
        shareJob=viewModelScope.launch {
            mutablePreparingShare.value=true
            try {
                val selected=history.value.filter { it.messageId in messageIds }.sortedBy { it.seq }
                require(selected.size==messageIds.size && selected.none { it.deleted })
                val files=transfers.prepareShare(accountKey,chatId,selected.flatMap { it.attachments })
                mutableShareReady.value=ShareBundle(selected.mapNotNull { it.plainTextOrNull() }.filter { it.isNotBlank() }.joinToString("\n\n"),files)
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { mutableError.value="SHARE_FILES_UNAVAILABLE" }
            finally { mutablePreparingShare.value=false }
        }
    }
    fun cancelShare() { shareJob?.cancel();mutableShareReady.value=null }
    fun forwardMany(messageIds:List<String>,target:String) = mutate("forward:${messageIds.joinToString()}:$target") {
        repository.messagesForward(target,messageIds)
    }
    private var jumpJob:Job?=null
    fun jump(messageId:String) {
        jumpJob?.cancel()
        mutableScrollTarget.value=messageId
        jumpJob=viewModelScope.launch {
            viewportJob?.cancelAndJoin()
            try {
                historyWindow.jump(messageId)
                if(historyError.value!=null && mutableScrollTarget.value==messageId) mutableScrollTarget.value=null
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(failure:Exception) {
                mutableError.value=failureCode(failure)
                if(mutableScrollTarget.value==messageId) mutableScrollTarget.value=null
            }
        }
    }
    fun finishJump(messageId:String) {if(mutableScrollTarget.value==messageId) mutableScrollTarget.value=null}
    fun loadReceipts() { viewModelScope.launch {
        try {
            val result=mutableListOf<MemberInfo>(); var cursor:String?=null; val seen=mutableSetOf<String>()
            do {
                val page=repository.receiptsList(chatId,cursor,100)
                result+=page.items; cursor=page.nextCursor
                if(cursor!=null) check(seen.add(cursor))
            } while(cursor!=null)
            mutableReceipts.value=result
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(failure:Exception) { mutableError.value=failureCode(failure) }
    } }
    fun read(through: Long) = mutate("read:$through") {
        repository.read(accountKey,chatId,through)
    }
    override fun onCleared() { edits.close() }
}

data class ShareBundle(val text:String,val files:List<TransferSnapshot>)

private const val MAX_ATTACHMENTS=10

