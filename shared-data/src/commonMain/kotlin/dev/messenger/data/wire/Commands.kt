package dev.messenger.data.wire

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

class Command<P, R> internal constructor(
    val name: String, val mutation: Boolean, val params: KSerializer<P>, val result: KSerializer<R>,
)
object Commands {
    val ServerInfo = Command("server.info", false, EmptyParams.serializer(), dev.messenger.data.wire.ServerInfo.serializer())
    val Ping = Command("ping", false, EmptyParams.serializer(), Pong.serializer())
    val EmailStart = Command("auth.email.start", false, EmailStartParams.serializer(), Challenge.serializer())
    val EmailVerify = Command("auth.email.verify", false, EmailVerifyParams.serializer(), Credentials.serializer())
    val Resume = Command("auth.resume", false, ResumeParams.serializer(), SessionIdentity.serializer())
    val Refresh = Command("auth.refresh", true, RefreshParams.serializer(), Credentials.serializer())
    val Logout = Command("auth.logout", true, EmptyParams.serializer(), Revoked.serializer())
    val ChatsList = Command("chats.list", false, PageParams.serializer(), ChatPage.serializer())
    val ChatsGet = Command("chats.get", false, ChatParams.serializer(), Chat.serializer())
    val ChatsDirect = Command("chats.direct", true, DirectParams.serializer(), Chat.serializer())
    val MessagesSend = Command("messages.send", true, SendParams.serializer(), Message.serializer())
    val MessagesHistory = Command("messages.history", false, HistoryParams.serializer(), History.serializer())
    val SyncStart = Command("sync.start", false, EmptyParams.serializer(), dev.messenger.data.wire.SyncStart.serializer())
    val SyncPull = Command("sync.pull", false, SyncParams.serializer(), SyncPage.serializer())
    val SyncAck = Command("sync.ack", false, AckParams.serializer(), Acknowledged.serializer())
    val supported = listOf(ServerInfo, Ping, EmailStart, EmailVerify, Resume, Refresh, Logout,
        ChatsList, ChatsGet, ChatsDirect, MessagesSend, MessagesHistory, SyncStart, SyncPull, SyncAck) + MoreCommands.all
}

fun <P, R> encodeRequest(
    command: Command<P, R>, params: P, requestId: Uuid, operationId: Uuid? = null,
): String {
    require(!command.mutation || operationId != null) { "Mutation requires stable operationId" }
    val request = buildJsonObject {
        put("version", PROTOCOL_VERSION)
        put("requestId", requestId.toString())
        operationId?.let { put("operationId", it.toString()) }
        put("command", command.name)
        put("params", wireJson.encodeToJsonElement(command.params, params))
    }.toString()
    require(request.encodeToByteArray().size <= MAX_REQUEST_BYTES) { "Request exceeds frame limit" }
    return request
}

class ProtocolFailure(val code: String) : Exception("Messenger protocol failure: $code")
class RemoteFailure(val code: String, val retryable: Boolean, val details: JsonObject) :
    Exception("Messenger command rejected") {
    override fun toString() = "RemoteFailure(code=$code, retryable=$retryable)"
}
class RequestTimeout(val outcomeUnknown: Boolean) : Exception("Messenger request timed out")
class ConnectionLost : Exception("Messenger connection closed")

sealed interface Incoming {
    data class Hello(val connectionId: String, val serverId: String, val contractVersion: String) : Incoming
    class Response(val requestId: String?, val data: JsonElement?, val error: RemoteFailure?) : Incoming
    class Event(val name: String, val data: JsonObject) : Incoming
}

fun decodeIncoming(text: String): Incoming {
    if (text.encodeToByteArray().size > MAX_RECEIVE_BYTES) throw ProtocolFailure("FRAME_TOO_LARGE")
    try {
        val root = wireJson.parseToJsonElement(text).jsonObject
        if (root["version"] != JsonPrimitive(PROTOCOL_VERSION)) throw ProtocolFailure("UNSUPPORTED_PROTOCOL")
        return when (root["type"]?.jsonPrimitive?.content) {
            "hello" -> {
                val connectionId = root.getValue("connectionId").jsonPrimitive.content.also(::uuid)
                val serverId = root.getValue("serverId").jsonPrimitive.content.also(::uuid)
                Incoming.Hello(connectionId, serverId, root.getValue("contractVersion").jsonPrimitive.content)
            }
            "response" -> {
                val requestId = root["requestId"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content?.also(::uuid)
                when (root["ok"]) {
                    JsonPrimitive(true) -> Incoming.Response(requestId, root.getValue("data"), null)
                    JsonPrimitive(false) -> {
                        val error = root.getValue("error").jsonObject
                        val code = error.getValue("code").jsonPrimitive.content
                        require(code.matches(Regex("[A-Z][A-Z0-9_]{0,79}")))
                        val retryable = error.getValue("retryable")
                        require(retryable == JsonPrimitive(true) || retryable == JsonPrimitive(false))
                        Incoming.Response(requestId, null,
                            RemoteFailure(code, retryable == JsonPrimitive(true), error.getValue("details").jsonObject))
                    }
                    else -> throw ProtocolFailure("INVALID_RESPONSE")
                }
            }
            "event" -> Incoming.Event(root.getValue("event").jsonPrimitive.content, root.getValue("data").jsonObject)
            else -> throw ProtocolFailure("UNKNOWN_ENVELOPE")
        }
    } catch (error: ProtocolFailure) { throw error }
    catch (_: Exception) { throw ProtocolFailure("MALFORMED_FRAME") }
}

fun verifyServer(info: ServerInfo, hello: Incoming.Hello, expectedServerId: String? = null) {
    if (info.contractVersion != CONTRACT_VERSION || hello.contractVersion != CONTRACT_VERSION ||
        PROTOCOL_VERSION !in info.protocolVersions) throw ProtocolFailure("UNSUPPORTED_CONTRACT")
    if (info.serverId != hello.serverId || (expectedServerId != null && info.serverId != expectedServerId)) {
        throw ProtocolFailure("SERVER_IDENTITY_CHANGED")
    }
    if (info.auth != "email_otp" || info.registration != "admin_email_allowlist") {
        throw ProtocolFailure("UNSUPPORTED_AUTH")
    }
}
