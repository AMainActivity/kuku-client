package dev.messenger.runtime

import android.app.NotificationManager
import android.content.Context
import android.app.NotificationChannel
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import dev.messenger.core.runtime.R
import dev.messenger.domain.MessageSound
import kotlinx.coroutines.flow.MutableStateFlow

/** Uses the system's posted set, so cleanup also works after process recreation. */
object MessageNotifications {
    private const val CHANNEL = "messenger.messages"
    private const val CUCKOO = "messenger.messages.cuckoo.v1"
    private const val SILENT = "messenger.messages.silent"
    internal val visible=MutableStateFlow<Pair<String,String>?>(null)
    internal val alerts=MessageAlertThrottle()
    private fun owns(channel:String?)=channel in setOf(CHANNEL,CUCKOO,SILENT)

    fun sound(context:Context):MessageSound {
        val prefs=context.getSharedPreferences("message_notifications",Context.MODE_PRIVATE)
        val saved=prefs.getString("sound",null)
        return MessageSound.entries.firstOrNull {it.name==saved}
            // Preserve existing installations' OS channel settings; fresh installs use Kuku!.
            ?: if(context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)!=null) MessageSound.SYSTEM else MessageSound.CUCKOO
    }
    fun selectSound(context:Context,sound:MessageSound) {
        context.getSharedPreferences("message_notifications",Context.MODE_PRIVATE).edit().putString("sound",sound.name).apply()
        ensureChannel(context)
    }
    fun ensureChannel(context:Context):String {
        val sound=sound(context)
        val id=when(sound) {MessageSound.CUCKOO->CUCKOO;MessageSound.SYSTEM->CHANNEL;MessageSound.SILENT->SILENT}
        val label=when(sound) {MessageSound.CUCKOO->R.string.message_channel_cuckoo;MessageSound.SYSTEM->R.string.message_channel;MessageSound.SILENT->R.string.message_channel_silent}
        val channel=NotificationChannel(id,context.getString(label),NotificationManager.IMPORTANCE_DEFAULT)
        val uri=when(sound) {
            MessageSound.CUCKOO->Uri.parse("android.resource://${context.packageName}/raw/${context.resources.getResourceEntryName(R.raw.kuku_notification)}")
            MessageSound.SYSTEM->RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            MessageSound.SILENT->null
        }
        channel.setSound(uri,AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return id
    }

    fun reconcile(context: Context, account: String?, chatIds: Set<String>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        for (posted in manager.activeNotifications) {
            if (!owns(posted.notification.channelId)) continue
            val tag = posted.tag ?: continue
            val prefix = "$account:"
            if (account == null || !tag.startsWith(prefix) || tag.removePrefix(prefix) !in chatIds) {
                manager.cancel(tag, posted.id)
            }
        }
    }

    fun clearAccount(context: Context, account: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        for (posted in manager.activeNotifications) {
            if (owns(posted.notification.channelId) && posted.tag?.startsWith("$account:") == true) {
                manager.cancel(posted.tag, posted.id)
            }
        }
        val markers = context.getSharedPreferences("notification_markers", Context.MODE_PRIVATE)
        val edit = markers.edit()
        markers.all.keys.filter { it.startsWith("$account:") }.forEach { edit.remove(it) }
        edit.apply()
    }
}

/** One audible alert per burst across chats; timestamps use Android's monotonic clock. */
internal class MessageAlertThrottle {
    private var account:String?=null
    private var last:Long?=null
    fun allow(key:String,now:Long):Boolean {
        val previous=last
        if(account==key && previous!=null && now-previous in 0 until MIN_ALERT_INTERVAL_MILLIS) return false
        account=key;last=now
        return true
    }
    companion object { const val MIN_ALERT_INTERVAL_MILLIS=5_000L }
}
