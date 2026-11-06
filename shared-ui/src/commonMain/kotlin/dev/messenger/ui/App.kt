package dev.messenger.ui
import dev.messenger.runtime.*
import dev.messenger.core.ui.*
import dev.messenger.feature.chats.*
import dev.messenger.feature.auth.*
import dev.messenger.feature.profile.*
import dev.messenger.feature.conversation.*
import dev.messenger.feature.groups.*
import dev.messenger.feature.admin.*


import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.*
import androidx.navigation3.ui.NavDisplay
import dev.messenger.domain.OutboxState
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.KoinIsolatedContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun App(container: AppContainer) {
    val theme by container.uiSettings.theme.collectAsStateWithLifecycle()
    val dark = theme=="dark" || (theme=="system" && androidx.compose.foundation.isSystemInDarkTheme())
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(container,lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver { _,event ->
            if(event==androidx.lifecycle.Lifecycle.Event.ON_START) container.setActive("ui",true)
            if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP) container.setActive("ui",false)
        }
        lifecycle.addObserver(observer)
        container.setActive("ui",lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); container.setActive("ui",false) }
    }
    KoinIsolatedContext(container.application) {
        
        val state by container.sessions.state.collectAsStateWithLifecycle()
        ConnectionHost(state.account != null,dark)
        val stack = rememberNavBackStack(navigationConfig, ServerRoute)
        LaunchedEffect(state.account?.key) {
            if (state.account != null) {
                if (stack.firstOrNull() != ChatsRoute) { stack.clear(); stack.add(ChatsRoute) }
            } else if (stack.any { it == ChatsRoute || it is ConversationRoute }) {
                stack.clear(); stack.add(ServerRoute)
            }
        }
        val notificationTarget by container.notificationTarget.collectAsStateWithLifecycle()
        LaunchedEffect(notificationTarget,state.loaded,state.account?.key) {
            val target=notificationTarget ?: return@LaunchedEffect
            if(!state.loaded) return@LaunchedEffect
            if(state.account?.key==target.first) {
                stack.clear();stack.add(ChatsRoute);stack.add(ConversationRoute(target.second))
            }
            container.notificationTarget.value=null
        }
        CompositionLocalProvider(
            LocalConnectionIndicatorEnabled provides (state.account!=null),
            LocalConnectionUnavailable provides (state.account!=null && !state.online),
        ) {
        KukuTheme(dark) {
            if(state.account!=null) IncomingShareDialog(opened={ chatId ->
                stack.clear(); stack.add(ChatsRoute); stack.add(ConversationRoute(chatId))
            })
            val importError by container.shareImportError.collectAsStateWithLifecycle()
            if(importError!=null) AlertDialog(onDismissRequest={container.shareImportError.value=null},
                text={Text(stringResource(Res.string.share_import_failed))},confirmButton={TextButton(onClick={container.shareImportError.value=null}) {Text(stringResource(Res.string.cancel_share))}})
            AppDrawer(state.account!=null,stack.lastOrNull(),navigate={ route ->
                stack.clear();stack.add(ChatsRoute);if(route!=ChatsRoute) stack.add(route)
            }) {
            Surface(Modifier.fillMaxSize()) {
                NavDisplay(
                    backStack = stack, onBack = { popIfNotRoot(stack) },
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator()),
                    entryProvider = entryProvider {
                        entry<ServerRoute> { ServerScreen() { host, port -> stack.add(LoginRoute(host, port)) } }
                        entry<LoginRoute> { route -> LoginScreen(route.host, route.port) { popIfNotRoot(stack) } }
                        entry<ChatsRoute> {
                            if (state.account != null) ChatsScreen(
                                onOpen = { stack.add(ConversationRoute(it)) },
                                onNew = { stack.add(NewChatRoute) },
                                onInfo = { stack.add(GroupRoute(it)) })
                        }
                        entry<NewChatRoute> {
                            DirectoryScreen({ popIfNotRoot(stack) }, opened = {
                                popIfNotRoot(stack); stack.add(ConversationRoute(it))
                            })
                        }
                        entry<AboutRoute> { AboutScreen {popIfNotRoot(stack)} }
                        entry<AccountRoute> { AccountScreen({ popIfNotRoot(stack) }) { stack.add(AdminRoute) } }
                        entry<AdminRoute> { AdminScreen(back={ popIfNotRoot(stack) },security={ stack.add(AccountRoute) }) }
                        entry<GroupRoute> { route -> state.account?.let { account ->
                            GroupScreen(account.key,route.chatId,{popIfNotRoot(stack)},invite={ bots -> stack.add(InviteRoute(route.chatId,bots)) },openChat={ chatId -> stack.add(ConversationRoute(chatId)) })
                        } }
                        entry<InviteRoute> { route ->
                            DirectoryScreen({popIfNotRoot(stack)}, {}, route.chatId, route.bots)
                        }
                        entry<ConversationRoute> { route ->
                            state.account?.let { account ->
                                ConversationScreen(account.key, route.chatId, {popIfNotRoot(stack)}) {stack.add(GroupRoute(route.chatId))}
                            }
                        }
                    },
                )
            }
        }
    }
    }
    }
}

