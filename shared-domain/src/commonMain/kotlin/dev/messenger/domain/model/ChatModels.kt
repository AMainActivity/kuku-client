package dev.messenger.domain.model

/** Application values; no wire serialization or database annotations. */
data class Attachment(val attachmentId: String, val filename: String, val mime: String, val size: Long,
    val sha256: String, val status: String,)

data class User(val userId: String, val displayName: String, val kind: String, val handle: String?,
    val description: String, val avatarId: String?, val version: Long, val deleted: Boolean,)

data class Membership(val role: String, val status: String, val joinedSeq: Long, val version: Long)

data class Preferences(val pinned: Boolean, val pinRank: Int, val muted: Boolean, val version: Long)

class Chat(val chatId: String, val kind: String, val title: String, val description: String, val avatarId: String?,
    val version: Long, val membership: Membership, val lastMessage: Message?, val unreadCount: Long,
    val peer: User?, val preferences: Preferences,)

data class History(val items: List<Message>, val beforeSeq: Long?, val afterSeq: Long?,
    val hasMoreBefore: Boolean, val hasMoreAfter: Boolean,)

data class DirectoryPerson(val userId: String, val displayName: String, val kind: String,
    val handle: String?, val description: String, val avatarId: String?, val version: Long)

class Me(val userId: String, val displayName: String, val kind: String, val handle: String?,
    val description: String, val avatarId: String?, val version: Long, val deleted: Boolean,
    val email: String, val role: String, val mfaEnabled: Boolean)

data class MemberInfo(val userId: String, val displayName: String, val kind: String,
    val handle: String?, val role: String, val status: String, val version: Long, val deliveredSeq: Long, val readSeq: Long)

data class DeviceSession(val sessionId: String, val deviceId: String, val deviceName: String, val createdAt: String, val revoked: Boolean)

data class AdmissionInfo(val email: String, val active: Boolean, val activatedUserId: String?, val createdAt: String)

data class AdminGroup(val chatId:String,val title:String,val version:Long,val ownerId:String?,val activeHumans:Int,val createdAt:String)

data class AdminUser(val userId: String, val email: String, val displayName: String, val role: String,
    val status: String, val banUntil: String?, val version: Long)

class AuditEntry(val id: Long, val actorId: String?, val action: String, val target: String,
    val details: String, val createdAt: String)

class MfaEnrollment(val secret: String, val uri: String)

class EmailChange(val changeId: String, val expiresAt: String, val message: String)

data class Challenge(val challengeId: String, val expiresAt: String, val resendAfterSeconds: Int, val message: String,)

class Message(val messageId:String,val clientMessageId:String,val chatId:String,val senderId:String,
    val seq:Long,val version:Long,private val text:String?,val replyTo:String?,val deleted:Boolean,
    val createdAt:String,val editedAt:String?,val attachments:List<Attachment>) {
    fun plainTextOrNull():String? = if(deleted) null else text
    override fun toString()="Message([redacted])"
}
data class Page<T>(val items:List<T>,val nextCursor:String?)
data class Items<T>(val items:List<T>)
data class TransferSnapshot(val accountId:String,val jobId:String,val chatId:String,val direction:String,
    val filename:String,val mime:String,val size:Long,val sha256:String,val state:String,val remoteId:String?,
    val progress:Long,val error:String?,val purpose:String)
data class DraftPreview(val chatId:String,val text:String,val replyTo:String?,val files:List<String>)
data class ComposerExtras(val replyTo:String?,val files:List<String>)
data class IncomingShare(val shareId:String,val text:String,val fileCount:Int)
enum class MemberAction { INVITE, REMOVE, BAN, UNBAN, TRANSFER_OWNER, ADD_BOT, REMOVE_BOT }
enum class UserAdminAction { UNBAN, REVOKE_SESSIONS, DEACTIVATE }
