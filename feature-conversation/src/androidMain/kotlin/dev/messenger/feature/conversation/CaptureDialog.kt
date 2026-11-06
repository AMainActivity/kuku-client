package dev.messenger.feature.conversation
import dev.messenger.domain.*
import dev.messenger.domain.model.*

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.camera.view.video.AudioConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.messenger.core.ui.AppToolbar
import dev.messenger.domain.FileStorage
import dev.messenger.ui.resources.*
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import java.io.File
import kotlin.uuid.Uuid

private const val AUDIO_BIT_RATE=128_000
private const val AUDIO_SAMPLE_RATE=44_100
private const val LEVEL_UPDATE_MILLIS=100L

internal data class CapturedFile(val id:String,val path:String,val name:String,val mime:String)

/** A take belongs to the capture screen until explicitly handed to the draft. */
internal class CaptureTake(private val files:FileStorage,private val accountKey:String,private val stage:(CapturedFile)->Unit) {
    var file by mutableStateOf<CapturedFile?>(null)
    var ready by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var failed by mutableStateOf(false)
    var closed=false
    private var retained=false
    fun allocate(kind:CaptureKind):CapturedFile {
        discard()
        val id=Uuid.random().toString()
        val extension=when(kind) {CaptureKind.PHOTO->"jpg";CaptureKind.VIDEO->"mp4";CaptureKind.AUDIO->"m4a"}
        val mime=when(kind) {CaptureKind.PHOTO->"image/jpeg";CaptureKind.VIDEO->"video/mp4";CaptureKind.AUDIO->"audio/mp4"}
        return CapturedFile(id,files.path(accountKey,id),"kuku-"+System.currentTimeMillis()+"."+extension,mime)
            .also {file=it;busy=true;failed=false}
    }
    fun complete(value:CapturedFile,success:Boolean) {
        if(retained) return
        if(closed || file?.id!=value.id) {File(value.path).delete();return}
        busy=false
        ready=success && File(value.path).length() in 1..FileStorage.MAX_BYTES
        failed=!ready
        if(!ready) {File(value.path).delete();file=null}
    }
    fun confirm() {
        val value=file ?: return
        if(!ready || busy || retained) return
        retained=true
        stage(value)
    }
    fun discard() {
        if(!retained) file?.let {File(it.path).delete()}
        file=null;ready=false;busy=false
    }
    fun close() {closed=true;discard()}
}

private class VoiceRecorder(private val context:Context) {
    private var recorder:MediaRecorder?=null
    fun start(file:File,onLimit:()->Unit) {
        val value=if(Build.VERSION.SDK_INT>=31) MediaRecorder(context) else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        recorder=value
        try {
            value.setAudioSource(MediaRecorder.AudioSource.MIC)
            value.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            value.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            value.setAudioEncodingBitRate(AUDIO_BIT_RATE)
            value.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            value.setMaxFileSize(FileStorage.MAX_BYTES)
            value.setOnInfoListener {_,what,_ -> if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED) onLimit()}
            value.setOutputFile(file.absolutePath)
            value.prepare()
            value.start()
        } catch(failure:Exception) {release();throw failure}
    }
    fun level():Float = try {(recorder?.maxAmplitude ?: 0)/32767f} catch(_:IllegalStateException) {0f}
    fun stop():Boolean {
        val value=recorder ?: return false
        return try {value.stop();true} catch(_:RuntimeException) {false} finally {release()}
    }
    private fun release() {recorder?.release();recorder=null}
}

@Composable internal fun CaptureDialog(model:ChatViewModel,initialKind:CaptureKind,close:()->Unit,chooseExisting:(CaptureKind)->Unit) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    var kind by remember {mutableStateOf(initialKind)}
    val take=remember(model) {CaptureTake(model.transfers.files,model.accountKey) {model.picked(it.id,it.name,it.mime)}}
    val voice=remember(context) {VoiceRecorder(context)}
    var voiceActive by remember {mutableStateOf(false)}
    var recording by remember {mutableStateOf<Recording?>(null)}
    var seconds by remember {mutableLongStateOf(0L)}
    var level by remember {mutableFloatStateOf(0f)}
    var permissionRevision by remember {mutableIntStateOf(0)}
    val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {permissionRevision++}
    fun granted(name:String)=ContextCompat.checkSelfPermission(context,name)==PackageManager.PERMISSION_GRANTED
    val required=if(kind==CaptureKind.AUDIO) Manifest.permission.RECORD_AUDIO else Manifest.permission.CAMERA
    val allowed=remember(kind,permissionRevision) {granted(required)}
    fun requestPermission() {
        permission.launch(if(kind==CaptureKind.VIDEO) arrayOf(Manifest.permission.CAMERA,Manifest.permission.RECORD_AUDIO) else arrayOf(required))
    }
    LaunchedEffect(kind) {if(!granted(required) || (kind==CaptureKind.VIDEO && !granted(Manifest.permission.RECORD_AUDIO))) requestPermission()}
    fun stopVoice() {
        if(!voiceActive) return
        voiceActive=false
        val file=take.file
        val valid=voice.stop()
        if(file!=null) take.complete(file,valid)
    }
    DisposableEffect(context) {
        AttachmentPlayback.pause()
        var host:Context?=context
        while(host is ContextWrapper && host !is Activity) host=host.baseContext
        val activity=host as? Activity
        val previous=activity?.requestedOrientation
        activity?.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose {if(previous!=null && activity!=null) activity.requestedOrientation=previous}
    }
    DisposableEffect(owner,take) {
        val observer=LifecycleEventObserver {_,event ->
            if(event==Lifecycle.Event.ON_STOP) {stopVoice();recording?.stop()}
            if(event==Lifecycle.Event.ON_RESUME) permissionRevision++
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            take.closed=true
            if(voiceActive) {voice.stop();voiceActive=false}
            recording?.close();recording=null
            take.close()
        }
    }
    LaunchedEffect(voiceActive) {
        if(voiceActive) {
            val started=SystemClock.elapsedRealtime()
            while(voiceActive) {
                seconds=(SystemClock.elapsedRealtime()-started)/1000
                level=voice.level()
                delay(LEVEL_UPDATE_MILLIS)
            }
        }
    }
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false,dismissOnClickOutside=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                AppToolbar(stringResource(when(kind) {CaptureKind.PHOTO->Res.string.capture_photo;CaptureKind.VIDEO->Res.string.capture_video;CaptureKind.AUDIO->Res.string.capture_audio}),back=close)
                if(!take.busy && !take.ready) TextButton(onClick={chooseExisting(kind)}) {Text(stringResource(Res.string.capture_existing))}
                if(take.failed) Text(stringResource(Res.string.capture_failed),color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(16.dp))
                when {
                    take.ready -> {
                        val file=take.file
                        if(file!=null) Box(Modifier.weight(1f).fillMaxWidth()) {
                            LocalMediaContent(file.path,file.mime,file.name,true,false,{},{})
                        }
                        Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceEvenly) {
                            OutlinedButton(onClick={take.discard()}) {Text(stringResource(Res.string.capture_retake))}
                            Button(onClick={take.confirm();close()}) {Text(stringResource(Res.string.capture_use))}
                        }
                    }
                    !allowed -> Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                        Text(stringResource(Res.string.capture_permission))
                        Button(onClick={requestPermission()}) {Text(stringResource(Res.string.capture_allow))}
                        TextButton(onClick={
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:"+context.packageName)))
                        }) {Text(stringResource(Res.string.capture_settings))}
                    }
                    kind==CaptureKind.AUDIO -> {
                        Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,
                            verticalArrangement=Arrangement.Center) {
                            Text((seconds/60).toString()+":"+(seconds%60).toString().padStart(2,'0'),style=MaterialTheme.typography.displayMedium)
                            if(voiceActive) LinearProgressIndicator(progress={level},modifier=Modifier.fillMaxWidth().padding(vertical=24.dp))
                            Button(onClick={
                                if(voiceActive) stopVoice()
                                else {
                                    val file=take.allocate(CaptureKind.AUDIO)
                                    try {seconds=0;voice.start(File(file.path),::stopVoice);voiceActive=true}
                                    catch(_:Exception) {take.complete(file,false)}
                                }
                            }) {Text(stringResource(if(voiceActive) Res.string.capture_stop else Res.string.capture_record))}
                        }
                    }
                    else -> CameraContent(kind,take,Modifier.weight(1f),seconds,recording,
                        setRecording={recording=it},setSeconds={seconds=it},
                        microphone=granted(Manifest.permission.RECORD_AUDIO),changeKind={kind=it})
                }
            }
        }
    }
}


@Composable private fun CameraContent(kind:CaptureKind,take:CaptureTake,modifier:Modifier,seconds:Long,
    recording:Recording?,setRecording:(Recording?)->Unit,setSeconds:(Long)->Unit,microphone:Boolean,changeKind:(CaptureKind)->Unit) {
    val context=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val controller=remember(context) {LifecycleCameraController(context)}
    val executor=remember(context) {ContextCompat.getMainExecutor(context)}
    var front by remember {mutableStateOf(false)}
    var bound by remember {mutableStateOf(false)}
    DisposableEffect(controller,owner,kind) {
        try {
            controller.setEnabledUseCases(if(kind==CaptureKind.PHOTO) CameraController.IMAGE_CAPTURE else CameraController.VIDEO_CAPTURE)
            controller.bindToLifecycle(owner)
            bound=true
        } catch(_:Exception) {take.failed=true;bound=false}
        onDispose {controller.unbind()}
    }
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            AndroidView(factory={PreviewView(it).apply {this.controller=controller}},modifier=Modifier.fillMaxSize())
        }
        if(kind==CaptureKind.VIDEO) Text(
            if(recording!=null) (seconds/60).toString()+":"+(seconds%60).toString().padStart(2,'0')
            else stringResource(if(microphone) Res.string.capture_video_sound else Res.string.capture_video_muted),
            modifier=Modifier.padding(horizontal=16.dp))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceEvenly) {
            TextButton(enabled=!take.busy,onClick={changeKind(if(kind==CaptureKind.PHOTO) CaptureKind.VIDEO else CaptureKind.PHOTO)}) {
                Text(stringResource(if(kind==CaptureKind.PHOTO) Res.string.capture_video else Res.string.capture_photo))
            }
            TextButton(enabled=!take.busy && bound,onClick={
                try {
                    val selector=if(front) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    if(controller.hasCamera(selector)) {controller.cameraSelector=selector;front=!front}
                } catch(_:Exception) {take.failed=true}
            }) {Text(stringResource(Res.string.capture_switch_camera))}
        }
        Button(modifier=Modifier.align(Alignment.CenterHorizontally).padding(16.dp),
            enabled=bound && (!take.busy || recording!=null),onClick={
                if(recording!=null) {recording.stop();return@Button}
                val file=take.allocate(kind)
                try {
                    if(kind==CaptureKind.PHOTO) {
                        controller.takePicture(ImageCapture.OutputFileOptions.Builder(File(file.path)).build(),executor,
                            object:ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(result:ImageCapture.OutputFileResults) {take.complete(file,true)}
                                override fun onError(exception:ImageCaptureException) {take.complete(file,false)}
                            })
                    } else {
                        setSeconds(0)
                        setRecording(controller.startRecording(
                            FileOutputOptions.Builder(File(file.path)).setFileSizeLimit(FileStorage.MAX_BYTES).build(),
                            if(microphone) AudioConfig.create(true) else AudioConfig.AUDIO_DISABLED,executor) {event ->
                            when(event) {
                                is VideoRecordEvent.Status -> setSeconds(event.recordingStats.recordedDurationNanos/1_000_000_000)
                                is VideoRecordEvent.Finalize -> {
                                    setRecording(null)
                                    take.complete(file,!event.hasError() || event.error==VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED)
                                }
                            }
                        })
                    }
                } catch(_:Exception) {take.complete(file,false);setRecording(null)}
            }) {Text(stringResource(if(recording!=null) Res.string.capture_stop else if(kind==CaptureKind.PHOTO) Res.string.capture_shutter else Res.string.capture_record))}
    }
}
