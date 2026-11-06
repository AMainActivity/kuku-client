package dev.messenger.feature.chats
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.core.ui.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.messenger.domain.MessagingRepository
import dev.messenger.domain.SessionAccess
import dev.messenger.domain.ServerEndpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatsViewModel(val sessions: SessionAccess, val repository: MessagingRepository, store:dev.messenger.domain.DraftRepository,private val transfers:dev.messenger.domain.AttachmentRepository) : ViewModel() {
    val state = sessions.state
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun reportError(code:String?) { mutableError.value=code }
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    val chats = sessions.state.map { it.account?.key }.distinctUntilChanged()
        .flatMapLatest { key -> if (key == null) flowOf(emptyList()) else repository.chats(key) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList<Chat>())

    val drafts=sessions.state.map { it.account?.key }.distinctUntilChanged().flatMapLatest { key ->
        if(key==null) flowOf(emptyList()) else store.drafts(key)
    }.map { rows -> rows.filter { it.text.isNotBlank() || it.files.isNotEmpty() || it.replyTo!=null }.associateBy { it.chatId } }
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyMap())
    init { action { sessions.restore() } }
    fun action(work: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true; mutableError.value = null
        viewModelScope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableError.value = failureCode(failure) }
            finally { mutableBusy.value = false }
        }
    }
    suspend fun avatar(chat:Chat):ByteArray? {
        val key=state.value.account?.key ?: return null
        val id=chat.avatarId ?: chat.peer?.avatarId ?: return null
        return try {transfers.avatarBytes(key,id)} catch(cancelled:CancellationException) {throw cancelled} catch(_:Exception) {null}
    }
    fun setPinned(chatId:String,pinned:Boolean) = action {
        val key=state.value.account?.key ?: return@action
        val current=repository.chat(key,chatId).first() ?: return@action
        if(current.preferences.pinned==pinned) return@action
        repository.preferencesSet(
            chatId,current.preferences.version,pinned,current.preferences.pinRank,current.preferences.muted)
    }
    fun direct(userId: String, opened: (String) -> Unit) = action { opened(repository.newDirect(userId).chatId) }
    fun logout() = action {
        val revoked = repository.logout()
        if (!revoked) mutableError.value = "LOCAL_LOGOUT_ONLY"
    }
}

