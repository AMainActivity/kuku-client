package dev.messenger.feature.profile
import dev.messenger.domain.*
import dev.messenger.domain.model.*

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.koin.compose.koinInject
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable actual fun PlatformSettings() {
    val background=koinInject<BackgroundConnection>()
    val context=LocalContext.current
    val prefs=remember { context.getSharedPreferences("background",0) }
    val service by background.state.collectAsState()
    var autostart by remember { mutableStateOf(prefs.getBoolean("autostart",false)) }
    var notifications by remember { mutableStateOf(background.notificationsEnabled()) }
    var sound by remember { mutableStateOf(background.messageSound()) }
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        fun refresh() { notifications=background.notificationsEnabled() }
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer);refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    Text(stringResource(Res.string.message_sound),style=MaterialTheme.typography.titleMedium)
    MessageSound.entries.forEach { choice ->
        Row(Modifier.fillMaxWidth().selectable(selected=sound==choice,role=androidx.compose.ui.semantics.Role.RadioButton,
            onClick={background.selectMessageSound(choice);sound=choice}),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected=sound==choice,onClick=null)
            Text(stringResource(when(choice) {
                MessageSound.CUCKOO->Res.string.message_sound_cuckoo
                MessageSound.SYSTEM->Res.string.message_sound_system
                MessageSound.SILENT->Res.string.message_sound_silent
            }))
        }
    }
    Text(stringResource(Res.string.message_sound_system_hint))
    Text(stringResource(Res.string.background_title),style=MaterialTheme.typography.titleMedium)
    Text(stringResource(Res.string.background_explanation))
    Text(stringResource(when(service.phase) {
        ServicePhase.STOPPED -> Res.string.service_stopped
        ServicePhase.STARTING -> Res.string.service_starting
        ServicePhase.RUNNING -> Res.string.service_running
        ServicePhase.STOPPING -> Res.string.service_stopping
        ServicePhase.FAILED -> Res.string.background_denied
    }))
    if(service.phase==ServicePhase.FAILED) TextButton(onClick={background.retry()}) {Text(stringResource(Res.string.retry))}
    Row { Checkbox(autostart,{autostart=it;prefs.edit().putBoolean("autostart",it).apply()});Text(stringResource(Res.string.background_autostart)) }
    if(!notifications) Text(stringResource(Res.string.service_notifications_disabled))
    TextButton(onClick={context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}) {
        Text(stringResource(Res.string.notification_settings))
    }
}
