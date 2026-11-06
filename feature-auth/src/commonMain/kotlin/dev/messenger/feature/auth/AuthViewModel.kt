package dev.messenger.feature.auth
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.core.ui.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.messenger.domain.SessionAccess
import dev.messenger.domain.model.Chat
import dev.messenger.domain.ServerEndpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
class AuthViewModel(val sessions: SessionAccess) : ViewModel() {
    val state = sessions.state
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun reportError(code:String?) { mutableError.value=code }
    private val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    private val mutableChallengeReady = MutableStateFlow(false)
    val challengeReady = mutableChallengeReady.asStateFlow()
    private val mutableResendAt = MutableStateFlow(0L)
    val resendAt = mutableResendAt.asStateFlow()
    private val mutableLeaving = MutableStateFlow(false)
    val leaving = mutableLeaving.asStateFlow()
    private var actionJob:Job?=null
    init { action { sessions.restore() } }
    fun action(work: suspend () -> Unit) {
        if (mutableBusy.value || mutableLeaving.value) return
        mutableBusy.value = true; mutableError.value = null
        actionJob=viewModelScope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                
                mutableError.value = failureCode(failure)
            }
            finally { mutableBusy.value = false }
        }
    }
    fun cancelLogin(onCancelled:()->Unit) {
        if(mutableLeaving.value) return
        mutableLeaving.value=true
        val pending=actionJob
        pending?.cancel()
        viewModelScope.launch {
            try {
                pending?.join()
                if(sessions.cancelLogin()) {
                    mutableChallengeReady.value=false;mutableResendAt.value=0;mutableError.value=null
                    onCancelled()
                }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) {
                
                mutableError.value=failureCode(failure)
            } finally { mutableLeaving.value=false }
        }
    }

    fun requestCode(host:String,port:Int,email:String) {
        if(mutableBusy.value || mutableLeaving.value) return
        val normalized=dev.messenger.domain.AddressValidation.normalizeEmail(email)
        if(normalized==null) {mutableError.value="INVALID_EMAIL";return}
        action {
            val challenge=sessions.startEmail(requireNotNull(ServerEndpoint.parse(host,port.toString())),normalized)
            mutableChallengeReady.value=true
            mutableResendAt.value=kotlin.time.Clock.System.now().toEpochMilliseconds()+challenge.resendAfterSeconds*1000L
        }
    }
    fun verify(code: String, mfa: String) = action { sessions.verifyEmail(code, "Kuku!", mfa.ifBlank { null }) }

}

