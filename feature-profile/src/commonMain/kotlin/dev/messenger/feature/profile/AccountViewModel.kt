package dev.messenger.feature.profile
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.core.ui.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.messenger.domain.ProfileRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.Uuid

class AccountViewModel(private val repository: ProfileRepository, val transfers:dev.messenger.domain.AttachmentRepository, private val sessions:dev.messenger.domain.SessionAccess) : ActionViewModel() {
    val accountKey get() = sessions.state.value.account?.key
    @OptIn(ExperimentalCoroutinesApi::class)
    val avatarJobs = sessions.state.map { it.account }.distinctUntilChanged().flatMapLatest { account ->
        if(account==null) flowOf(emptyList()) else transfers.observe(account.key,account.userId).map { jobs -> jobs.filter { it.purpose=="avatar" } }
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    fun avatarPicked(id:String,name:String,mime:String) = action {
        val account=requireNotNull(sessions.state.value.account)
        transfers.registerUpload(account.key,account.userId,id,name,mime,"avatar")
    }
    fun avatar(id:String?) = action {
        val profile=requireNotNull(mutableMe.value)
        repository.usersUpdate(profile.version,profile.displayName,id)
        mutableMe.value=repository.usersMe()
    }
    fun avatarTransfer(job:dev.messenger.domain.model.TransferSnapshot,cancel:Boolean) = action {
        if(cancel) transfers.cancel(job.accountId,job.jobId) else transfers.resume(job.accountId,job.jobId)
    }
    private val mutableMe = MutableStateFlow<Me?>(null)
    val me = mutableMe.asStateFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    val avatarImage = combine(me,sessions.state) { profile,state -> state.account?.key to profile?.avatarId }
        .distinctUntilChanged().mapLatest { (key,id) ->
            if(key==null || id==null) null else try { transfers.avatarBytes(key,id) }
            catch(cancelled:CancellationException) { throw cancelled } catch(_:Exception) { null }
        }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    private val mutableDevices = MutableStateFlow<List<DeviceSession>>(emptyList())
    val devices = mutableDevices.asStateFlow()
    private val mutableEnrollment = MutableStateFlow<MfaEnrollment?>(null)
    val enrollment = mutableEnrollment.asStateFlow()
    private val mutableEmailChange = MutableStateFlow<EmailChange?>(null)
    val emailChange = mutableEmailChange.asStateFlow()
    init {
        viewModelScope.launch {
            sessions.state.map {it.account?.key}.distinctUntilChanged().collectLatest {key ->
                if(key==null) mutableMe.value=null else repository.profile(key).collect {mutableMe.value=it}
            }
        }
        refresh()
    }
    fun clearCache() = action { sessions.state.value.account?.key?.let { transfers.clearDownloadCache(it) } }
    fun refresh() = action {
        mutableMe.value = repository.usersMe()
        mutableDevices.value = repository.sessionsList().items
    }
    fun profile(displayName: String) = action {
        val profile = requireNotNull(mutableMe.value)
        repository.usersUpdate(profile.version, displayName, profile.avatarId)
        mutableMe.value = repository.usersMe()
    }
    fun revoke(id: String) = action {
        repository.sessionsRevoke(id)
        mutableDevices.value = repository.sessionsList().items
    }
    fun setupMfa() = action { mutableEnrollment.value = repository.mfaSetup() }
    fun confirmMfa(code: String) = action {
        repository.mfaConfirm(code); mutableEnrollment.value = null
        mutableMe.value = repository.usersMe()
    }
    fun stepUp(code: String) = action { repository.mfaStepUp(code) }
    fun changeEmail(email: String) = action { mutableEmailChange.value = repository.emailChangeStart(email) }
    fun verifyEmail(oldCode: String, newCode: String, mfa: String) = action {
        val change = requireNotNull(mutableEmailChange.value)
        repository.emailChangeVerify(change.changeId, oldCode, newCode, mfa.ifBlank { null })
        mutableEmailChange.value = null
        repository.logout()
    }
}

