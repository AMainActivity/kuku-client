package dev.messenger.runtime
import dev.messenger.core.runtime.R
import dev.messenger.domain.BackgroundConnection
import dev.messenger.domain.ServicePhase
import dev.messenger.domain.MessagingServiceState

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*


class AndroidBackgroundConnection(context:Context):BackgroundConnection {
    private val context=context.applicationContext
    override val state=AndroidRuntime.serviceState
    override fun retry()=AndroidRuntime.start(context)
    override fun notificationsEnabled()=AndroidRuntime.notificationsEnabled(context)
    override fun messageSound()=MessageNotifications.sound(context)
    override fun selectMessageSound(sound:dev.messenger.domain.MessageSound)=MessageNotifications.selectSound(context,sound)
    override fun visibleConversation(account:String?,chatId:String?) {
        MessageNotifications.visible.value=if(account!=null && chatId!=null) account to chatId else null
    }
}
object AndroidRuntime {
    var container: AppRuntime? = null
    private val mutableService=MutableStateFlow(MessagingServiceState())
    val serviceState=mutableService.asStateFlow()
    fun notificationsEnabled(context:Context):Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        return androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun running() { mutableService.update { it.copy(phase=ServicePhase.RUNNING) } }
    fun stopped() { mutableService.update { it.copy(phase=ServicePhase.STOPPED) } }
    fun start(context:Context):Boolean {
        if(mutableService.value.phase!=ServicePhase.RUNNING) mutableService.update { it.copy(phase=ServicePhase.STARTING) }
        return try {
            ContextCompat.startForegroundService(context,Intent(context,MessagingForegroundService::class.java)); true
        } catch(_:IllegalStateException) {
            mutableService.update { it.copy(phase=ServicePhase.FAILED) };false
        } catch(_:SecurityException) {
            mutableService.update { it.copy(phase=ServicePhase.FAILED) };false
        }
    }

}
private const val CHANNEL = "messenger.background"
private const val SERVICE_ID = 1001


private fun channels(context: Context) {
    val manager=context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL,context.getString(R.string.bg_channel),NotificationManager.IMPORTANCE_LOW))
    MessageNotifications.ensureChannel(context)
}
private fun launchIntent(context:Context, key:String):PendingIntent {
    val intent=requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    // Data participates in PendingIntent identity; hash collisions cannot open another conversation.
    intent.data=android.net.Uri.Builder().scheme("messenger").authority("notification").appendPath(key).build()
    val parts=key.split(':' )
    if(parts.size==3) { intent.putExtra("notificationAccount",parts.take(2).joinToString(":"));intent.putExtra("notificationChat",parts[2]) }
    return PendingIntent.getActivity(context,key.hashCode(),intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
internal suspend fun notifyUnread(context:Context,account:String, chats:List<dev.messenger.data.wire.Chat>,visible:Pair<String,String>?) {
    MessageNotifications.reconcile(context,account,chats.map { it.chatId }.toSet())
    if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
    channels(context)
    val preferences=context.getSharedPreferences("notification_markers",Context.MODE_PRIVATE)
    val manager=context.getSystemService(NotificationManager::class.java)
    for(chat in chats) {
        val key=account+":"+chat.chatId
        if(chat.unreadCount==0L || chat.preferences.muted) { manager.cancel(key,1); continue }
        val last=chat.lastMessage ?: continue
        if(visible==(account to chat.chatId)) {
            manager.cancel(key,1)
            preferences.edit().putString(key,last.messageId).apply()
            continue
        }
        if(preferences.getString(key,null)==last.messageId) continue
        val notification=NotificationCompat.Builder(context,MessageNotifications.ensureChannel(context))
            .setSmallIcon(R.drawable.ic_notification_kuku)
            .setContentTitle(chat.title.ifBlank { chat.peer?.displayName.orEmpty() })
            .setContentText(context.getString(R.string.unread_count,chat.unreadCount))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setSilent(MessageNotifications.sound(context)==dev.messenger.domain.MessageSound.SILENT ||
                !MessageNotifications.alerts.allow(account,android.os.SystemClock.elapsedRealtime()))
            .setContentIntent(launchIntent(context,key)).setAutoCancel(true).build()
        manager.notify(key,1,notification)
        preferences.edit().putString(key,last.messageId).apply()
    }
}

/** Owns the messaging runtime independently of Activity visibility. */
class MessagingForegroundService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var watching:Job?=null

    private var defaultNetwork:android.net.Network?=null
    private val networkCallback=object:android.net.ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network:android.net.Network) {
            defaultNetwork=network
            AndroidRuntime.container?.conversations?.networkAvailable()
        }
        override fun onLost(network:android.net.Network) {
            if(defaultNetwork==network) {
                defaultNetwork=null
                AndroidRuntime.container?.conversations?.networkUnavailable()
            }
        }
    }
    private var connectionWatching:Job?=null
    private fun connectionNotification(online:Boolean)=NotificationCompat.Builder(this,CHANNEL)
        .setSmallIcon(R.drawable.ic_notification_kuku)
        .setContentTitle(getString(R.string.bg_running))
        .setContentText(getString(if(online) R.string.bg_connection else R.string.bg_reconnecting))
        .setContentIntent(launchIntent(this,"service")).setOngoing(true).setOnlyAlertOnce(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate()
        val connectivity=getSystemService(android.net.ConnectivityManager::class.java)
        // Callbacks share the main thread with this initial snapshot, avoiding a stale
        // initial value overwriting a newer callback. No network polling is needed.
        connectivity.registerDefaultNetworkCallback(networkCallback,android.os.Handler(android.os.Looper.getMainLooper()))
        defaultNetwork=connectivity.activeNetwork
        if(defaultNetwork==null) AndroidRuntime.container?.conversations?.networkUnavailable()
        else AndroidRuntime.container?.conversations?.networkAvailable()
    }

    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        channels(this)
        val notification=connectionNotification(AndroidRuntime.container?.sessions?.state?.value?.online==true)
        if(Build.VERSION.SDK_INT>=34) startForeground(SERVICE_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(SERVICE_ID,notification)
        val runtime=AndroidRuntime.container
        if(runtime==null) { stopSelf(); return START_NOT_STICKY }
        runtime.setActive("service",true)
        AndroidRuntime.running()
        if(connectionWatching==null) connectionWatching=scope.launch {
            runtime.sessions.state.map {it.online}.distinctUntilChanged().collect {online ->
                android.util.Log.i("KukuConnection","connection state: "+if(online) "online" else "offline")
                NotificationManagerCompat.from(this@MessagingForegroundService)
                    .notify(SERVICE_ID,connectionNotification(online))
            }
        }
        if(watching==null) watching=scope.launch {
            runtime.sessions.state.map { it.loaded to it.account?.key }.distinctUntilChanged().collectLatest { (initialized,key) ->
                if(!initialized) return@collectLatest
                if(key==null) { MessageNotifications.reconcile(this@MessagingForegroundService,null,emptySet()); stopSelf(); return@collectLatest }
                combine(runtime.conversations.chats(key),MessageNotifications.visible) { chats,visible -> chats to visible }
                    .collect { (chats,visible) -> notifyUnread(this@MessagingForegroundService,key,chats,visible) }
            }
        }
        return START_STICKY
    }
    override fun onDestroy() {
        getSystemService(android.net.ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        AndroidRuntime.container?.setActive("service",false)
        AndroidRuntime.stopped()

        scope.cancel(); stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
class MessagingBootReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val prefs=context.getSharedPreferences("background",Context.MODE_PRIVATE)
        if(prefs.getBoolean("autostart",false)) AndroidRuntime.start(context)
    }
}

/** Binary transfers have their own Android dataSync budget, independent of messaging. */
class FileTransferService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var watching:Job?=null
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        channels(this)
        val notification=NotificationCompat.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(getString(R.string.file_transfers)).setContentText(getString(R.string.transfer_controls))
            .setContentIntent(launchIntent(this,"files")).setOngoing(true).build()
        if(Build.VERSION.SDK_INT>=29) startForeground(1002,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1002,notification)
        val runtime=AndroidRuntime.container ?: run {stopSelf();return START_NOT_STICKY}
        runtime.setActive("files",true)
        if(watching==null) watching=scope.launch { runtime.transfers.activeWork.collect { if(!it) stopSelf() } }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId:Int,fgsType:Int) { stopSelf() }
    override fun onDestroy() {
        AndroidRuntime.container?.setActive("files",false)
        scope.cancel();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()
    }
}
