package dev.messenger.feature.chats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable fun IncomingShareDialog(opened:(String)->Unit,model:IncomingShareViewModel=koinViewModel()) {
    val pending by model.pending.collectAsStateWithLifecycle()
    val chats by model.chats.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val item=pending.firstOrNull() ?: return
    AlertDialog(onDismissRequest={},title={Text(stringResource(Res.string.share_destination))},text={
        Column {
            Text(stringResource(Res.string.share_draft_notice))
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            dev.messenger.core.ui.ErrorNotice(error)
            LazyColumn(Modifier.heightIn(max=360.dp)) {
                items(chats.filter { it.membership.status=="active" },key={it.chatId}) { chat ->
                    TextButton(enabled=!busy,onClick={model.accept(item.shareId,chat.chatId,opened)}) {
                        Text(chat.title.ifBlank { chat.peer?.displayName ?: chat.chatId })
                    }
                }
            }
        }
    },confirmButton={},dismissButton={TextButton(enabled=!busy,onClick={model.discard(item.shareId)}) {Text(stringResource(Res.string.cancel_share))}})
}