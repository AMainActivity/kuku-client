package dev.messenger.data.application

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.domain.model.Page
import dev.messenger.domain.model.MemberInfo
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class DefaultGroupRepository(catalog:ChatCatalog,creation:GroupCreation,private val commands:RepositoryCommands):GroupRepository,ChatCatalog by catalog,GroupCreation by creation {
    override suspend fun userBlock(userId: String):Unit {  commands.execute(MoreCommands.UserBlock,DirectParams(userId)); }
    override suspend fun userUnblock(userId: String):Unit {  commands.execute(MoreCommands.UserUnblock,DirectParams(userId)); }
    override suspend fun groupUpdate(chatId: String, expectedVersion: Long, title: String, description: String, avatarId: String?):Unit {  commands.execute(MoreCommands.GroupUpdate,GroupUpdateParams(chatId,expectedVersion,title,description,avatarId)); }
    override suspend fun membersList(chatId: String, cursor: String?, limit: Int, query: String):Page<MemberInfo> = applicationCall { commands.execute(MoreCommands.MembersList,ChatPageParams(chatId,cursor,limit,query)).let {Page(it.items.map {row -> row.toModel()},it.nextCursor)} }
    override suspend fun groupAccept(chatId: String):Unit {  commands.execute(MoreCommands.GroupAccept,ChatParams(chatId)); }
    override suspend fun groupLeave(chatId: String):Unit {  commands.execute(MoreCommands.GroupLeave,ChatParams(chatId)); }
    override suspend fun groupRemove(chatId: String, userId: String):Unit {  commands.execute(MoreCommands.GroupRemove,MemberParams(chatId,userId)); }
    override suspend fun groupBan(chatId: String, userId: String):Unit {  commands.execute(MoreCommands.GroupBan,MemberParams(chatId,userId)); }
    override suspend fun groupUnban(chatId: String, userId: String):Unit {  commands.execute(MoreCommands.GroupUnban,MemberParams(chatId,userId)); }
    override suspend fun groupRole(chatId: String, userId: String, role: String):Unit {  commands.execute(MoreCommands.GroupRole,MemberRoleParams(chatId,userId,role)); }
    override suspend fun groupTransfer(chatId: String, userId: String):Unit {  commands.execute(MoreCommands.GroupTransfer,MemberParams(chatId,userId)); }
    override suspend fun groupBotRemove(chatId: String, userId: String):Unit {  commands.execute(MoreCommands.GroupBotRemove,MemberParams(chatId,userId)); }
    override suspend fun changeMember(action:MemberAction,chatId:String,userId:String) {
        when(action) {
            MemberAction.INVITE -> groupInvite(chatId,userId)
            MemberAction.REMOVE -> groupRemove(chatId,userId)
            MemberAction.BAN -> groupBan(chatId,userId)
            MemberAction.UNBAN -> groupUnban(chatId,userId)
            MemberAction.TRANSFER_OWNER -> groupTransfer(chatId,userId)
            MemberAction.ADD_BOT -> groupBotAdd(chatId,userId)
            MemberAction.REMOVE_BOT -> groupBotRemove(chatId,userId)
        }
    }

}
