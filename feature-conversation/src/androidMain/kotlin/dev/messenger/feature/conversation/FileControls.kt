package dev.messenger.feature.conversation
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.core.ui.*

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import dev.messenger.domain.FileStorage
import dev.messenger.domain.model.TransferSnapshot
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import kotlin.uuid.Uuid

@Composable actual fun rememberFilePicker(model: ChatViewModel): () -> Unit = rememberAttachmentMenu(model)

@Composable actual fun rememberFileOpener(model: ChatViewModel): (TransferSnapshot)->Unit {
    val context = LocalContext.current
    return { job ->
        try {
            require(job.state=="READY")
            val uri=FileProvider.getUriForFile(context,context.packageName+".attachments",File(model.transfers.files.path(job.accountId,job.jobId)),job.filename)
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,job.mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch(_: Exception) { model.reportError("FILE_OPEN_FAILED") }
    }
}
@Composable actual fun rememberShareLauncher(model:ChatViewModel):(ShareBundle)->Unit {
    val context=LocalContext.current
    return { bundle ->
        try {
            val uris=bundle.files.map { job ->
                val file=File(model.transfers.files.path(job.accountId,job.jobId))
                require(file.isFile && file.length()==job.size)
                FileProvider.getUriForFile(context,context.packageName+".attachments",file,job.filename)
            }
            val intent=Intent(if(uris.size>1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND)
            intent.type=if(uris.isEmpty()) "text/plain" else bundle.files.map { it.mime }.distinct().singleOrNull() ?: "*/*"
            if(bundle.text.isNotBlank()) intent.putExtra(Intent.EXTRA_TEXT,bundle.text)
            if(uris.size==1) intent.putExtra(Intent.EXTRA_STREAM,uris.single())
            else if(uris.isNotEmpty()) intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(uris))
            if(uris.isNotEmpty()) {
                intent.clipData=android.content.ClipData.newUri(context.contentResolver,"attachments",uris.first()).apply {
                    uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent,null))
        } catch(_:Exception) { model.reportError("SHARE_FILES_UNAVAILABLE") }
    }
}
