package dev.messenger.data.wire

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class Items<T>(val items: List<T>)
@Serializable data class Page<T>(val items: List<T>, val nextCursor: String?)
@Serializable data class DirectoryPerson(val userId: String, val displayName: String, val kind: String,
    val handle: String?, val description: String, val avatarId: String?, val version: Long)
@Serializable data class Me(val userId: String, val displayName: String, val kind: String, val handle: String?,
    val description: String, val avatarId: String?, val version: Long, val deleted: Boolean,
    val email: String, val role: String, val mfaEnabled: Boolean)
@Serializable data class MemberInfo(val userId: String, val displayName: String, val kind: String,
    val handle: String?, val role: String, val status: String, val version: Long, val deliveredSeq: Long, val readSeq: Long)
@Serializable data class DeviceSession(val sessionId: String, val deviceId: String, val deviceName: String, val createdAt: String, val revoked: Boolean)
@Serializable data class AdmissionInfo(val email: String, val active: Boolean, val activatedUserId: String?, val createdAt: String)
@Serializable data class AdminUser(val userId: String, val email: String, val displayName: String, val role: String,
    val status: String, val banUntil: String?, val version: Long)
@Serializable class AuditEntry(val id: Long, val actorId: String?, val action: String, val target: String,
    val details: JsonObject, val createdAt: String)
@Serializable class MfaEnrollment(val secret: String, val uri: String) { override fun toString() = "MfaEnrollment([redacted])" }
@Serializable data class MfaEnabled(val mfaEnabled: Boolean)
@Serializable data class UserUpdated(val userId: String, val updated: Boolean)
@Serializable data class UserBlocked(val userId: String, val blocked: Boolean)
@Serializable data class AdmissionChanged(val email: String, val active: Boolean)
@Serializable data class GroupDeleteParams(val chatId:String,val expectedVersion:Long,val onlyIfLastHuman:Boolean=false) {
    init { uuid(chatId); require(expectedVersion>=1) }
}
@Serializable data class AdminGroupDeleteParams(val chatId:String,val expectedVersion:Long,val reason:String) {
    init { uuid(chatId); require(expectedVersion>=1 && reason.isNotBlank() && reason.length<=1000) }
}
@Serializable data class GroupDeleted(val chatId:String,val deleted:Boolean,val version:Long,val deletedAt:String)
@Serializable data class AdminGroup(val chatId:String,val title:String,val version:Long,val ownerId:String?,val activeHumans:Int,val createdAt:String)
@Serializable data class GroupChanged(val chatId: String, val version: Long)
@Serializable data class ReceiptAccepted(val accepted: Boolean)
@Serializable data class EmailChange(val changeId: String, val expiresAt: String, val message: String)
@Serializable data class EmailChanged(val changed: Boolean, val reauthenticationRequired: Boolean)
@Serializable data class CancelledUpload(val cancelled: Boolean)
@Serializable data class UploadInfo(val uploadId: String, val status: String, val size: Long, val sha256: String,
    val partBytes: Int, val partCount: Int, val expiresAt: String, val uploadPath: String, val downloadPath: String, val error: String?)
@Serializable data class ConfirmedPart(val number: Int, val size: Int, val sha256: String)
@Serializable data class UploadStatus(val uploadId: String, val status: String, val size: Long, val sha256: String,
    val partBytes: Int, val partCount: Int, val expiresAt: String, val uploadPath: String, val downloadPath: String,
    val error: String?, val parts: List<ConfirmedPart>, val confirmedBytes: Long)

@Serializable data class SessionRevokeParams(val sessionId: String? = null) { init { sessionId?.let(::uuid) } }
@Serializable class MfaParams(val code: String) {
    init { require(code.matches(Regex("[0-9]{6}"))) }; override fun toString() = "MfaParams([redacted])"
}
@Serializable data class EmailParams(val email: String) { init { require(email.length in 3..254) } }
@Serializable class EmailChangeParams(val changeId: String, val oldCode: String, val newCode: String, val mfaCode: String? = null) {
    init { uuid(changeId); require(oldCode.matches(Regex("[0-9]{8}")) && newCode.matches(Regex("[0-9]{8}"))) }
    override fun toString() = "EmailChangeParams([redacted])"
}
@Serializable data class ProfileParams(val expectedVersion: Long, val displayName: String, val avatarId: String? = null) {
    init { require(expectedVersion >= 1 && displayName.length in 1..128); avatarId?.let(::uuid) }
}
@Serializable data class BanParams(val userId: String, val reason: String, val until: String? = null) {
    init { uuid(userId); require(reason.length in 1..1000); require(until == null || until.length <= 40) }
}
@Serializable data class UserRoleParams(val userId: String, val role: String) {
    init { uuid(userId); require(role in setOf("owner", "admin", "user")) }
}
@Serializable data class GroupCreateParams(val title: String, val description: String = "", val userIds: List<String> = emptyList()) {
    init { require(title.length in 1..128 && description.length <= 2000 && userIds.size <= 99); userIds.forEach(::uuid) }
}
@Serializable data class GroupUpdateParams(val chatId: String, val expectedVersion: Long, val title: String,
    val description: String = "", val avatarId: String? = null) {
    init { uuid(chatId); require(expectedVersion >= 1 && title.length in 1..128 && description.length <= 2000); avatarId?.let(::uuid) }
}
@Serializable data class ChatPageParams(val chatId: String, val cursor: String? = null, val limit: Int = 30, val query: String = "") {
    init { uuid(chatId); require(limit in 1..100 && query.length <= 128); require(cursor == null || cursor.length <= 2048) }
}
@Serializable data class MemberParams(val chatId: String, val userId: String) { init { uuid(chatId); uuid(userId) } }
@Serializable data class MemberRoleParams(val chatId: String, val userId: String, val role: String) {
    init { uuid(chatId); uuid(userId); require(role in setOf("admin", "member")) }
}
@Serializable data class PreferenceParams(val chatId: String, val expectedVersion: Long, val pinned: Boolean,
    val pinRank: Int = 0, val muted: Boolean) {
    init { uuid(chatId); require(expectedVersion >= 0 && pinRank in 0..9999) }
}
@Serializable data class MessageParams(val messageId: String) { init { uuid(messageId) } }
@Serializable class EditParams(val messageId: String, val expectedVersion: Long, val text: String, val textEntities: List<Mention> = emptyList()) {
    init { uuid(messageId); require(expectedVersion >= 1); PlainContent(text, textEntities) }
    override fun toString() = "EditParams([redacted])"
}
@Serializable data class DeleteParams(val messageId: String, val expectedVersion: Long) { init { uuid(messageId); require(expectedVersion >= 1) } }
@Serializable data class ForwardParams(val chatId: String, val messageIds: List<String>) {
    init { uuid(chatId); require(messageIds.size in 1..20); messageIds.forEach(::uuid) }
}
@Serializable data class ReceiptParams(val chatId: String, val kind: String, val throughSeq: Long) {
    init { uuid(chatId); require(kind in setOf("delivered", "read")); cursor(throughSeq) }
}
@Serializable data class UploadCreateParams(val filename: String, val mime: String, val size: Long, val sha256: String, val purpose: String = "attachment") {
    init { require(filename.length in 1..255 && mime.length in 1..120 && size >= 1); require(sha256.matches(Regex("[0-9a-f]{64}"))); require(purpose in setOf("attachment", "avatar")) }
}
@Serializable data class UploadParams(val uploadId: String) { init { uuid(uploadId) } }
@Serializable data class BotCreateParams(val handle: String, val displayName: String, val description: String = "") {
    init { require(handle.matches(Regex("[a-z][a-z0-9_]{2,31}")) && displayName.length in 1..128 && description.length <= 2000) }
}
@Serializable data class BotUpdateParams(val userId: String, val expectedVersion: Long, val handle: String, val displayName: String, val description: String = "") {
    init { uuid(userId); require(expectedVersion >= 1); BotCreateParams(handle, displayName, description) }
}

@Serializable internal data class ReceiptUpdate(val chatId: String, val userId: String, val kind: String, val throughSeq: Long)
