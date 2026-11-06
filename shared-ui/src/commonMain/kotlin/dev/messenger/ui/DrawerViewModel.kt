package dev.messenger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class DrawerViewModel(private val sessions:SessionAccess,private val repository:ProfileRepository,private val transfers:AttachmentRepository):ViewModel() {
    val profile=sessions.state.map { it.account?.key }.distinctUntilChanged().flatMapLatest { key ->
        if(key==null) flowOf(null) else repository.profile(key)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val avatar=combine(profile,sessions.state) { me,state -> state.account?.key to me?.avatarId }.distinctUntilChanged().mapLatest { (key,id) ->
        if(key==null || id==null) null else try {transfers.avatarBytes(key,id)}
        catch(cancelled:CancellationException){throw cancelled} catch(_:Exception){null}
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun reportError(code:String?) { mutableError.value=code }
    init {
        viewModelScope.launch {
            sessions.state.map { it.account?.key to it.online }.distinctUntilChanged().collectLatest { (key,online) ->
                if(key!=null && online) try {repository.usersMe()}
                catch(cancelled:CancellationException){throw cancelled} catch(_:Exception){ /* Cached profile stays visible offline. */ }
            }
        }
    }
    fun logout() {
        if(mutableBusy.value) return
        mutableBusy.value=true
        viewModelScope.launch {
            try {repository.logout()}
            catch(cancelled:CancellationException){throw cancelled}
            catch(failure:Exception){mutableError.value=failureCode(failure)}
            finally {mutableBusy.value=false}
        }
    }
}
