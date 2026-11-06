package dev.messenger.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import dev.messenger.core.ui.androidUiSettings
import dev.messenger.data.*
import dev.messenger.data.network.MessengerNetwork
import dev.messenger.data.session.SecureStore
import dev.messenger.domain.*
import dev.messenger.feature.admin.AdminViewModel
import dev.messenger.feature.auth.*
import dev.messenger.feature.chats.*
import dev.messenger.feature.conversation.ChatViewModel
import dev.messenger.feature.groups.GroupViewModel
import dev.messenger.feature.profile.AccountViewModel
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.parameter.parametersOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class ContainerTest {
    @Test fun completeGraphResolvesAndSeparatesRepositoryResponsibilities()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context=RuntimeEnvironment.getApplication()
        val store=androidLocalStore(context)
        val network=MessengerNetwork(HttpClient(MockEngine {error("DI test must not access a server")}))
        val secure=object:SecureStore {
            override suspend fun read():String?=null
            override suspend fun write(value:String) {}
        }
        val background=object:BackgroundConnection {
            override val state=MutableStateFlow(MessagingServiceState())
            override fun retry()=true
            override fun notificationsEnabled()=true
        }
        val container=AppContainer(store,network,secure,ManagedFiles(context.cacheDir.resolve("graph-test").path),androidUiSettings(context),background)
        val models=ViewModelStore()
        try {
            val koin=container.application.koin
            val setup=koin.get<SetupViewModel> {parametersOf(SavedStateHandle(mapOf("host" to "example.com")))}
            assertEquals("example.com",setup.endpoint()?.host)
            val account=AccountId(Uuid.random(),Uuid.random())
            val key=account.serverId.toString()+":"+account.userId
            val chatId=Uuid.random().toString()
            val resolved=listOf<ViewModel>(setup,koin.get<AuthViewModel>(),koin.get<ChatsViewModel>(),
                koin.get<IncomingShareViewModel>(),koin.get<DirectoryViewModel>(),koin.get<AccountViewModel>(),
                koin.get<AdminViewModel>(),koin.get<DrawerViewModel>(),
                koin.get<GroupViewModel> {parametersOf(key,chatId)},
                koin.get<ChatViewModel> {parametersOf(key,chatId)})
            resolved.forEachIndexed {index,model -> models.put(index.toString(),model)}
            assertNotNull(koin.get<LocalMessages> {parametersOf(account)})
            val repositories=listOf<Any>(koin.get<MessagingRepository>(),koin.get<DirectoryRepository>(),
                koin.get<GroupRepository>(),koin.get<ProfileRepository>(),koin.get<AdminRepository>())
            assertEquals(5,repositories.toSet().size)
            assertSame(koin.get<MessagingRepository>(),koin.get<MessagingRepository>())
            assertSame(background,koin.get<BackgroundConnection>())
        } finally {
            models.clear()
            container.closeAndJoin()
            container.closeAndJoin()
            Dispatchers.resetMain()
        }
    }
}
