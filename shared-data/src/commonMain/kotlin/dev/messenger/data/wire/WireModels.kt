package dev.messenger.data.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

const val CONTRACT_VERSION = "0.1.0"
const val PROTOCOL_VERSION = 1
const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
const val MAX_REQUEST_BYTES = 128 * 1024
const val MAX_RECEIVE_BYTES = 1024 * 1024
val wireJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

internal fun uuid(value: String) {
    require(value == Uuid.parse(value).toString()) { "Expected lowercase UUID" }
}
internal fun cursor(value: Long) { require(value in 0..MAX_SAFE_INTEGER) }

@Serializable class EmptyParams
@Serializable data class ServerInfo(
    val serverId: String, val protocolVersions: List<Int>, val contractVersion: String,
    val serverTime: String, val registration: String, val auth: String,
    val capabilities: Map<String, JsonElement>, val limits: Map<String, Long>,
) { init { uuid(serverId) } }
@Serializable data class Pong(val serverTime: String)
@Serializable class EmailStartParams(val email: String, val clientAttemptId: String) {
    init { require(email.length in 3..254); uuid(clientAttemptId) }
    override fun toString() = "EmailStartParams([redacted])"
}
@Serializable class EmailVerifyParams(
    val challengeId: String, val code: String, val deviceId: String, val deviceName: String,
    val mfaCode: String? = null,
) {
    init {
        uuid(challengeId); uuid(deviceId)
        require(code.matches(Regex("[0-9]{8}")))
        require(deviceName.length in 1..128)
        require(mfaCode == null || mfaCode.matches(Regex("[0-9]{6}")))
    }
    override fun toString() = "EmailVerifyParams([redacted])"
}
@Serializable class ResumeParams(val accessToken: String) {
    init { require(accessToken.length in 32..256) }
    override fun toString() = "ResumeParams([redacted])"
}
@Serializable class RefreshParams(val refreshToken: String) {
    init { require(refreshToken.length in 32..256) }
    override fun toString() = "RefreshParams([redacted])"
}
@Serializable data class Challenge(
    val challengeId: String, val expiresAt: String, val resendAfterSeconds: Int, val message: String,
) { init { uuid(challengeId); require(resendAfterSeconds >= 0) } }
@Serializable data class SessionIdentity(
    val sessionId: String, val userId: String, val deviceId: String, val serverId: String,
) { init { listOf(sessionId, userId, deviceId, serverId).forEach(::uuid) } }
@Serializable class Credentials(
    val sessionId: String, val userId: String, val deviceId: String, val serverId: String,
    val accessToken: String, val refreshToken: String, val accessExpiresAt: String,
    val mfaEnrollmentRequired: Boolean = false,
) {
    init {
        listOf(sessionId, userId, deviceId, serverId).forEach(::uuid)
        require(accessToken.length in 32..256 && refreshToken.length in 32..256)
    }
    fun identity() = SessionIdentity(sessionId, userId, deviceId, serverId)
    override fun toString() = "Credentials([redacted])"
}
@Serializable data class Revoked(val revoked: Boolean)
@Serializable data class PageParams(val cursor: String? = null, val limit: Int = 30, val query: String = "") {
    init { require(cursor == null || cursor.length <= 2048); require(limit in 1..100); require(query.length <= 128) }
}
@Serializable data class ChatParams(val chatId: String) { init { uuid(chatId) } }
@Serializable data class DirectParams(val userId: String) { init { uuid(userId) } }
@Serializable data class Mention(val targetId: String, val offset: Int, val length: Int, val type: String = "mention") {
    init { uuid(targetId); require(type == "mention" && offset >= 0 && length > 0) }
}
/** Protocol offsets are Unicode code points, not Kotlin UTF-16 code units. */
fun String.codePointCount(): Int {
    var count = 0
    var i = 0
    while (i < length) {
        val first = this[i++]
        if (first.isHighSurrogate() && i < length && this[i].isLowSurrogate()) i++
        count++
    }
    return count
}
@Serializable class PlainContent(
    val text: String = "", val textEntities: List<Mention> = emptyList(),
    val attachmentIds: List<String> = emptyList(), val mode: String = "plain", val version: Int = 1,
) {
    init {
        require(mode == "plain" && version == 1)
        val size = text.codePointCount()
        require(size <= 16384 && textEntities.size <= 50 && attachmentIds.size <= 10)
        attachmentIds.forEach(::uuid)
        textEntities.forEach { require(it.offset.toLong() + it.length <= size) }
    }
    override fun toString() = "PlainContent([redacted])"
}
@Serializable class SendParams(
    val chatId: String, val clientMessageId: String, val content: PlainContent, val replyTo: String? = null,
) {
    init { uuid(chatId); uuid(clientMessageId); replyTo?.let(::uuid) }
    override fun toString() = "SendParams([redacted])"
}
@Serializable data class HistoryParams(
    val chatId: String, val direction: String = "before", val anchorSeq: Long? = null, val limit: Int = 30,
) {
    init {
        uuid(chatId); require(direction in setOf("before", "after", "around"))
        require(anchorSeq == null || anchorSeq in 1..MAX_SAFE_INTEGER); require(limit in 1..100)
    }
}
@Serializable data class Attachment(
    val attachmentId: String, val filename: String, val mime: String, val size: Long,
    val sha256: String, val status: String,
)
@Serializable class Message(
    val messageId: String, val clientMessageId: String, val chatId: String, val senderId: String,
    val seq: Long, val version: Long, val content: JsonObject?, val replyTo: String?,
    val deleted: Boolean, val createdAt: String, val editedAt: String?, val attachments: List<Attachment>,
) {
    init {
        listOf(messageId, clientMessageId, chatId, senderId).forEach(::uuid)
        replyTo?.let(::uuid); require(seq > 0 && version > 0)
    }
    // Unknown content stays intact in storage; the presentation layer can show a placeholder.
    fun plainTextOrNull(): String? {
        if (deleted) return null
        val data = content ?: return null
        if (data["mode"] != JsonPrimitive("plain") || data["version"] != JsonPrimitive(1)) return null
        return (data["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
    override fun toString() = "Message([redacted])"
}
@Serializable data class User(
    val userId: String, val displayName: String, val kind: String, val handle: String?,
    val description: String, val avatarId: String?, val version: Long, val deleted: Boolean,
)
@Serializable data class Membership(val role: String, val status: String, val joinedSeq: Long, val version: Long)
@Serializable data class Preferences(val pinned: Boolean, val pinRank: Int, val muted: Boolean, val version: Long)
@Serializable class Chat(
    val chatId: String, val kind: String, val title: String, val description: String, val avatarId: String?,
    val version: Long, val membership: Membership, val lastMessage: Message?, val unreadCount: Long,
    val peer: User?, val preferences: Preferences,
) { init { uuid(chatId) }; override fun toString() = "Chat([redacted])" }
@Serializable data class ChatPage(val items: List<Chat>, val nextCursor: String?, val order: String)
@Serializable data class History(
    val items: List<Message>, val beforeSeq: Long?, val afterSeq: Long?,
    val hasMoreBefore: Boolean, val hasMoreAfter: Boolean,
)
@Serializable data class SyncStart(val cursor: Long, val strategy: String, val history: String) {
    init { cursor(cursor); require(strategy == "catalog-then-replay" && history == "lazy") }
}
@Serializable data class SyncParams(val cursor: Long, val limit: Int = 100) {
    init { cursor(cursor); require(limit in 1..100) }
}
@Serializable data class AckParams(val cursor: Long) { init { cursor(cursor) } }
@Serializable data class Acknowledged(val acknowledged: Long) { init { cursor(acknowledged) } }
@Serializable class SyncEvent(val cursor: Long, val type: String, val data: JsonObject, val createdAt: String) {
    init { cursor(cursor) }
    override fun toString() = "SyncEvent([redacted])"
}
@Serializable data class SyncPage(
    val items: List<SyncEvent>, val nextCursor: Long, val hasMore: Boolean, val highWatermark: Long,
) {
    init {
        cursor(nextCursor); cursor(highWatermark); require(nextCursor <= highWatermark)
        require(items.zipWithNext().all { (a, b) -> a.cursor < b.cursor })
        require(items.all { it.cursor <= nextCursor })
        require(!hasMore || nextCursor < highWatermark)
    }
}
