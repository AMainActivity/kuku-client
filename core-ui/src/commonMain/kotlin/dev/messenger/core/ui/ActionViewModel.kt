package dev.messenger.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.Uuid

open class ActionViewModel : ViewModel() {
    protected val mutableBusy = MutableStateFlow(false)
    val busy = mutableBusy.asStateFlow()
    protected val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    fun reportError(code:String?) { mutableError.value=code }
    fun action(work: suspend () -> Unit) {
        if (mutableBusy.value) return
        mutableBusy.value = true; mutableError.value = null
        viewModelScope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableError.value = dev.messenger.domain.failureCode(failure) }
            finally { mutableBusy.value = false }
        }
    }
}

