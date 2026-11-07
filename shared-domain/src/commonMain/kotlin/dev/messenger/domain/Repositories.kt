package dev.messenger.domain

import dev.messenger.domain.model.*
import kotlinx.coroutines.flow.*

data class ActiveAccount(val serverId:String,val userId:String,val sessionId:String) {
    val key:String get()="$serverId:$userId"
}
data class SessionState(val loaded:Boolean=false,val account:ActiveAccount?=null,val online:Boolean=false)
class ApplicationFailure(val code:String,cause:Throwable?=null):Exception(code,cause)
fun failureCode(failure:Throwable)=(failure as? ApplicationFailure)?.code ?: "CONNECTION_ERROR"

interface SessionAccess {
    val state:StateFlow<SessionState>
    suspend fun restore()
    suspend fun prepareLogin(endpoint:ServerEndpoint)
    suspend fun cancelLogin():Boolean
    suspend fun startEmail(endpoint:ServerEndpoint,email:String):Challenge
    suspend fun verifyEmail(code:String,deviceName:String,mfaCode:String?=null)
}
interface FileStorage {
    fun path(accountId:String,jobId:String):String
    fun size(accountId:String,jobId:String):Long
    fun hash(accountId:String,jobId:String):String
    fun read(accountId:String,jobId:String,offset:Long,count:Long):ByteArray
    fun write(accountId:String,jobId:String,offset:Long,bytes:ByteArray)
    fun remove(accountId:String,jobId:String)
    companion object {const val MAX_BYTES=512L*1024*1024}
}
interface DraftRepository {
    fun messages(accountId:AccountId):LocalMessages
    suspend fun composer(accountId:String,chatId:String):ComposerExtras
    suspend fun saveComposer(accountId:String,chatId:String,replyTo:String?,files:List<String>)
    fun drafts(accountId:String):Flow<List<DraftPreview>>
}
interface IncomingShareRepository {
    val pending:Flow<List<IncomingShare>>
    suspend fun accept(id:String,accountId:String,chatId:String)
    suspend fun discard(id:String)
}
interface AttachmentRepository {
    val files:FileStorage
    fun observe(accountId:String,chatId:String):Flow<List<TransferSnapshot>>
    suspend fun registerUpload(accountId:String,chatId:String,jobId:String,filename:String,mime:String,purpose:String="attachment")
    suspend fun avatarBytes(accountId:String,avatarId:String):ByteArray
    suspend fun enqueueDownload(accountId:String,chatId:String,attachment:Attachment)
    suspend fun prepareShare(accountId:String,chatId:String,attachments:List<Attachment>):List<TransferSnapshot>
    suspend fun pause(accountId:String,jobId:String)
    suspend fun resume(accountId:String,jobId:String)
    suspend fun cancel(accountId:String,jobId:String)
    suspend fun clearDownloadCache(accountId:String)
}
interface ChatCatalog {
    val connection:StateFlow<SessionState>
    fun chats(key:String):Flow<List<Chat>>
    fun chat(key:String,chatId:String):Flow<Chat?>
    fun members(key:String,chatId:String):Flow<List<MemberInfo>>
    suspend fun loadMembers(key:String,chatId:String)
    suspend fun synchronize()
    suspend fun newDirect(userId:String):Chat
    suspend fun preferencesSet(chatId: String, expectedVersion: Long, pinned: Boolean, pinRank: Int = 0, muted: Boolean):Unit
    suspend fun receiptsList(chatId: String, cursor: String? = null, limit: Int = 30, query: String = ""):Page<MemberInfo>
    suspend fun chatsGet(chatId: String):Chat
    suspend fun chatsDirect(userId: String):Chat
}
interface DirectoryCatalog {
    suspend fun usersList(cursor: String? = null, limit: Int = 30, query: String = ""):Page<DirectoryPerson>
    suspend fun botsList(cursor: String? = null, limit: Int = 30, query: String = ""):Page<DirectoryPerson>
}
interface AdminRepository:DirectoryCatalog {
    suspend fun supportsGroupDeletion():Boolean
    suspend fun adminGroupsList(cursor:String?=null,limit:Int=30,query:String=""):Page<AdminGroup>
    suspend fun adminGroupDelete(chatId:String,expectedVersion:Long,reason:String)
    suspend fun changeUser(action:UserAdminAction,userId:String)
    suspend fun admissionsCreate(email: String):Unit
    suspend fun admissionsRevoke(email: String):Unit
    suspend fun admissionsList(cursor: String? = null, limit: Int = 30, query: String = ""):Page<AdmissionInfo>
    suspend fun adminUsersList(cursor: String? = null, limit: Int = 30, query: String = ""):Page<AdminUser>
    suspend fun adminBan(userId: String, reason: String, until: String? = null):Unit
    suspend fun adminUnban(userId: String):Unit
    suspend fun adminDeactivate(userId: String):Unit
    suspend fun adminRole(userId: String, role: String):Unit
    suspend fun adminRevoke(userId: String):Unit
    suspend fun adminAudit(cursor: String? = null, limit: Int = 30, query: String = ""):Page<AuditEntry>
    suspend fun botsCreate(handle: String, displayName: String, description: String = ""):Unit
    suspend fun botsUpdate(userId: String, expectedVersion: Long, handle: String, displayName: String, description: String = ""):Unit
    suspend fun botsDisable(userId: String):Unit
}
interface ProfileRepository {
    fun profile(accountId:String):Flow<Me?>
    suspend fun logout():Boolean
    suspend fun sessionsList():Items<DeviceSession>
    suspend fun sessionsRevoke(sessionId: String? = null):Unit
    suspend fun mfaSetup():MfaEnrollment
    suspend fun mfaConfirm(code: String):Unit
    suspend fun mfaStepUp(code: String):Unit
    suspend fun usersMe():Me
    suspend fun usersUpdate(expectedVersion: Long, displayName: String, avatarId: String? = null):Unit
    suspend fun emailChangeStart(email: String):EmailChange
    suspend fun emailChangeVerify(changeId: String, oldCode: String, newCode: String, mfaCode: String? = null):Unit
}
interface GroupRepository:ChatCatalog,GroupCreation {
    suspend fun supportsGroupDeletion():Boolean
    suspend fun groupDelete(chatId:String,expectedVersion:Long,onlyIfLastHuman:Boolean)
    suspend fun changeMember(action:MemberAction,chatId:String,userId:String)
    suspend fun userBlock(userId: String):Unit
    suspend fun userUnblock(userId: String):Unit
    suspend fun groupUpdate(chatId: String, expectedVersion: Long, title: String, description: String = "", avatarId: String? = null):Unit
    suspend fun membersList(chatId: String, cursor: String? = null, limit: Int = 30, query: String = ""):Page<MemberInfo>
    suspend fun groupAccept(chatId: String):Unit
    suspend fun groupLeave(chatId: String):Unit
    suspend fun groupRemove(chatId: String, userId: String):Unit
    suspend fun groupBan(chatId: String, userId: String):Unit
    suspend fun groupUnban(chatId: String, userId: String):Unit
    suspend fun groupRole(chatId: String, userId: String, role: String):Unit
    suspend fun groupTransfer(chatId: String, userId: String):Unit
    suspend fun groupBotRemove(chatId: String, userId: String):Unit
}
interface GroupCreation {
    suspend fun groupCreate(title: String, description: String = "", userIds: List<String> = emptyList()):Chat
    suspend fun groupInvite(chatId: String, userId: String):Unit
    suspend fun groupBotAdd(chatId: String, userId: String):Unit
}
interface DirectoryRepository:ChatCatalog,DirectoryCatalog,GroupCreation {
}
interface MessagingRepository:ChatCatalog {
    /** Local preflight; must not perform IO or clear the composer. */
    fun validateText(text: String)
    val error:StateFlow<String?>
    val historyGeneration:StateFlow<Long>
    suspend fun initialUnreadCursor(key:String,chatId:String):Long?
    fun observeHistoryWindow(key:String,chatId:String,lower:Long,upper:Long?,limit:Int):Flow<List<Message>>
    suspend fun cachedHistoryPage(key:String,chatId:String,direction:String,anchor:Long?,limit:Int=50):History
    suspend fun historyPage(key:String,chatId:String,direction:String,anchor:Long?,limit:Int=50):History
    fun replyTargets(key:String,chatId:String,ids:List<String>):Flow<Map<String,Message>>
    suspend fun messageSequence(key:String,chatId:String,messageId:String):Long
    suspend fun send(key:String,chatId:String,text:String,replyTo:String?=null,files:List<String> = emptyList())
    suspend fun read(key:String,chatId:String,seq:Long)
    suspend fun retry(key:String,operationId:String)
    suspend fun logout():Boolean
    suspend fun messagesEdit(messageId: String, expectedVersion: Long, text: String):Unit
    suspend fun messagesDelete(messageId: String, expectedVersion: Long):Unit
    suspend fun messagesForward(chatId: String, messageIds: List<String>):Unit
}
