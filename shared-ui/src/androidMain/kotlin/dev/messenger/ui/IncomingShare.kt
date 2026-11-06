package dev.messenger.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.content.Context
import dev.messenger.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileOutputStream
import kotlin.uuid.Uuid

private val importLock=Mutex()
/** Only content URIs granted by the sending app are read; never interpret external filesystem paths. */
fun AppContainer.receiveShare(context:Context,intent:Intent,id:String) {
    if(intent.action !in setOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)) return
    val appContext=context.applicationContext
    importScope.launch(Dispatchers.IO) {
        importLock.withLock {
            if(shares.contains(id)) return@withLock
            val copied=mutableListOf<String>()
            var committed=false
            try {
                val text=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
                @Suppress("DEPRECATION")
                val streams=if(intent.action==Intent.ACTION_SEND_MULTIPLE)
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                    else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                val uris=(if(streams.isNotEmpty()) streams else (0 until (intent.clipData?.itemCount ?: 0)).mapNotNull { intent.clipData?.getItemAt(it)?.uri }).distinct()
                require(text.length<=16000 && uris.size<=10)
                val resolver=appContext.contentResolver
                val entries=uris.map { uri ->
                    require(uri.scheme=="content" && uri.authority!=appContext.packageName+".attachments")
                    val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                        if(it.moveToFirst()) it.getString(0) else null
                    } ?: "attachment"
                    val jobId=Uuid.random().toString(); copied.add(jobId)
                    val mime=resolver.getType(uri) ?: "application/octet-stream"
                    var size=0L
                    requireNotNull(resolver.openInputStream(uri)).use { input ->
                        FileOutputStream(shares.files.path(ShareInboxRepository.STAGING_ACCOUNT,jobId)).use { output ->
                            val buffer=ByteArray(65536)
                            while(true) {
                                ensureActive()
                                val count=input.read(buffer); if(count<0) break
                                size+=count; require(size<=ManagedFiles.MAX_BYTES)
                                output.write(buffer,0,count)
                            }
                            output.fd.sync()
                        }
                    }
                    require(size>0)
                    SharedFile(jobId,name.take(255),mime.take(120),size,shares.files.hash(ShareInboxRepository.STAGING_ACCOUNT,jobId))
                }
                withContext(NonCancellable) { shares.record(id,text,entries); committed=true }
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(_:Exception) { shareImportError.value="FILE_IMPORT_FAILED" }
            finally { if(!committed) copied.forEach { shares.files.remove(ShareInboxRepository.STAGING_ACCOUNT,it) } }
        }
    }
}