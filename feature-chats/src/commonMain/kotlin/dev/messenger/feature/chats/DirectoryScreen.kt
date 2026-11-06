package dev.messenger.feature.chats
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.core.ui.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun DirectoryScreen(back: ()->Unit, opened: (String)->Unit, inviteChatId: String? = null, bots: Boolean = false) {
    val model = koinViewModel<DirectoryViewModel>()
    val existingChats by model.existingChats.collectAsStateWithLifecycle()
    val people by model.people.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val more by model.hasMore.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var group by rememberSaveable { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val list=rememberLazyListState()
    var appliedQuery by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(query,inviteChatId,bots) {
        appliedQuery=null
        if(query.isNotBlank()) kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MILLIS)
        model.busy.first {!it}
        model.search(query,bots=bots)
        model.busy.first {!it}
        appliedQuery=query
    }
    LaunchedEffect(list,people.size,more,busy,error,query,appliedQuery) {
        snapshotFlow {list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount}.collect {(last,total) ->
            if(more && !busy && error==null && appliedQuery==query && last!=null && last>=total-4) model.search(query,true,bots)
        }
    }
    AppPage(stringResource(if(inviteChatId!=null) Res.string.invite_members else Res.string.new_chat),back) {
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp)) {


        Field(query,{query=it},Res.string.search_people)

        if(inviteChatId==null) {
            Row { Checkbox(group,{group=it; selected=emptySet()}); Text(stringResource(Res.string.create_group)) }
            if(group) Field(title,{title=it},Res.string.group_name)
        }
        ErrorNotice(error)
        if(error!=null) Action(Res.string.retry,!busy) {model.search(query,bots=bots)}
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f),state=list) {
            if(inviteChatId==null && !group && query.isNotBlank()) {
                val matches=existingChats.filter {it.title.contains(query,true) || it.peer?.displayName?.contains(query,true)==true}
                if(matches.isNotEmpty()) item(key="existing-title") {Text(stringResource(Res.string.chats),style=MaterialTheme.typography.titleMedium)}
                items(matches,key={"chat-${it.chatId}"}) {chat ->
                    ListItem(headlineContent={Text(chat.title.ifBlank {chat.peer?.displayName.orEmpty()})},modifier=Modifier.clickable {opened(chat.chatId)})
                }
            }
            items(people,key={it.userId}) { person ->
                val selectable = group || inviteChatId!=null
                ListItem(headlineContent={Text(person.displayName)},
                    supportingContent={Text(person.description)},
                    leadingContent={if(selectable) Checkbox(person.userId in selected,{ checked ->
                        selected=if(checked) selected+person.userId else selected-person.userId
                    }) else AvatarImage(null,person.displayName,44.dp)},
                    modifier=Modifier.clickable(enabled=!busy) {
                        if(selectable) selected=if(person.userId in selected) selected-person.userId else selected+person.userId
                        else model.direct(person.userId,opened)
                    })
            }

        }
        if(group && inviteChatId==null) PrimaryAction(Res.string.create_group,!busy && title.isNotBlank()) { model.group(title,selected,opened) }
        if(inviteChatId!=null) PrimaryAction(Res.string.invite_members,!busy && selected.isNotEmpty()) { model.invite(inviteChatId,selected,bots,back) }
    }
    }
}


private const val SEARCH_DEBOUNCE_MILLIS=300L

