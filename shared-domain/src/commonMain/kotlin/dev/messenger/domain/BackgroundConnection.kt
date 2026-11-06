package dev.messenger.domain

import kotlinx.coroutines.flow.StateFlow

enum class ServicePhase { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
enum class MessageSound { CUCKOO, SYSTEM, SILENT }
data class MessagingServiceState(val phase:ServicePhase = ServicePhase.STOPPED)

/** Platform service status and retry; does not own the socket or the UI lifecycle. */
interface BackgroundConnection {
    val state:StateFlow<MessagingServiceState>
    fun retry():Boolean
    fun notificationsEnabled():Boolean
    fun messageSound():MessageSound = MessageSound.SYSTEM
    fun selectMessageSound(sound:MessageSound) {}
    fun visibleConversation(account:String?,chatId:String?) {}
}
