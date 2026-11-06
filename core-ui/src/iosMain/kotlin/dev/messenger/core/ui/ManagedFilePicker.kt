@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.messenger.core.ui
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.core.ui.*

import androidx.compose.runtime.*
import androidx.compose.ui.interop.LocalUIViewController
import dev.messenger.domain.model.TransferSnapshot
import dev.messenger.domain.FileStorage
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.*
import platform.darwin.NSObject
import kotlin.uuid.Uuid

@Composable actual fun rememberManagedFilePicker(accountKey:String, files:FileStorage, maxFiles:Int, maxBytes:Long, mimeTypes:List<String>, onPicked:(String,String,String)->Unit, onError:(String)->Unit): () -> Unit {
    val controller = LocalUIViewController.current
    val scope = rememberCoroutineScope()
    val picked by rememberUpdatedState(onPicked)
    val failed by rememberUpdatedState(onError)
    val limit by rememberUpdatedState(maxFiles)
    val delegate = remember(accountKey,files) {
        object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                scope.launch {
                    for (url in didPickDocumentsAtURLs.filterIsInstance<NSURL>().take(limit.coerceAtLeast(0))) {
                        val id = Uuid.random().toString()
                        try {
                            withContext(Dispatchers.IO) {
                                val scoped = url.startAccessingSecurityScopedResource()
                                try {
                                    val source = requireNotNull(url.path)
                                    val attrs = requireNotNull(NSFileManager.defaultManager.attributesOfItemAtPath(source,null))
                                    val size = (attrs[NSFileSize] as? NSNumber)?.longLongValue ?: error("Missing file size")
                                    require(size in 1..maxBytes)
                                    check(NSFileManager.defaultManager.copyItemAtPath(source,files.path(accountKey,id),null))
                                } finally { if(scoped) url.stopAccessingSecurityScopedResource() }
                            }
                            picked(id,url.lastPathComponent ?: "attachment","application/octet-stream")
                        } catch(cancelled: CancellationException) { files.remove(accountKey,id); throw cancelled }
                        catch(_: Exception) { files.remove(accountKey,id); failed("FILE_IMPORT_FAILED") }
                    }
                }
            }
        }
    }
    return {
        val picker = UIDocumentPickerViewController(forOpeningContentTypes=listOf(UTTypeData),asCopy=true)
        picker.allowsMultipleSelection = true
        picker.delegate = delegate
        controller.presentViewController(picker,true,null)
    }
}

