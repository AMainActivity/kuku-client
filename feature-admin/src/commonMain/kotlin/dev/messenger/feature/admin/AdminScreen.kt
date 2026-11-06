package dev.messenger.feature.admin
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.core.ui.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
@OptIn(ExperimentalMaterial3Api::class)
fun AdminScreen(back: ()->Unit,security:()->Unit) {
    val model=koinViewModel<AdminViewModel>()
    val users by model.users.collectAsStateWithLifecycle()
    val admissions by model.admissions.collectAsStateWithLifecycle()
    val bots by model.bots.collectAsStateWithLifecycle()
    val audit by model.audit.collectAsStateWithLifecycle()
    val more by model.more.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val confirmation by model.confirmation.collectAsStateWithLifecycle()
    val confirming by model.confirming.collectAsStateWithLifecycle()
    val confirmationError by model.confirmationError.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var handle by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var editingBot by remember { mutableStateOf<DirectoryPerson?>(null) }
    var banUser by remember { mutableStateOf<AdminUser?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    var until by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String,()->Unit>?>(null) }
    LaunchedEffect(tab) {model.load(tab,query)}
    ActionPage(Res.string.administration,model,back) {
        if(error == "MFA_REQUIRED") {
            SectionCard(stringResource(Res.string.security)) {
                Text(stringResource(Res.string.admin_security_description))
                Button(onClick=security) {Text(stringResource(Res.string.open_security))}
            }
            return@ActionPage
        }
        PrimaryScrollableTabRow(selectedTabIndex=tab,edgePadding=0.dp) {
            listOf(Res.string.admissions,Res.string.users,Res.string.bots,Res.string.audit).forEachIndexed { i,label ->
                Tab(selected=tab==i,enabled=!busy,onClick={tab=i},text={Text(stringResource(label))})
            }
        }
        SectionCard {
        Field(query,{query=it},Res.string.search)
        Action(Res.string.search,!busy) {model.load(tab,query)}
        }
        SectionCard {
        when(tab) {
            0 -> {
                Field(email,{email=it},Res.string.email)
                PrimaryAction(Res.string.allow_email,!busy && email.isNotBlank()) {model.admission(email,true)}
                admissions.forEach { item ->
                    Text(item.email)
                    Action(if(item.active) Res.string.revoke_admission else Res.string.allow_email,!busy) {
                        confirm=item.email to {model.admission(item.email,!item.active)}
                    }
                }
            }
            1 -> users.forEach { user ->
                HorizontalDivider()
                Text(user.displayName+" · "+user.email)
                Text(role(user.role))
                Action(Res.string.ban_user,!busy) {banUser=user; reason=""; until=""}
                Action(Res.string.unban_member,!busy) {model.userAction(UserAdminAction.UNBAN,user.userId)}
                Action(Res.string.revoke_all_sessions,!busy) {confirm=user.email to {model.userAction(UserAdminAction.REVOKE_SESSIONS,user.userId)}}
                Action(Res.string.deactivate_user,!busy) {confirm=user.email to {model.userAction(UserAdminAction.DEACTIVATE,user.userId)}}
                Row {
                    Action(Res.string.make_admin,!busy) {confirm=user.email to {model.role(user.userId,"admin")}}
                    Action(Res.string.make_member,!busy) {confirm=user.email to {model.role(user.userId,"user")}}
                    Action(Res.string.make_owner,!busy) {confirm=user.email to {model.role(user.userId,"owner")}}
                }
            }
            2 -> {
                Field(handle,{handle=it},Res.string.bot_handle)
                Field(name,{name=it},Res.string.display_name)
                Field(description,{description=it},Res.string.description)
                PrimaryAction(Res.string.save,!busy && name.isNotBlank() && handle.isNotBlank()) {
                    model.bot(handle,name,description,editingBot); editingBot=null
                }
                bots.forEach { bot ->
                    Text(bot.displayName+" @"+bot.handle.orEmpty())
                    Row {
                        Action(Res.string.edit,!busy) {editingBot=bot;handle=bot.handle.orEmpty();name=bot.displayName;description=bot.description}
                        Action(Res.string.disable_bot,!busy) {confirm=bot.displayName to {model.disableBot(bot.userId)}}
                    }
                }
            }
            else -> audit.forEach { item ->
                Text(item.action,style=MaterialTheme.typography.titleSmall)
                Text(item.createdAt+" · "+item.target)
            }
        }
        }
        if(more) Action(Res.string.load_more,!busy) {model.load(tab,query,true)}
    }
    if (confirmation) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = model::cancelConfirmation,
            title = { Text(stringResource(Res.string.verify_admin)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.admin_confirmation_description))
                    Field(code, { code = it.filter { char -> char in '0'..'9' }.take(6) }, Res.string.authenticator_code)
                    ErrorNotice(confirmationError)
                }
            },
            confirmButton = {
                Action(Res.string.confirm, !confirming && code.length == 6) {
                    model.confirmAccess(code)
                    code = ""
                }
            },
            dismissButton = { Action(Res.string.cancel, !confirming, model::cancelConfirmation) },
        )
    }
    banUser?.let { user ->
        AlertDialog(onDismissRequest={banUser=null},title={Text(stringResource(Res.string.ban_user))},
            text={Column {
                Text(user.email)
                Field(reason,{reason=it},Res.string.reason)
                Field(until,{until=it},Res.string.ban_until)
            }},
            confirmButton={Action(Res.string.confirm,reason.isNotBlank()) {banUser=null;model.ban(user.userId,reason,until.ifBlank{null})}},
            dismissButton={Action(Res.string.cancel) {banUser=null}})
    }
    confirm?.let { action ->
        AlertDialog(onDismissRequest={confirm=null},title={Text(stringResource(Res.string.confirm_action))},text={Text(action.first)},
            confirmButton={Action(Res.string.confirm) {confirm=null;action.second()}},
            dismissButton={Action(Res.string.cancel) {confirm=null}})
    }
}

