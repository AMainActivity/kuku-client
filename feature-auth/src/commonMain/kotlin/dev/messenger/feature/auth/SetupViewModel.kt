package dev.messenger.feature.auth
import dev.messenger.domain.*
import dev.messenger.domain.model.*

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.messenger.domain.MessagingRepository
import dev.messenger.domain.ServerEndpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SetupViewModel(private val state:SavedStateHandle,
    private val checkServer:suspend (ServerEndpoint)->Unit):ViewModel() {
    val host=state.getStateFlow("host","")
    val port=state.getStateFlow("port","443")
    private val mutableBusy=MutableStateFlow(false)
    val busy=mutableBusy.asStateFlow()
    private val mutableError=MutableStateFlow<String?>(null)
    val error=mutableError.asStateFlow()
    private var checking:Job?=null
    fun changeHost(value:String) { if(!busy.value) {state["host"]=value;mutableError.value=null} }
    fun changePort(value:String) { if(!busy.value) {state["port"]=value;mutableError.value=null} }
    fun endpoint():ServerEndpoint?=ServerEndpoint.parse(host.value,port.value)
    fun connect(onConnected:(String,Int)->Unit) {
        if(busy.value) return
        val selected=endpoint() ?: return
        mutableBusy.value=true;mutableError.value=null
        checking=viewModelScope.launch {
            try {
                checkServer(selected)
                ensureActive()
                onConnected(selected.host,selected.port)
            } catch(timeout:TimeoutCancellationException) {
                mutableError.value="TIMEOUT"; 
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(failure:Exception) {
                mutableError.value=failureCode(failure)
                
            } finally { mutableBusy.value=false }
        }
    }
    fun cancelCheck() { checking?.cancel() }
}
