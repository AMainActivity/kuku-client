package dev.messenger.ui
import dev.messenger.runtime.*
import dev.messenger.core.ui.*
import dev.messenger.feature.chats.*
import dev.messenger.feature.auth.*
import dev.messenger.feature.profile.*
import dev.messenger.feature.conversation.*
import dev.messenger.feature.groups.*
import dev.messenger.feature.admin.*


import dev.messenger.data.*
import dev.messenger.data.network.MessengerNetwork
import dev.messenger.data.session.*
import dev.messenger.domain.*
import dev.messenger.data.application.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class AppContainer(private val store: LocalStore, private val network: MessengerNetwork, secureStore: SecureStore, files: ManagedFiles, val uiSettings:UiSettings, background:BackgroundConnection? = null) {
    internal val importScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    val shares=store.shares(files)
    val notificationTarget=kotlinx.coroutines.flow.MutableStateFlow<Pair<String,String>?>(null)
    fun openNotification(account:String?,chat:String?) {
        if(account==null || chat==null) return
        val ids=account.split(':' )
        if(ids.size!=2 || (ids+chat).any { runCatching { kotlin.uuid.Uuid.parse(it) }.isFailure }) return
        notificationTarget.value=account to chat
    }
    val shareImportError=kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val runtime = AppRuntime(store,network,secureStore,files)
    val sessions = runtime.sessions
    val conversations = runtime.conversations
    val transfers = runtime.transfers
    fun setActive(client:String,active:Boolean) = runtime.setActive(client,active)
    private val sessionAccess=ApplicationSession(sessions,importScope)
    private val repositories=ApplicationRepositories(conversations,sessionAccess)
    private val attachments=ApplicationAttachments(transfers)
    val application = koinApplication {
        modules(authModule(), chatsModule(), conversationModule(), groupsModule(), profileModule(), adminModule(), module {
            single { this@AppContainer }
            single { uiSettings }
            if(background!=null) single<BackgroundConnection> {background}
            single { store }
            single { sessions }
            single { conversations }
            single { transfers }
            single { shares }
            single<SessionAccess> {sessionAccess}
            single<MessagingRepository> {repositories.messaging}
            single<GroupRepository> {repositories.groups}
            single<DirectoryRepository> {repositories.directory}
            single<ProfileRepository> {repositories.profile}
            single<AdminRepository> {repositories.admin}
            single<AttachmentRepository> {attachments}
            single<DraftRepository> {ApplicationDrafts(store)}
            single<IncomingShareRepository> {ApplicationShares(shares)}
            factory<LocalMessages> { parameters -> store.messages(parameters.get<AccountId>()) }
            viewModel { DrawerViewModel(get(),get(),get()) }
        })
    }
    private val shutdownJob=importScope.launch(start=CoroutineStart.LAZY) {
        try { application.close() }
        finally {
            try { runtime.close() }
            finally { importScope.cancel() }
        }
    }
    /** Non-blocking host disposal; cleanup has one owner and is idempotent. */
    fun close() { shutdownJob.start() }
    suspend fun closeAndJoin() { close(); shutdownJob.join() }
}

