package dev.messenger.feature.chats
import dev.messenger.core.ui.*

import androidx.compose.foundation.combinedClickable
import org.jetbrains.compose.resources.painterResource
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
import dev.messenger.domain.OutboxState
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ChatsScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onInfo: (String) -> Unit) {
    val model = koinViewModel<ChatsViewModel>()
    val state by model.state.collectAsStateWithLifecycle()
    val chats by model.chats.collectAsStateWithLifecycle()
    val drafts by model.drafts.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val syncError by model.repository.error.collectAsStateWithLifecycle()
    AppPage(stringResource(Res.string.chats),actions={
        SearchAction(onNew)
    }) {
    Column(Modifier.fillMaxSize().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        ErrorNotice(error,onDismiss={model.reportError(null)})
        ErrorNotice(syncError)
        if (chats.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center) {
            EmptyState(stringResource(Res.string.no_chats_title),stringResource(Res.string.no_chats_hint)) {
                Button(onClick=onNew) {Text(stringResource(Res.string.start_chat))}
            }
        }
        LazyColumn(verticalArrangement=Arrangement.spacedBy(4.dp),contentPadding=PaddingValues(bottom=16.dp)) {
            items(chats, key = { it.chatId }) { chat ->
                val avatar by produceState<ByteArray?>(null,state.account?.key,chat.chatId,chat.avatarId,chat.peer?.avatarId,state.online) { value=model.avatar(chat) }
                var menuExpanded by remember(state.account?.key,chat.chatId) {mutableStateOf(false)}
                Box(Modifier.fillMaxWidth()) {
                Surface(shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer) {
                ListItem(
                    colors=ListItemDefaults.colors(containerColor=MaterialTheme.colorScheme.surfaceContainer),
                    leadingContent={AvatarImage(avatar,chat.title.ifBlank {chat.peer?.displayName.orEmpty()})},
                    headlineContent = { Text(chat.title.ifBlank { chat.peer?.displayName.orEmpty() },maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium) },
                    supportingContent = {
                        val draft=drafts[chat.chatId]
                        Text(if(draft!=null) stringResource(Res.string.draft_preview,draft.text) else chat.lastMessage?.plainTextOrNull().orEmpty(),maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    },
                    trailingContent = {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(chat.preferences.pinned) Icon(painterResource(Res.drawable.mui_pin),
                                stringResource(Res.string.chat_pinned),Modifier.size(16.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(chat.unreadCount>0) Badge {Text(chat.unreadCount.toString())}
                        }
                    },
                    modifier=Modifier.combinedClickable(
                        onClick={onOpen(chat.chatId)},onLongClick={menuExpanded=true},
                        onLongClickLabel=stringResource(Res.string.chat_actions)))
                }
                Box(Modifier.align(Alignment.CenterEnd)) {
                    DropdownMenu(expanded=menuExpanded,onDismissRequest={menuExpanded=false}) {
                        DropdownMenuItem(
                            text={Text(stringResource(if(chat.preferences.pinned) Res.string.unpin_chat else Res.string.pin_chat))},
                            leadingIcon={Icon(painterResource(Res.drawable.mui_pin),null,tint=MaterialTheme.colorScheme.primary)},
                            enabled=!busy && state.online,
                            onClick={menuExpanded=false;model.setPinned(chat.chatId,!chat.preferences.pinned)})
                        DropdownMenuItem(
                            text={Text(stringResource(Res.string.chat_info))},
                            leadingIcon={Icon(painterResource(Res.drawable.mui_info),null,tint=MaterialTheme.colorScheme.primary)},
                            onClick={menuExpanded=false;onInfo(chat.chatId)})
                    }
                }
                }
            }
        }
    }
    }
}
