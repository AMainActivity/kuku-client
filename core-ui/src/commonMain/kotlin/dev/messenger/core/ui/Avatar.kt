package dev.messenger.core.ui
import dev.messenger.domain.*
import dev.messenger.domain.model.*

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.messenger.domain.model.TransferSnapshot
import dev.messenger.ui.resources.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.*

@OptIn(ExperimentalResourceApi::class)
@Composable fun AvatarImage(bytes:ByteArray?,name:String,size:androidx.compose.ui.unit.Dp=64.dp) {
    val bitmap by produceState<ImageBitmap?>(null,bytes) {
        value=if(bytes==null) null else withContext(Dispatchers.Default) {
            try { bytes.decodeToImageBitmap() } catch(_:Exception) { null }
        }
    }
    val image=bitmap
    if(image!=null) Image(image,null,Modifier.size(size).clip(CircleShape),contentScale=ContentScale.Crop)
    else Surface(Modifier.size(size),shape=CircleShape,color=MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment=androidx.compose.ui.Alignment.Center) {Text(name.take(1))}
    }
}
@Composable fun AvatarUploadActions(jobs:List<TransferSnapshot>,currentId:String?,busy:Boolean,
    apply:(String?)->Unit,transfer:(TransferSnapshot,Boolean)->Unit) {
    jobs.filter { it.state!="CANCELLED" && it.remoteId!=currentId }.forEach { job ->
        Text(job.filename+" "+job.progress+"/"+job.size)
        Row {
            val id=job.remoteId
            if(job.state=="READY" && id!=null) TextButton(onClick={apply(id)},enabled=!busy) {Text(stringResource(Res.string.apply_avatar))}
            if(job.state in setOf("FAILED","PAUSED")) TextButton(onClick={transfer(job,false)},enabled=!busy) {Text(stringResource(Res.string.retry))}
            TextButton(onClick={transfer(job,true)},enabled=!busy) {Text(stringResource(Res.string.cancel))}
        }
    }
}

