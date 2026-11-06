package dev.messenger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import dev.messenger.core.ui.*
import dev.messenger.ui.resources.*
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable fun AppDrawer(authenticated:Boolean,current:NavKey?,navigate:(NavKey)->Unit,content:@Composable ()->Unit) {
    val model=koinViewModel<DrawerViewModel>()
    val profile by model.profile.collectAsStateWithLifecycle()
    val avatar by model.avatar.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val drawer=rememberDrawerState(DrawerValue.Closed)
    val scope=rememberCoroutineScope()
    var confirmLogout by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(authenticated) {if(!authenticated) {drawer.close();confirmLogout=false}}
    fun open(route:NavKey) {scope.launch {drawer.close();navigate(route)}}
    if(authenticated && confirmLogout) AlertDialog(onDismissRequest={if(!busy) confirmLogout=false},
        title={Text(stringResource(Res.string.logout))},text={Column {Text(stringResource(Res.string.logout_warning));ErrorNotice(error)}},
        confirmButton={TextButton(enabled=!busy,onClick=model::logout) {Text(stringResource(Res.string.logout))}},
        dismissButton={TextButton(enabled=!busy,onClick={confirmLogout=false}) {Text(stringResource(Res.string.cancel))}})
    ModalNavigationDrawer(drawerState=drawer,gesturesEnabled=authenticated,drawerContent={
        ModalDrawerSheet {
            Column(Modifier.fillMaxHeight().widthIn(max=320.dp).navigationBarsPadding()) {
                Surface(color=MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().clickable {open(AccountRoute)}.padding(24.dp)) {
                    AvatarImage(avatar,profile?.displayName ?: stringResource(Res.string.app_name))
                    Spacer(Modifier.height(12.dp))
                    Text(profile?.displayName ?: stringResource(Res.string.profile_loading),style=MaterialTheme.typography.titleLarge)
                }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
                    NavigationDrawerItem(icon={Icon(painterResource(Res.drawable.mui_info),null)},label={Text(stringResource(Res.string.chats))},selected=current==ChatsRoute,onClick={open(ChatsRoute)})
                    NavigationDrawerItem(icon={Icon(painterResource(Res.drawable.mui_search),null)},label={Text(stringResource(Res.string.new_chat))},selected=current==NewChatRoute,onClick={open(NewChatRoute)})
                    NavigationDrawerItem(icon={Icon(painterResource(Res.drawable.mui_settings),null)},label={Text(stringResource(Res.string.settings))},selected=current==AccountRoute,onClick={open(AccountRoute)})
                    if(profile?.role in setOf("owner","admin")) NavigationDrawerItem(icon={Icon(painterResource(Res.drawable.mui_settings),null)},label={Text(stringResource(Res.string.administration))},selected=current==AdminRoute,onClick={open(AdminRoute)})
                    NavigationDrawerItem(icon={Icon(painterResource(Res.drawable.mui_info),null)},label={Text(stringResource(Res.string.about_app))},selected=current==AboutRoute,onClick={open(AboutRoute)})
                    HorizontalDivider(Modifier.padding(vertical=12.dp))
                    NavigationDrawerItem(icon={Icon(painterResource(Res.drawable.mui_logout),null)},label={Text(stringResource(Res.string.logout))},selected=false,onClick={confirmLogout=true})
                }
                Text(stringResource(Res.string.app_version),style=MaterialTheme.typography.labelMedium,modifier=Modifier.padding(24.dp))
            }
        }
    }) {
        CompositionLocalProvider(LocalOpenAppDrawer provides if(authenticated) ({scope.launch {drawer.open()};Unit}) else null) {content()}
    }
}