package dev.messenger.data.application

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.domain.model.Items
import dev.messenger.domain.model.Me
import dev.messenger.domain.model.DeviceSession
import dev.messenger.domain.model.MfaEnrollment
import dev.messenger.domain.model.EmailChange
import dev.messenger.data.ConversationRepository
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class DefaultProfileRepository(private val source:ConversationRepository,private val commands:RepositoryCommands):ProfileRepository {
    override fun profile(accountId:String)=source.profile(accountId).map {it?.toModel()}
    override suspend fun logout()=applicationCall {source.logout()}
    override suspend fun sessionsList():Items<DeviceSession> = applicationCall { commands.execute(MoreCommands.SessionsList,EmptyParams()).let {Items(it.items.map {row -> row.toModel()})} }
    override suspend fun sessionsRevoke(sessionId: String?):Unit {  commands.execute(MoreCommands.SessionsRevoke,SessionRevokeParams(sessionId)); }
    override suspend fun mfaSetup():MfaEnrollment = applicationCall { commands.execute(MoreCommands.MfaSetup,EmptyParams()).toModel() }
    override suspend fun mfaConfirm(code: String):Unit {  commands.execute(MoreCommands.MfaConfirm,MfaParams(code)); }
    override suspend fun mfaStepUp(code: String):Unit {  commands.execute(MoreCommands.MfaStepUp,MfaParams(code)); }
    override suspend fun usersMe():Me = applicationCall { commands.execute(MoreCommands.UsersMe,EmptyParams()).toModel() }
    override suspend fun usersUpdate(expectedVersion: Long, displayName: String, avatarId: String?):Unit {  commands.execute(MoreCommands.UsersUpdate,ProfileParams(expectedVersion,displayName,avatarId)); }
    override suspend fun emailChangeStart(email: String):EmailChange = applicationCall { commands.execute(MoreCommands.EmailChangeStart,EmailParams(email)).toModel() }
    override suspend fun emailChangeVerify(changeId: String, oldCode: String, newCode: String, mfaCode: String?):Unit {  commands.execute(MoreCommands.EmailChangeVerify,EmailChangeParams(changeId,oldCode,newCode,mfaCode)); }
}
