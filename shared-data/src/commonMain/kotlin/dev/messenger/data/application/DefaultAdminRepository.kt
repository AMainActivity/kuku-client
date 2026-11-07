package dev.messenger.data.application

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.domain.model.Page
import dev.messenger.domain.model.AdmissionInfo
import dev.messenger.domain.model.AdminUser
import dev.messenger.domain.model.AuditEntry
import dev.messenger.data.wire.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class DefaultAdminRepository(directory:DirectoryCatalog,private val commands:RepositoryCommands):AdminRepository,DirectoryCatalog by directory {
    override suspend fun supportsGroupDeletion():Boolean = commands.execute(Commands.ServerInfo,EmptyParams()).capabilities["groupDeletion"]?.jsonPrimitive?.booleanOrNull == true
    override suspend fun admissionsCreate(email: String):Unit {  commands.execute(MoreCommands.AdmissionsCreate,EmailParams(email)); }
    override suspend fun admissionsRevoke(email: String):Unit {  commands.execute(MoreCommands.AdmissionsRevoke,EmailParams(email)); }
    override suspend fun admissionsList(cursor: String?, limit: Int, query: String):Page<AdmissionInfo> = applicationCall { commands.execute(MoreCommands.AdmissionsList,PageParams(cursor,limit,query)).let {Page(it.items.map {row -> row.toModel()},it.nextCursor)} }
    override suspend fun adminUsersList(cursor: String?, limit: Int, query: String):Page<AdminUser> = applicationCall { commands.execute(MoreCommands.AdminUsersList,PageParams(cursor,limit,query)).let {Page(it.items.map {row -> row.toModel()},it.nextCursor)} }
    override suspend fun adminGroupsList(cursor:String?,limit:Int,query:String):Page<dev.messenger.domain.model.AdminGroup> = applicationCall {
        commands.execute(MoreCommands.AdminGroupsList,PageParams(cursor,limit,query)).let { page ->
            Page(page.items.map { dev.messenger.domain.model.AdminGroup(it.chatId,it.title,it.version,it.ownerId,it.activeHumans,it.createdAt) },page.nextCursor)
        }
    }
    override suspend fun adminGroupDelete(chatId:String,expectedVersion:Long,reason:String) {
        commands.execute(MoreCommands.AdminGroupDelete,AdminGroupDeleteParams(chatId,expectedVersion,reason))
    }
    override suspend fun adminBan(userId: String, reason: String, until: String?):Unit {  commands.execute(MoreCommands.AdminBan,BanParams(userId,reason,until)); }
    override suspend fun adminUnban(userId: String):Unit {  commands.execute(MoreCommands.AdminUnban,DirectParams(userId)); }
    override suspend fun adminDeactivate(userId: String):Unit {  commands.execute(MoreCommands.AdminDeactivate,DirectParams(userId)); }
    override suspend fun adminRole(userId: String, role: String):Unit {  commands.execute(MoreCommands.AdminRole,UserRoleParams(userId,role)); }
    override suspend fun adminRevoke(userId: String):Unit {  commands.execute(MoreCommands.AdminRevoke,DirectParams(userId)); }
    override suspend fun adminAudit(cursor: String?, limit: Int, query: String):Page<AuditEntry> = applicationCall { commands.execute(MoreCommands.AdminAudit,PageParams(cursor,limit,query)).let {Page(it.items.map {row -> row.toModel()},it.nextCursor)} }
    override suspend fun botsCreate(handle: String, displayName: String, description: String):Unit {  commands.execute(MoreCommands.BotsCreate,BotCreateParams(handle,displayName,description)); }
    override suspend fun botsUpdate(userId: String, expectedVersion: Long, handle: String, displayName: String, description: String):Unit {  commands.execute(MoreCommands.BotsUpdate,BotUpdateParams(userId,expectedVersion,handle,displayName,description)); }
    override suspend fun botsDisable(userId: String):Unit {  commands.execute(MoreCommands.BotsDisable,DirectParams(userId)); }
    override suspend fun changeUser(action:UserAdminAction,userId:String) {
        when(action) {
            UserAdminAction.UNBAN -> adminUnban(userId)
            UserAdminAction.REVOKE_SESSIONS -> adminRevoke(userId)
            UserAdminAction.DEACTIVATE -> adminDeactivate(userId)
        }
    }
}
