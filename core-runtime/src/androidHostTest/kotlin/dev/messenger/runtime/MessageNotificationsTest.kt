package dev.messenger.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class MessageNotificationsTest {
    @Test fun revocationAndLogoutRemoveOnlyTheirMessageNotifications() {
        val context=RuntimeEnvironment.getApplication()
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("messenger.messages","Messages",3))
        manager.createNotificationChannel(NotificationChannel("messenger.background","Service",2))
        fun post(tag:String,channel:String="messenger.messages") {
            manager.notify(tag,1,Notification.Builder(context,channel).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(tag).build())
        }
        fun tags()=manager.activeNotifications.map {it.tag}.toSet()
        try {
            post("a:kept");post("a:revoked");post("a:service","messenger.background")
            MessageNotifications.reconcile(context,"a",setOf("kept"))
            assertEquals(setOf("a:kept","a:service"),tags())
            post("b:new")
            MessageNotifications.clearAccount(context,"a")
            assertEquals(setOf("b:new","a:service"),tags())
            MessageNotifications.reconcile(context,null,emptySet())
            assertEquals(setOf("a:service"),tags())
        } finally { manager.cancelAll() }
    }
}
