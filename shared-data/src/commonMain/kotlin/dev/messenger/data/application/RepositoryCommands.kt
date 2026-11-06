package dev.messenger.data.application

import dev.messenger.data.wire.Command
import dev.messenger.data.wire.wireJson
import dev.messenger.domain.ActiveAccount
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid
import okio.ByteString.Companion.encodeUtf8

internal interface CommandExecutor {
    suspend fun <P,R> execute(command:Command<P,R>,params:P,operationId:Uuid?):R
}

/** Wire-only boundary. Durable identities belong to Room; OTP/TOTP identities are session-scoped. */
internal class RepositoryCommands(
    private val account:()->ActiveAccount?,
    private val executor:CommandExecutor,
) {
    private val lock=Mutex()
    private var sessionId:String?=null
    private val pending=mutableMapOf<String,Uuid>()

    suspend fun <P,R> execute(command:Command<P,R>,params:P):R = applicationCall {
        val owner=account()?.let {"${it.key}:${it.sessionId}"}
        val ephemeral=command.mutation &&
            (command.name.startsWith("auth.") || command.name.startsWith("users.email."))
        val key=command.name+":"+wireJson.encodeToString(command.params,params).encodeUtf8().sha256().hex()
        val operationId=lock.withLock {
            if(sessionId!=owner) {pending.clear();sessionId=owner}
            if(ephemeral) pending.getOrPut(key) {Uuid.random()} else null
        }
        executor.execute(command,params,operationId).also {
            lock.withLock {if(sessionId==owner) pending.remove(key)}
        }
    }
}
