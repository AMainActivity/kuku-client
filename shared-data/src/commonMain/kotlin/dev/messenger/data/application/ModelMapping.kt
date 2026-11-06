package dev.messenger.data.application

import dev.messenger.data.wire.*

internal fun Attachment.toModel()=dev.messenger.domain.model.Attachment(attachmentId,filename,mime,size,sha256,status)
internal fun User.toModel()=dev.messenger.domain.model.User(userId,displayName,kind,handle,description,avatarId,version,deleted)
internal fun Membership.toModel()=dev.messenger.domain.model.Membership(role,status,joinedSeq,version)
internal fun Preferences.toModel()=dev.messenger.domain.model.Preferences(pinned,pinRank,muted,version)
internal fun Chat.toModel()=dev.messenger.domain.model.Chat(chatId,kind,title,description,avatarId,version,membership.toModel(),lastMessage?.toModel(),unreadCount,peer?.toModel(),preferences.toModel())
internal fun History.toModel()=dev.messenger.domain.model.History(items.map {it.toModel()},beforeSeq,afterSeq,hasMoreBefore,hasMoreAfter)
internal fun DirectoryPerson.toModel()=dev.messenger.domain.model.DirectoryPerson(userId,displayName,kind,handle,description,avatarId,version)
internal fun Me.toModel()=dev.messenger.domain.model.Me(userId,displayName,kind,handle,description,avatarId,version,deleted,email,role,mfaEnabled)
internal fun MemberInfo.toModel()=dev.messenger.domain.model.MemberInfo(userId,displayName,kind,handle,role,status,version,deliveredSeq,readSeq)
internal fun DeviceSession.toModel()=dev.messenger.domain.model.DeviceSession(sessionId,deviceId,deviceName,createdAt,revoked)
internal fun AdmissionInfo.toModel()=dev.messenger.domain.model.AdmissionInfo(email,active,activatedUserId,createdAt)
internal fun AdminUser.toModel()=dev.messenger.domain.model.AdminUser(userId,email,displayName,role,status,banUntil,version)
internal fun AuditEntry.toModel()=dev.messenger.domain.model.AuditEntry(id,actorId,action,target,details.toString(),createdAt)
internal fun MfaEnrollment.toModel()=dev.messenger.domain.model.MfaEnrollment(secret,uri)
internal fun EmailChange.toModel()=dev.messenger.domain.model.EmailChange(changeId,expiresAt,message)
internal fun Challenge.toModel()=dev.messenger.domain.model.Challenge(challengeId,expiresAt,resendAfterSeconds,message)
internal fun Message.toModel()=dev.messenger.domain.model.Message(messageId,clientMessageId,chatId,senderId,seq,version,plainTextOrNull(),replyTo,deleted,createdAt,editedAt,attachments.map {it.toModel()})
internal fun dev.messenger.data.TransferEntity.toModel()=dev.messenger.domain.model.TransferSnapshot(accountId,jobId,chatId,direction,filename,mime,size,sha256,state,remoteId,progress,error,purpose)
internal fun dev.messenger.domain.model.Attachment.toWire()=Attachment(attachmentId,filename,mime,size,sha256,status)
