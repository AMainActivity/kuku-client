package dev.messenger.runtime

import android.app.NotificationManager
import android.content.Context

/** Uses the system's posted set, so cleanup also works after process recreation. */
object MessageNotifications {
    private const val CHANNEL = "messenger.messages"

    fun reconcile(context: Context, account: String?, chatIds: Set<String>) {
        val manager = context.getSystemService(NotificationManager::class.java)
        for (posted in manager.activeNotifications) {
            if (posted.notification.channelId != CHANNEL) continue
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
            if (posted.notification.channelId == CHANNEL && posted.tag?.startsWith("$account:") == true) {
                manager.cancel(posted.tag, posted.id)
            }
        }
        val markers = context.getSharedPreferences("notification_markers", Context.MODE_PRIVATE)
        val edit = markers.edit()
        markers.all.keys.filter { it.startsWith("$account:") }.forEach { edit.remove(it) }
        edit.apply()
    }
}
