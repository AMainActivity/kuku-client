@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.messenger.feature.conversation
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

@Composable actual fun rememberFilePicker(model: ChatViewModel): () -> Unit =
    rememberManagedFilePicker(model.accountKey, model.transfers.files,
        (10-model.selectedFiles.value.size).coerceAtLeast(0), FileStorage.MAX_BYTES,
        listOf("*/*"), model::picked, { model.reportError(it) })

@Composable actual fun rememberFileOpener(model: ChatViewModel): (TransferSnapshot)->Unit {
    val controller=LocalUIViewController.current
    return { job ->
        if(job.state=="READY") {
            val url=NSURL.fileURLWithPath(model.transfers.files.path(job.accountId,job.jobId))
            val sheet=UIActivityViewController(activityItems=listOf(url),applicationActivities=null)
            sheet.popoverPresentationController?.sourceView=controller.view
            controller.presentViewController(sheet,true,null)
        }
    }
}


@Composable actual fun rememberShareLauncher(model:ChatViewModel):(ShareBundle)->Unit {
    val controller=LocalUIViewController.current
    return { bundle ->
        val items=mutableListOf<Any>()
        if(bundle.text.isNotBlank()) items.add(bundle.text)
        bundle.files.forEach { items.add(NSURL.fileURLWithPath(model.transfers.files.path(it.accountId,it.jobId))) }
        val sheet=UIActivityViewController(activityItems=items,applicationActivities=null)
        sheet.popoverPresentationController?.sourceView=controller.view
        controller.presentViewController(sheet,true,null)
    }
}
