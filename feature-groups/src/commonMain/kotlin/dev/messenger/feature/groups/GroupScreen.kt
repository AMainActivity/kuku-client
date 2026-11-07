package dev.messenger.feature.groups
import dev.messenger.domain.*
import dev.messenger.domain.model.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.messenger.core.ui.*
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun GroupScreen(accountKey:String,chatId:String,back:()->Unit,invite:(Boolean)->Unit,openChat:(String)->Unit) {
    val model=koinViewModel<GroupViewModel>(key="group:$accountKey:$chatId",parameters={parametersOf(accountKey,chatId)})
    val chat by model.chat.collectAsStateWithLifecycle()
    val deletionSupported by model.deletionSupported.collectAsStateWithLifecycle()
    val deletion by model.deletion.collectAsStateWithLifecycle()
    val avatar by model.avatarImage.collectAsStateWithLifecycle()
    val jobs by model.avatarJobs.collectAsStateWithLifecycle()
    val members by model.members.collectAsStateWithLifecycle()
    val more by model.hasMore.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val connection by model.connection.collectAsStateWithLifecycle()
    val list=rememberLazyListState()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var editing by rememberSaveable {mutableStateOf(false)}
    var editTitle by rememberSaveable {mutableStateOf("")}
    var editDescription by rememberSaveable {mutableStateOf("")}
    var menu by remember {mutableStateOf(false)}
    var unban by remember { mutableStateOf(false) }
    var selectedId by remember {mutableStateOf<String?>(null)}
    var confirm by remember {mutableStateOf<Pair<String,()->Unit>?>(null)}
    var rankDialog by rememberSaveable {mutableStateOf(false)}
    var rank by rememberSaveable {mutableStateOf("")}
    val pickAvatar=rememberManagedFilePicker(accountKey,model.transfers.files,1,5L*1024*1024,listOf("image/*"),model::avatarPicked,{model.reportError(it)})
    val value=chat
    val group=value?.kind=="group"
    val manage=group && value?.membership?.status=="active" && value.membership.role in setOf("owner","admin")
    val name=value?.title?.ifBlank {value.peer?.displayName.orEmpty()}.orEmpty()
    LaunchedEffect(model,lifecycle,connection.online) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {if(connection.online) model.refresh();kotlinx.coroutines.awaitCancellation()}
    }
    LaunchedEffect(list,members.size,more,busy,error) {
        snapshotFlow {list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount}.collect { (last,total) ->
            if(group && more && !busy && error==null && last!=null && last>=total-4) model.refresh(true)
        }
    }
    AppPage(stringResource(if(group) Res.string.group_info else Res.string.person_info),back,actions={
        if(value!=null) Box {
            IconButton(onClick={menu=true}) {Icon(painterResource(Res.drawable.mui_more),stringResource(Res.string.more_actions))}
            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                if(manage) DropdownMenuItem(text={Text(stringResource(Res.string.unban_member))},onClick={menu=false;unban=true})
                if(manage) DropdownMenuItem(text={Text(stringResource(Res.string.edit))},onClick={menu=false;editTitle=value.title;editDescription=value.description;editing=true})
                if(group && value.membership.status=="active") {
                    val owner=value.membership.role=="owner"
                    val lastHuman=owner && !more && members.isNotEmpty() && members.none {it.kind!="bot" && it.status=="active" && it.userId!=accountKey.substringAfter(':')}
                    DropdownMenuItem(text={Text(stringResource(if(lastHuman && deletionSupported) Res.string.leave_delete_group else Res.string.leave_group))},enabled=!busy,onClick={
                        menu=false
                        if(owner && deletionSupported) model.prepareDeletion(true) else confirm=name to {model.leave(back)}
                    })
                    if(owner && deletionSupported) DropdownMenuItem(text={Text(stringResource(Res.string.delete_group_all),color=MaterialTheme.colorScheme.error)},enabled=!busy,onClick={menu=false;model.prepareDeletion(false)})
                }
                if(!group) value.peer?.let {peer ->
                    DropdownMenuItem(text={Text(stringResource(Res.string.block_user))},onClick={menu=false;confirm=peer.displayName to {model.block(peer.userId,true)}})
                    DropdownMenuItem(text={Text(stringResource(Res.string.unblock_user))},onClick={menu=false;model.block(peer.userId,false)})
                }
            }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize(),state=list,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item(key="header") {
                Column(Modifier.fillMaxWidth().padding(vertical=16.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    AvatarImage(avatar,name,104.dp)
                    Text(name,style=MaterialTheme.typography.headlineSmall,textAlign=TextAlign.Center)
                    if(!group) value?.peer?.handle?.let {Text("@$it",color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    val description=if(group) value?.description else value?.peer?.description
                    if(!description.isNullOrBlank()) Text(description,textAlign=TextAlign.Center,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(!group) FilledTonalButton(onClick=back) {Text(stringResource(Res.string.write_message))}
                }
            }
            if(error!=null) item(key="error") {ErrorNotice(error);TextButton(onClick={model.refresh()}) {Text(stringResource(Res.string.retry))}}
            if(value!=null) item(key="preferences") {
                SectionCard {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(stringResource(Res.string.mute_chat),Modifier.weight(1f))
                        Switch(value.preferences.muted,{model.preferences(value.preferences.pinned,it,value.preferences.pinRank)},enabled=!busy)
                    }
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Text(stringResource(Res.string.pin_chat),Modifier.weight(1f))
                        Switch(value.preferences.pinned,{model.preferences(it,value.preferences.muted,value.preferences.pinRank)},enabled=!busy)
                    }
                    if(value.preferences.pinned) TextButton(onClick={rank=value.preferences.pinRank.toString();rankDialog=true}) {Text(stringResource(Res.string.pin_order))}
                }
            }
            if(group) {
                item(key="members-title") {
                    Text(stringResource(Res.string.members),style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(vertical=8.dp))
                    if(value?.membership?.status=="invited") PrimaryAction(Res.string.accept_invitation,!busy,model::accept)
                    if(manage) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick={invite(false)},enabled=!busy) {Text(stringResource(Res.string.invite_members))}
                        TextButton(onClick={invite(true)},enabled=!busy) {Text(stringResource(Res.string.add_bot))}
                    }
                }
                items(members,key={it.userId}) {member ->
                    ListItem(headlineContent={Text(member.displayName)},supportingContent={Text(if(member.kind=="bot") stringResource(Res.string.bot) else role(member.role))},
                        leadingContent={AvatarImage(null,member.displayName,44.dp)},modifier=Modifier.clickable {selectedId=member.userId})
                }
            }
            if(busy) item(key="loading") {Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {CircularProgressIndicator(Modifier.size(24.dp))}}
        }
    }
    deletion?.let { request ->
        AlertDialog(onDismissRequest=model::cancelDeletion,
            title={Text(stringResource(if(request.onlyIfLastHuman) Res.string.leave_delete_group else Res.string.delete_group_all))},
            text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {Text(request.title);Text(stringResource(Res.string.delete_group_warning));ErrorNotice(error)}},
            confirmButton={Action(Res.string.confirm,!busy,model::confirmDeletion)},
            dismissButton={Action(Res.string.cancel,!busy,model::cancelDeletion)})
    }
    if (unban && manage) UnbanMemberDialog(accountKey, chatId) { unban=false }
    if(editing && value!=null && manage) AlertDialog(onDismissRequest={if(!busy) editing=false},title={Text(stringResource(Res.string.edit))},text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Field(editTitle,{editTitle=it},Res.string.group_name)
        Field(editDescription,{editDescription=it},Res.string.description)
        Action(Res.string.change_avatar,!busy,pickAvatar)
        if(value.avatarId!=null) Action(Res.string.remove_avatar,!busy) {model.avatar(null)}
        AvatarUploadActions(jobs,value.avatarId,busy,model::avatar,model::avatarTransfer)
    }},confirmButton={Action(Res.string.save,!busy && editTitle.isNotBlank()) {model.update(editTitle,editDescription);editing=false}},dismissButton={Action(Res.string.cancel,!busy) {editing=false}})
    if(rankDialog && value!=null) AlertDialog(onDismissRequest={rankDialog=false},title={Text(stringResource(Res.string.pin_order))},text={Field(rank,{rank=it.filter(Char::isDigit).take(4)},Res.string.pin_order)},confirmButton={Action(Res.string.save,!busy && rank.toIntOrNull()!=null) {model.preferences(true,value.preferences.muted,rank.toIntOrNull() ?: 0);rankDialog=false}},dismissButton={Action(Res.string.cancel) {rankDialog=false}})
    members.firstOrNull { it.userId == selectedId }?.let {member ->
        AlertDialog(onDismissRequest={selectedId=null},title={Text(member.displayName)},text={Column {
            Text(if(member.kind=="bot") stringResource(Res.string.bot) else role(member.role))
            if(member.userId!=accountKey.substringAfter(':')) Action(Res.string.write_message,!busy) {selectedId=null;model.direct(member.userId,openChat)}
            if(manage && member.userId!=accountKey.substringAfter(':')) {
                Action(Res.string.remove_member,!busy) {selectedId=null;confirm=member.displayName to {model.member(if(member.kind=="bot") MemberAction.REMOVE_BOT else MemberAction.REMOVE,member.userId)}}
                Action(Res.string.ban_member,!busy) {selectedId=null;confirm=member.displayName to {model.member(MemberAction.BAN,member.userId)}}
                if(value?.membership?.role=="owner" && member.kind!="bot") {
                    Action(if(member.role=="admin") Res.string.make_member else Res.string.make_admin,!busy) {model.role(member.userId,if(member.role=="admin") "member" else "admin");selectedId=null}
                    Action(Res.string.transfer_owner,!busy) {selectedId=null;confirm=member.displayName to {model.member(MemberAction.TRANSFER_OWNER,member.userId)}}
                }
            }
        }},confirmButton={Action(Res.string.back) {selectedId=null}})
    }
    confirm?.let {action -> AlertDialog(onDismissRequest={confirm=null},title={Text(stringResource(Res.string.confirm_action))},text={Text(action.first)},confirmButton={Action(Res.string.confirm,!busy) {confirm=null;action.second()}},dismissButton={Action(Res.string.cancel) {confirm=null}})}
}
