package dev.messenger.data.application

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.domain.model.Chat
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class DefaultGroupCreation(private val commands:RepositoryCommands):GroupCreation {
    override suspend fun groupCreate(title: String, description: String, userIds: List<String>):Chat = applicationCall { commands.execute(MoreCommands.GroupCreate,GroupCreateParams(title,description,userIds)).toModel() }
    override suspend fun groupInvite(chatId: String, userId: String):Unit {  commands.execute(MoreCommands.GroupInvite,MemberParams(chatId,userId)); }
    override suspend fun groupBotAdd(chatId: String, userId: String):Unit {  commands.execute(MoreCommands.GroupBotAdd,MemberParams(chatId,userId)); }
}
