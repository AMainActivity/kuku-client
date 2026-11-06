package dev.messenger.feature.profile
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
fun AccountScreen(back: ()->Unit, admin: ()->Unit) {
    val model=koinViewModel<AccountViewModel>()
    val uiSettings=org.koin.compose.koinInject<UiSettings>()
    val theme by uiSettings.theme.collectAsStateWithLifecycle()
    val me by model.me.collectAsStateWithLifecycle()
    val avatarImage by model.avatarImage.collectAsStateWithLifecycle()
    val avatarJobs by model.avatarJobs.collectAsStateWithLifecycle()
    val pickAvatar=rememberManagedFilePicker(model.accountKey.orEmpty(),model.transfers.files,1,5L*1024*1024,listOf("image/*"),model::avatarPicked,{model.reportError(it)})
    val devices by model.devices.collectAsStateWithLifecycle()
    val enrollment by model.enrollment.collectAsStateWithLifecycle()
    val change by model.emailChange.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    var name by rememberSaveable(me?.version) { mutableStateOf(me?.displayName.orEmpty()) }
    var email by rememberSaveable { mutableStateOf("") }
    var mfa by remember { mutableStateOf("") }
    var oldCode by remember { mutableStateOf("") }
    var newCode by remember { mutableStateOf("") }
    var revoke by remember { mutableStateOf<DeviceSession?>(null) }
    ActionPage(Res.string.settings,model,back) {
        SectionCard(stringResource(Res.string.appearance)) {
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("system","light","dark").forEach { choice ->
                FilterChip(selected=theme==choice,onClick={uiSettings.selectTheme(choice)},label={Text(stringResource(when(choice) {
                    "dark" -> Res.string.theme_dark
                    "light" -> Res.string.theme_light
                    else -> Res.string.theme_system
                }))})
            }
        }
        Action(Res.string.clear_cache,!busy,model::clearCache)
        Text(stringResource(Res.string.cache_explanation))

        }
        SectionCard { PlatformSettings() }
        SectionCard(stringResource(Res.string.profile_loading)) {
        AvatarImage(avatarImage,me?.displayName.orEmpty())
        Action(Res.string.change_avatar,!busy && me!=null,pickAvatar)
        if(me?.avatarId!=null) Action(Res.string.remove_avatar,!busy) {model.avatar(null)}
        AvatarUploadActions(avatarJobs,me?.avatarId,busy,model::avatar,model::avatarTransfer)
        Text(me?.email.orEmpty())
        me?.let { Text(role(it.role)) }
        Field(name,{name=it},Res.string.display_name)
        PrimaryAction(Res.string.save,!busy && name.isNotBlank() && me!=null) { model.profile(name) }
        }
        SectionCard(stringResource(Res.string.security)) {
        if(me?.mfaEnabled==false) PrimaryAction(Res.string.setup_mfa,!busy,model::setupMfa)
        enrollment?.let {
            Text(stringResource(Res.string.mfa_setup_instructions))
            SelectionContainer { Text(it.secret) }
        }
        if(enrollment!=null || me?.mfaEnabled==true) Field(mfa,{mfa=it.filter(Char::isDigit).take(6)},Res.string.authenticator_code)
        if(enrollment!=null) PrimaryAction(Res.string.confirm_mfa,!busy && mfa.length==6) { model.confirmMfa(mfa); mfa="" }
        if(me?.mfaEnabled==true) PrimaryAction(Res.string.verify_admin,!busy && mfa.length==6) { model.stepUp(mfa); mfa="" }
        if(me?.role in setOf("owner","admin")) Action(Res.string.administration,!busy,admin)
        }
        SectionCard(stringResource(Res.string.change_email)) {
        Field(email,{email=it},Res.string.email)
        PrimaryAction(Res.string.send_codes,!busy && email.isNotBlank()) { model.changeEmail(email) }
        if(change!=null) {
            Field(oldCode,{oldCode=it.filter(Char::isDigit).take(8)},Res.string.old_email_code)
            Field(newCode,{newCode=it.filter(Char::isDigit).take(8)},Res.string.new_email_code)
            PrimaryAction(Res.string.confirm_email,!busy && oldCode.length==8 && newCode.length==8) {
                model.verifyEmail(oldCode,newCode,mfa); oldCode=""; newCode=""; mfa=""
            }
        }
        }
        SectionCard(stringResource(Res.string.devices)) {
        devices.forEach { device ->
            Text(device.deviceName)
            Text(device.createdAt,style=MaterialTheme.typography.labelSmall)
            if(!device.revoked) Action(Res.string.revoke_session,!busy) { revoke=device }
        }
        }
    }
    revoke?.let { device ->
        AlertDialog(onDismissRequest={revoke=null},title={Text(stringResource(Res.string.revoke_session))},
            text={Text(device.deviceName)},confirmButton={Action(Res.string.confirm) { revoke=null; model.revoke(device.sessionId) }},
            dismissButton={Action(Res.string.cancel) { revoke=null }})
    }
}


