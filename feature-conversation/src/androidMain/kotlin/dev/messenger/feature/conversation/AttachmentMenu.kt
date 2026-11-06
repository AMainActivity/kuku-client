package dev.messenger.feature.conversation
import dev.messenger.domain.*
import dev.messenger.domain.model.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.messenger.core.ui.rememberManagedFilePicker
import dev.messenger.domain.FileStorage
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.*

internal enum class CaptureKind { PHOTO, VIDEO, AUDIO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun rememberAttachmentMenu(model:ChatViewModel):()->Unit {
    var menu by remember {mutableStateOf(false)}
    var capture by remember {mutableStateOf<CaptureKind?>(null)}
    val selected by model.selectedFiles.collectAsStateWithLifecycle()
    val jobs by model.files.collectAsStateWithLifecycle()
    val available=(MAX_ATTACHMENTS-selected.size).coerceAtLeast(0)
    val document=rememberManagedFilePicker(model.accountKey,model.transfers.files,available,FileStorage.MAX_BYTES,
        listOf("*/*"),model::picked,{model.reportError(it)})
    val photo=rememberManagedFilePicker(model.accountKey,model.transfers.files,available,FileStorage.MAX_BYTES,
        listOf("image/*"),model::picked,{model.reportError(it)})
    val video=rememberManagedFilePicker(model.accountKey,model.transfers.files,available,FileStorage.MAX_BYTES,
        listOf("video/*"),model::picked,{model.reportError(it)})
    val audio=rememberManagedFilePicker(model.accountKey,model.transfers.files,available,FileStorage.MAX_BYTES,
        listOf("audio/*"),model::picked,{model.reportError(it)})
    if(menu) ModalBottomSheet(onDismissRequest={menu=false}) {
        Column(Modifier.fillMaxWidth().heightIn(max=440.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(stringResource(Res.string.attach_file),style=MaterialTheme.typography.titleLarge)
            selected.forEach {id ->
                val job=jobs.firstOrNull {it.jobId==id}
                if(job!=null) Row(verticalAlignment=Alignment.CenterVertically) {
                    Text(job.filename,Modifier.weight(1f),maxLines=1)
                    TextButton(onClick={model.transferAction(job,"cancel")}) {Text(stringResource(Res.string.capture_remove))}
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
                AttachmentChoice(Res.drawable.mui_document,Res.string.capture_document,available>0) {menu=false;document()}
                AttachmentChoice(Res.drawable.mui_photo,Res.string.capture_photo,available>0) {menu=false;capture=CaptureKind.PHOTO}
                AttachmentChoice(Res.drawable.mui_video,Res.string.capture_video,available>0) {menu=false;capture=CaptureKind.VIDEO}
                AttachmentChoice(Res.drawable.mui_audio,Res.string.capture_audio,available>0) {menu=false;capture=CaptureKind.AUDIO}
            }
            if(available==0) Text(stringResource(Res.string.capture_limit))
        }
    }
    capture?.let {kind ->
        CaptureDialog(model,kind,close={capture=null},chooseExisting={selectedKind ->
            capture=null
            when(selectedKind) {CaptureKind.PHOTO->photo();CaptureKind.VIDEO->video();CaptureKind.AUDIO->audio()}
        })
    }
    return {menu=true}
}
private const val MAX_ATTACHMENTS=10

@Composable private fun AttachmentChoice(icon:DrawableResource,label:StringResource,enabled:Boolean,click:()->Unit) {
    Column(Modifier.widthIn(min=64.dp).clickable(enabled=enabled,onClick=click).padding(vertical=20.dp,horizontal=4.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(icon),null,Modifier.size(28.dp),
            tint=MaterialTheme.colorScheme.primary.copy(alpha=if(enabled) 1f else 0.38f))
        Text(stringResource(label),style=MaterialTheme.typography.labelMedium)
    }
}
