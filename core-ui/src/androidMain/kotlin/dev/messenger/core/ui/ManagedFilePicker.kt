package dev.messenger.core.ui
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

@Composable actual fun rememberManagedFilePicker(accountKey:String, files:FileStorage, maxFiles:Int, maxBytes:Long, mimeTypes:List<String>, onPicked:(String,String,String)->Unit, onError:(String)->Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            for(uri in uris.take(maxFiles.coerceAtLeast(0))) {
                val jobId = Uuid.random().toString()
                try {
                    val metadata = withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        val name = resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                            if(it.moveToFirst()) it.getString(0) else null
                        } ?: "attachment"
                        val mime = resolver.getType(uri) ?: "application/octet-stream"
                        val path = files.path(accountKey,jobId)
                        requireNotNull(resolver.openInputStream(uri)).use { input ->
                            FileOutputStream(path).use { output ->
                                val buffer=ByteArray(65536); var total=0L
                                while(true) {
                                    ensureActive()
                                    val count=input.read(buffer); if(count<0) break
                                    total+=count; require(total<=maxBytes)
                                    output.write(buffer,0,count)
                                }
                                output.fd.sync()
                            }
                        }
                        name to mime
                    }
                    onPicked(jobId,metadata.first,metadata.second)
                } catch(cancelled: CancellationException) { files.remove(accountKey,jobId); throw cancelled }
                catch(_: Exception) { files.remove(accountKey,jobId); onError("FILE_IMPORT_FAILED") }
            }
        }
    }
    return { picker.launch(mimeTypes.toTypedArray()) }
}

