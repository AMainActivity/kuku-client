@file:JvmName("AndroidAttachmentViewer")
package dev.messenger.feature.conversation
import dev.messenger.domain.*
import dev.messenger.domain.model.*

import android.net.Uri
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.PlayerControlView
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.compose.AsyncImage
import coil3.video.VideoFrameDecoder
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import java.io.File

// UI-thread only: starting any attachment pauses the previous player.
internal object AttachmentPlayback {
    fun pause() {current?.pause()}
    private var current: Player? = null
    fun playing(player: Player) { if(current !== player) current?.pause(); current=player }
    fun release(player: Player) { if(current === player) current=null }
}

@Composable internal actual fun LocalMediaContent(path:String,mime:String,name:String,active:Boolean,preview:Boolean,onZoom:(Boolean)->Unit,onTap:()->Unit) {
    if(audioMedia(mime) || (mime.startsWith("video/",true) && !preview)) {
        MediaPlayer(path,audioMedia(mime),active)
    } else {
        val context=LocalContext.current
        // Coil's application loader retains decoded previews across LazyColumn disposal/re-entry.
        val loader=SingletonImageLoader.get(context)
        val request=remember(context,path,mime) {
            ImageRequest.Builder(context).data(File(path)).apply {
                if(mime.startsWith("video/",true)) decoderFactory(VideoFrameDecoder.Factory())
            }.build()
        }
        var scale by remember(path) {mutableFloatStateOf(1f)}
        var offset by remember(path) {mutableStateOf(Offset.Zero)}
        var failed by remember(path) {mutableStateOf(false)}
        var attempt by remember(path) {mutableIntStateOf(0)}
        val zoomChanged by rememberUpdatedState(onZoom)
        val tapped by rememberUpdatedState(onTap)
        LaunchedEffect(active) {if(!active) {scale=1f;offset=Offset.Zero;zoomChanged(false)}}
        fun clamp(width:Int,height:Int) {
            val x=width*(scale-1f)/2f
            val y=height*(scale-1f)/2f
            offset=Offset(offset.x.coerceIn(-x,x),offset.y.coerceIn(-y,y))
        }
        val gestures=if(preview) Modifier else Modifier.pointerInput(path) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed=false)
                do {
                    val event=awaitPointerEvent()
                    val pressed=event.changes.count {it.pressed}
                    if(pressed>=2 || (pressed==1 && scale>1f)) {
                        if(pressed>=2) scale=(scale*event.calculateZoom()).coerceIn(1f,5f)
                        offset=if(scale>1f) offset+event.calculatePan() else Offset.Zero
                        clamp(size.width,size.height)
                        zoomChanged(scale>1f)
                        event.changes.forEach {it.consume()}
                    }
                } while(event.changes.any {it.pressed})
            }
        }.pointerInput(path) {
            detectTapGestures(onTap={tapped()},onDoubleTap={
                scale=if(scale>1f) 1f else 2.5f
                offset=Offset.Zero
                zoomChanged(scale>1f)
            })
        }
        Box(Modifier.fillMaxSize().then(gestures),contentAlignment=Alignment.Center) {
            key(attempt) {
                AsyncImage(model=request,imageLoader=loader,contentDescription=name,contentScale=ContentScale.Fit,
                    onError={failed=true},onSuccess={failed=false},
                    modifier=Modifier.fillMaxSize().graphicsLayer {scaleX=scale;scaleY=scale;translationX=offset.x;translationY=offset.y})
            }
            if(failed) Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.media_open_failed))
                TextButton(onClick={failed=false;attempt++}) {Text(stringResource(Res.string.retry))}
            } else if(preview && mime.startsWith("video/",true)) Text("▶",style=MaterialTheme.typography.headlineLarge)
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun MediaPlayer(path:String,audio:Boolean,active:Boolean) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var failed by remember(path) {mutableStateOf(false)}
    val player=remember(context,path) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT,true)
            setHandleAudioBecomingNoisy(true)
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            prepare()
        }
    }
    DisposableEffect(player,lifecycle) {
        val listener=object:Player.Listener {
            override fun onIsPlayingChanged(isPlaying:Boolean) {if(isPlaying) AttachmentPlayback.playing(player)}
            override fun onPlayerError(error:PlaybackException) {failed=true}
        }
        val observer=LifecycleEventObserver {_,event -> if(event==Lifecycle.Event.ON_STOP) player.pause()}
        player.addListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener)
            AttachmentPlayback.release(player)
            player.release()
        }
    }
    LaunchedEffect(active) {if(!active) player.pause()}
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
        if(audio) AndroidView(factory={PlayerControlView(it).apply {this.player=player;showTimeoutMs=0}},modifier=Modifier.fillMaxSize())
        else AndroidView(factory={PlayerView(it).apply {this.player=player;useController=true}},modifier=Modifier.fillMaxSize())
        if(failed) Surface {
            Column(horizontalAlignment=Alignment.CenterHorizontally) {
                Text(stringResource(Res.string.media_open_failed))
                TextButton(onClick={failed=false;player.prepare()}) {Text(stringResource(Res.string.retry))}
            }
        }
    }
}
