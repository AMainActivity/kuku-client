package dev.messenger.domain

import kotlinx.coroutines.flow.StateFlow

enum class ServicePhase { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
data class MessagingServiceState(val phase:ServicePhase = ServicePhase.STOPPED)

/** Platform service status and retry; does not own the socket or the UI lifecycle. */
interface BackgroundConnection {
    val state:StateFlow<MessagingServiceState>
    fun retry():Boolean
    fun notificationsEnabled():Boolean
}
