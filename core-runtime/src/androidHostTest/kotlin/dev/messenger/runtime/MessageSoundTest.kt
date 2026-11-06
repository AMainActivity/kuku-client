package dev.messenger.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import dev.messenger.domain.MessageSound
import dev.messenger.core.runtime.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class MessageSoundTest {
    @Test fun visibleChatCancelsNotificationAndDoesNotAlertAfterLeaving()=kotlinx.coroutines.runBlocking<Unit> {
        val context=RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val manager=context.getSystemService(NotificationManager::class.java)
        fun id()=java.util.UUID.randomUUID().toString()
        val account=id()+":"+id()
        val chatId=id()
        val last=dev.messenger.data.wire.Message(id(),id(),chatId,id(),1,1,null,null,false,"",null,emptyList())
        val chat=dev.messenger.data.wire.Chat(chatId,"group","Chat","",null,1,
            dev.messenger.data.wire.Membership("member","active",1,1),last,1,null,
            dev.messenger.data.wire.Preferences(false,0,false,1))
        manager.notify("$account:$chatId",1,android.app.Notification.Builder(context,MessageNotifications.ensureChannel(context))
            .setSmallIcon(android.R.drawable.ic_dialog_info).build())
        notifyUnread(context,account,listOf(chat),account to chatId)
        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(last.messageId,context.getSharedPreferences("notification_markers",0).getString("$account:$chatId",null))
        notifyUnread(context,account,listOf(chat),null)
        assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun channelSelectionPreservesLegacyAndProvidesCuckooAndSilence() {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        context.getSharedPreferences("message_notifications",0).edit().clear().commit()
        assertEquals(MessageSound.CUCKOO,MessageNotifications.sound(context))
        manager.createNotificationChannel(NotificationChannel("messenger.messages","Legacy",NotificationManager.IMPORTANCE_LOW))
        assertEquals(MessageSound.SYSTEM,MessageNotifications.sound(context))
        MessageNotifications.selectSound(context,MessageSound.CUCKOO)
        val cuckoo=manager.getNotificationChannel(MessageNotifications.ensureChannel(context))
        assertTrue(cuckoo.sound.toString().endsWith("/raw/kuku_notification"))
        assertEquals(NotificationManager.IMPORTANCE_LOW,manager.getNotificationChannel("messenger.messages").importance)
        MessageNotifications.selectSound(context,MessageSound.SILENT)
        assertNull(manager.getNotificationChannel(MessageNotifications.ensureChannel(context)).sound)
    }
    @Test fun soundIsPackagedAsShortPcmWave() {
        val bytes=RuntimeEnvironment.getApplication().resources.openRawResource(R.raw.kuku_notification).use {it.readBytes()}
        assertEquals("RIFF",bytes.copyOfRange(0,4).decodeToString())
        assertEquals("WAVE",bytes.copyOfRange(8,12).decodeToString())
        assertEquals(44+32000*72/100*2,bytes.size)
    }
    @Test fun burstsAcrossChatsProduceOneAlertAndRecoverAfterFiveSeconds() {
        val gate=MessageAlertThrottle()
        assertTrue(gate.allow("account",0))
        assertFalse(gate.allow("account",1))
        assertFalse(gate.allow("account",4999))
        assertTrue(gate.allow("account",5000))
        assertTrue(gate.allow("other-account",5001))
    }
    @Test fun logoutAlsoRemovesCuckooAndSilentChannelNotifications() {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        listOf(MessageSound.CUCKOO,MessageSound.SILENT).forEachIndexed {index,sound ->
            MessageNotifications.selectSound(context,sound)
            manager.notify("account:chat$index",1,android.app.Notification.Builder(context,MessageNotifications.ensureChannel(context))
                .setSmallIcon(android.R.drawable.ic_dialog_info).build())
        }
        assertEquals(2,manager.activeNotifications.size)
        MessageNotifications.reconcile(context,"account",setOf("chat0"))
        assertEquals(1,manager.activeNotifications.size)
        MessageNotifications.clearAccount(context,"account")
        assertTrue(manager.activeNotifications.isEmpty())
    }
}
