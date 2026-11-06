package dev.messenger.data.application

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.data.session.SessionCoordinator
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class ApplicationSession(private val source:SessionCoordinator,owner:CoroutineScope):SessionAccess {
    override val state=source.state.map {state ->
        SessionState(state.loaded,state.account?.let {ActiveAccount(it.serverId,it.userId,it.sessionId)},state.online)
    }.stateIn(owner,SharingStarted.Eagerly,SessionState())
    private suspend fun <T> auth(work:suspend ()->T):T = try {applicationCall(work)}
    catch(cancelled:CancellationException) {throw cancelled}
    catch(failure:Exception) {dev.messenger.data.network.reportAuthFailure((failure.cause as? Exception) ?: failure);throw failure}
    override suspend fun restore()=auth {source.restore()}
    override suspend fun prepareLogin(endpoint:ServerEndpoint) {auth {source.prepareLogin(endpoint)}}
    override suspend fun cancelLogin()=auth {source.cancelLogin()}
    override suspend fun startEmail(endpoint:ServerEndpoint,email:String)=auth {source.startEmail(endpoint,email).toModel()}
    override suspend fun verifyEmail(code:String,deviceName:String,mfaCode:String?) {auth {source.verifyEmail(code,deviceName,mfaCode)}}
}
