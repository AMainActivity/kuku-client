package dev.messenger.runtime

import dev.messenger.data.*
import dev.messenger.data.network.MessengerNetwork
import dev.messenger.data.network.reportTokenRefreshed
import dev.messenger.data.session.*
import dev.messenger.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SessionRestoration { RESTORING, READY, FAILED }

class AppRuntime(private val store: LocalStore, private val network: MessengerNetwork, secureStore: SecureStore, files: ManagedFiles, onAccountRemoved: (String) -> Unit = {}) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sessions = SessionCoordinator(secureStore,
        Connector { endpoint, owner, expected -> network.connect(endpoint, owner, expected) }, scope,onTokenRefreshed=::reportTokenRefreshed)
    val conversations = store.conversations(sessions, scope)
    val transfers = store.transfers(files, network, sessions, scope)
    init { conversations.onAccountRemoved = { key -> try { transfers.clearAccount(key) } finally { onAccountRemoved(key) } } }
    private val runtimeLock = Mutex()
    private val clients = mutableSetOf<String>()
    private val mutableRestoration=MutableStateFlow(SessionRestoration.RESTORING)
    val restoration=mutableRestoration.asStateFlow()
    private val commands=Channel<Pair<String,Boolean>>(Channel.UNLIMITED)
    private val lifecycleJob=scope.launch {
        for((client,active) in commands) runtimeLock.withLock {
            if(active) clients.add(client) else clients.remove(client)
            if(!sessions.state.value.loaded) mutableRestoration.value=SessionRestoration.RESTORING
            try {
                sessions.restore()
                mutableRestoration.value=SessionRestoration.READY
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) {
                // Storage failure is not logout. Keep the command loop alive for retry.
                mutableRestoration.value=SessionRestoration.FAILED
                return@withLock
            }
            if(clients.isNotEmpty()) {
                conversations.start()
                if("ui" in clients || "files" in clients) transfers.start() else transfers.stop()
            } else { conversations.stop(); transfers.stop(); sessions.disconnect() }
        }
    }
    fun setActive(client:String,active:Boolean) { commands.trySend(client to active) }
    suspend fun close() {
        commands.close(); lifecycleJob.cancelAndJoin()
        transfers.stop(); conversations.stop(); sessions.disconnect(); network.close(); store.close(); scope.cancel()
    }
}
