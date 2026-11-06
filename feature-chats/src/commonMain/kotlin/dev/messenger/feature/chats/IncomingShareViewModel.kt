package dev.messenger.feature.chats
import dev.messenger.domain.*
import dev.messenger.domain.model.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.messenger.domain.SessionAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class IncomingShareViewModel(private val inbox:IncomingShareRepository,private val sessions:SessionAccess,repository:MessagingRepository):ViewModel() {
    val pending=inbox.pending.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    val chats=sessions.state.map { it.account?.key }.distinctUntilChanged().flatMapLatest { key ->
        if(key==null) flowOf(emptyList()) else repository.chats(key)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun reportError(code:String?) { mutableError.value=code }
    fun accept(id:String,chatId:String,opened:(String)->Unit)=action {
        val account=requireNotNull(sessions.state.value.account).key
        inbox.accept(id,account,chatId)
        if(sessions.state.value.account?.key==account) opened(chatId)
    }
    fun discard(id:String)=action { inbox.discard(id) }
    private fun action(work:suspend ()->Unit) {
        if(mutableBusy.value) return
        mutableBusy.value=true
        viewModelScope.launch {
            try { mutableError.value=null; work() }
            catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) { mutableError.value=failureCode(failure) }
            finally { mutableBusy.value=false }
        }
    }
}
