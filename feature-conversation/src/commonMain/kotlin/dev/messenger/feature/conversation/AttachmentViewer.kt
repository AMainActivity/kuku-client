package dev.messenger.feature.conversation
import dev.messenger.domain.*
import dev.messenger.domain.model.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.messenger.core.ui.*
import dev.messenger.domain.model.Attachment
import dev.messenger.ui.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

internal fun galleryMedia(mime:String)=mime.startsWith("image/",true) || mime.startsWith("video/",true)
internal fun audioMedia(mime:String)=mime.startsWith("audio/",true)
internal data class GalleryRequest(val attachments:List<Attachment>,val startId:String)

@Composable internal fun rememberAvailableFile(job:TransferSnapshot?,files:FileStorage):State<String?> = key(job?.accountId,job?.jobId,job?.state,job?.size) { produceState<String?>(null) {
    value=withContext(Dispatchers.IO) {
        if(job?.state=="READY" && files.size(job.accountId,job.jobId)==job.size) files.path(job.accountId,job.jobId) else null
    }
} }

private val MEDIA_PREVIEW_HEIGHT=190.dp
private val AUDIO_PREVIEW_HEIGHT=92.dp

@Composable internal fun AttachmentTile(attachment:Attachment,job:TransferSnapshot?,files:FileStorage,playbackAllowed:Boolean,open:()->Unit,retry:()->Unit) {
    val path by rememberAvailableFile(job,files)
    val media=galleryMedia(attachment.mime)
    val audio=audioMedia(attachment.mime)
    val failed=job?.state in setOf("FAILED","PAUSED","CANCELLED")
    val downloading=job!=null && job.state in setOf("QUEUED","RUNNING")
    if(media || audio) {
        // Keep exactly the same footprint during disk lookup, decoding, download and retry.
        // LazyColumn must not re-anchor the viewport when an entering row becomes ready.
        Surface(Modifier.widthIn(min=180.dp,max=300.dp).fillMaxWidth()
            .height(if(media) MEDIA_PREVIEW_HEIGHT else AUDIO_PREVIEW_HEIGHT),
            color=MaterialTheme.colorScheme.surfaceContainerHighest,shape=MaterialTheme.shapes.small) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                val available=path
                if(available!=null) {
                    LocalMediaContent(available,attachment.mime,attachment.filename,active=playbackAllowed,preview=true,onZoom={},onTap=open)
                    if(media) Box(Modifier.fillMaxSize().clickable(onClick=open))
                } else {
                    Column(Modifier.padding(8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        TextButton(onClick=if(failed) retry else open) {
                            Text(attachment.filename,maxLines=2,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        if(failed) TextButton(onClick=retry) {Text(stringResource(Res.string.retry))}
                        else if(job?.state=="READY") CircularProgressIndicator(Modifier.size(20.dp))
                    }
                }
                if(downloading && job!=null) LinearProgressIndicator(
                    progress={if(job.size>0) (job.progress.toFloat()/job.size).coerceIn(0f,1f) else 0f},
                    modifier=Modifier.fillMaxWidth().align(Alignment.BottomCenter))
            }
        }
    } else Column(Modifier.widthIn(min=180.dp,max=300.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        TextButton(onClick=if(failed) retry else open) {Text(attachment.filename,maxLines=2)}
        if(downloading && job!=null) LinearProgressIndicator(
            progress={if(job.size>0) (job.progress.toFloat()/job.size).coerceIn(0f,1f) else 0f},
            modifier=Modifier.fillMaxWidth())
        if(failed) TextButton(onClick=retry) {Text(stringResource(Res.string.retry))}
    }
}
@Composable internal fun AttachmentViewer(request:GalleryRequest,jobs:List<TransferSnapshot>,files:FileStorage,download:(Attachment)->Unit,retry:(TransferSnapshot)->Unit,share:(TransferSnapshot)->Unit,close:()->Unit) {
    val media=remember(request) {request.attachments.filter {galleryMedia(it.mime)}}
    if(media.isEmpty()) return
    val pager=rememberPagerState(initialPage=media.indexOfFirst {it.attachmentId==request.startId}.coerceAtLeast(0)) {media.size}
    var zoomed by remember {mutableStateOf(false)}
    var chrome by remember {mutableStateOf(true)}
    LaunchedEffect(pager.currentPage) {zoomed=false;chrome=true}
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                HorizontalPager(state=pager,userScrollEnabled=!zoomed,modifier=Modifier.fillMaxSize(),key={media[it].attachmentId}) {index ->
                    val attachment=media[index]
                    val job=jobs.firstOrNull {it.direction=="DOWNLOAD" && it.remoteId==attachment.attachmentId}
                    val path by rememberAvailableFile(job,files)
                    val current=pager.currentPage==index
                    LaunchedEffect(attachment.attachmentId,current) {if(current && (job==null || job.state=="READY")) download(attachment)}
                    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                        if(path!=null) LocalMediaContent(requireNotNull(path),attachment.mime,attachment.filename,current,false,{if(current) zoomed=it},{chrome=!chrome})
                        else Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            if(job?.state in setOf("FAILED","PAUSED","CANCELLED")) {
                                Text(stringResource(Res.string.media_download_failed))
                                Button(onClick={if(job!=null) retry(job) else download(attachment)}) {Text(stringResource(Res.string.retry))}
                            } else {
                                CircularProgressIndicator()
                                Text(stringResource(Res.string.media_loading))
                                if(job!=null && job.size>0) Text("${(100*job.progress/job.size).coerceIn(0,100)}%")
                            }
                        }
                    }
                }
                if(chrome) Column(Modifier.align(Alignment.TopCenter)) {
                    val current=media[pager.currentPage]
                    val job=jobs.firstOrNull {it.direction=="DOWNLOAD" && it.remoteId==current.attachmentId}
                    val path by rememberAvailableFile(job,files)
                    AppToolbar("${pager.currentPage+1} / ${media.size}",back=close,actions={
                        if(path!=null && job!=null) TextButton(onClick={share(job)}) {Text(stringResource(Res.string.share_file))}
                    })
                    Text(current.filename,modifier=Modifier.padding(horizontal=16.dp),maxLines=1)
                }
            }
        }
    }
}

@Composable internal expect fun LocalMediaContent(path:String,mime:String,name:String,active:Boolean,preview:Boolean,onZoom:(Boolean)->Unit,onTap:()->Unit)
