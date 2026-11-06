package dev.messenger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Local session restoration only; connectivity must not gate access to cached chats. */
@Composable
internal fun SessionStartup(failed:Boolean,retry:()->Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(32.dp),horizontalAlignment=Alignment.CenterHorizontally,
            verticalArrangement=Arrangement.spacedBy(20.dp,Alignment.CenterVertically)) {
            Text(stringResource(Res.string.app_name),style=MaterialTheme.typography.headlineMedium)
            if(failed) {
                Text(stringResource(Res.string.session_restore_failed))
                Button(onClick=retry) {Text(stringResource(Res.string.retry))}
            } else {
                CircularProgressIndicator(Modifier.size(32.dp))
                Text(stringResource(Res.string.session_restoring),style=MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
