package dev.messenger.data.wire

object MoreCommands {
    val SessionsList = Command("sessions.list", false, EmptyParams.serializer(), Items.serializer(DeviceSession.serializer()))
    val SessionsRevoke = Command("sessions.revoke", true, SessionRevokeParams.serializer(), Revoked.serializer())
    val MfaSetup = Command("auth.mfa.setup", true, EmptyParams.serializer(), MfaEnrollment.serializer())
    val MfaConfirm = Command("auth.mfa.confirm", true, MfaParams.serializer(), MfaEnabled.serializer())
    val MfaStepUp = Command("auth.mfa.stepup", false, MfaParams.serializer(), MfaEnabled.serializer())
    val AdmissionsCreate = Command("admin.admissions.create", true, EmailParams.serializer(), AdmissionChanged.serializer())
    val AdmissionsRevoke = Command("admin.admissions.revoke", true, EmailParams.serializer(), AdmissionChanged.serializer())
    val AdmissionsList = Command("admin.admissions.list", false, PageParams.serializer(), Page.serializer(AdmissionInfo.serializer()))
    val AdminUsersList = Command("admin.users.list", false, PageParams.serializer(), Page.serializer(AdminUser.serializer()))
    val AdminBan = Command("admin.users.ban", true, BanParams.serializer(), UserUpdated.serializer())
    val AdminUnban = Command("admin.users.unban", true, DirectParams.serializer(), UserUpdated.serializer())
    val AdminDeactivate = Command("admin.users.deactivate", true, DirectParams.serializer(), UserUpdated.serializer())
    val AdminRole = Command("admin.users.setRole", true, UserRoleParams.serializer(), UserUpdated.serializer())
    val AdminRevoke = Command("admin.sessions.revoke", true, DirectParams.serializer(), UserUpdated.serializer())
    val AdminAudit = Command("admin.audit.list", false, PageParams.serializer(), Page.serializer(AuditEntry.serializer()))
    val UsersMe = Command("users.me", false, EmptyParams.serializer(), Me.serializer())
    val UsersGet = Command("users.get", false, DirectParams.serializer(), User.serializer())
    val UsersList = Command("users.list", false, PageParams.serializer(), Page.serializer(DirectoryPerson.serializer()))
    val UsersUpdate = Command("users.update", true, ProfileParams.serializer(), User.serializer())
    val EmailChangeStart = Command("users.email.start", true, EmailParams.serializer(), EmailChange.serializer())
    val EmailChangeVerify = Command("users.email.verify", true, EmailChangeParams.serializer(), EmailChanged.serializer())
    val UserBlock = Command("users.block", true, DirectParams.serializer(), UserBlocked.serializer())
    val UserUnblock = Command("users.unblock", true, DirectParams.serializer(), UserBlocked.serializer())
    val PreferencesSet = Command("chats.preferences.set", true, PreferenceParams.serializer(), Chat.serializer())
    val GroupCreate = Command("groups.create", true, GroupCreateParams.serializer(), Chat.serializer())
    val GroupUpdate = Command("groups.update", true, GroupUpdateParams.serializer(), Chat.serializer())
    val MembersList = Command("groups.members.list", false, ChatPageParams.serializer(), Page.serializer(MemberInfo.serializer()))
    val GroupInvite = Command("groups.invite", true, MemberParams.serializer(), GroupChanged.serializer())
    val GroupAccept = Command("groups.accept", true, ChatParams.serializer(), GroupChanged.serializer())
    val GroupLeave = Command("groups.leave", true, ChatParams.serializer(), GroupChanged.serializer())
    val GroupRemove = Command("groups.remove", true, MemberParams.serializer(), GroupChanged.serializer())
    val GroupBan = Command("groups.ban", true, MemberParams.serializer(), GroupChanged.serializer())
    val GroupUnban = Command("groups.unban", true, MemberParams.serializer(), GroupChanged.serializer())
    val GroupRole = Command("groups.setRole", true, MemberRoleParams.serializer(), GroupChanged.serializer())
    val GroupTransfer = Command("groups.transferOwner", true, MemberParams.serializer(), GroupChanged.serializer())
    val MessagesGet = Command("messages.get", false, MessageParams.serializer(), Message.serializer())
    val MessagesEdit = Command("messages.edit", true, EditParams.serializer(), Message.serializer())
    val MessagesDelete = Command("messages.delete", true, DeleteParams.serializer(), Message.serializer())
    val MessagesForward = Command("messages.forward", true, ForwardParams.serializer(), Items.serializer(Message.serializer()))
    val ReceiptsSet = Command("receipts.set", true, ReceiptParams.serializer(), ReceiptAccepted.serializer())
    val ReceiptsList = Command("receipts.list", false, ChatPageParams.serializer(), Page.serializer(MemberInfo.serializer()))
    val UploadsCreate = Command("uploads.create", true, UploadCreateParams.serializer(), UploadInfo.serializer())
    val UploadsStatus = Command("uploads.status", false, UploadParams.serializer(), UploadStatus.serializer())
    val UploadsComplete = Command("uploads.complete", true, UploadParams.serializer(), UploadInfo.serializer())
    val UploadsCancel = Command("uploads.cancel", true, UploadParams.serializer(), CancelledUpload.serializer())
    val BotsCreate = Command("admin.bots.create", true, BotCreateParams.serializer(), User.serializer())
    val BotsUpdate = Command("admin.bots.update", true, BotUpdateParams.serializer(), User.serializer())
    val BotsDisable = Command("admin.bots.disable", true, DirectParams.serializer(), User.serializer())
    val BotsList = Command("bots.list", false, PageParams.serializer(), Page.serializer(DirectoryPerson.serializer()))
    val GroupBotAdd = Command("groups.bots.add", true, MemberParams.serializer(), GroupChanged.serializer())
    val GroupBotRemove = Command("groups.bots.remove", true, MemberParams.serializer(), GroupChanged.serializer())
    val GroupDelete = Command("groups.delete",true,GroupDeleteParams.serializer(),GroupDeleted.serializer())
    val AdminGroupDelete = Command("admin.groups.delete",true,AdminGroupDeleteParams.serializer(),GroupDeleted.serializer())
    val AdminGroupsList = Command("admin.groups.list",false,PageParams.serializer(),Page.serializer(AdminGroup.serializer()))
    val all = listOf(GroupDelete,AdminGroupDelete,AdminGroupsList,
        SessionsList,
        SessionsRevoke,
        MfaSetup,
        MfaConfirm,
        MfaStepUp,
        AdmissionsCreate,
        AdmissionsRevoke,
        AdmissionsList,
        AdminUsersList,
        AdminBan,
        AdminUnban,
        AdminDeactivate,
        AdminRole,
        AdminRevoke,
        AdminAudit,
        UsersMe,
        UsersGet,
        UsersList,
        UsersUpdate,
        EmailChangeStart,
        EmailChangeVerify,
        UserBlock,
        UserUnblock,
        PreferencesSet,
        GroupCreate,
        GroupUpdate,
        MembersList,
        GroupInvite,
        GroupAccept,
        GroupLeave,
        GroupRemove,
        GroupBan,
        GroupUnban,
        GroupRole,
        GroupTransfer,
        MessagesGet,
        MessagesEdit,
        MessagesDelete,
        MessagesForward,
        ReceiptsSet,
        ReceiptsList,
        UploadsCreate,
        UploadsStatus,
        UploadsComplete,
        UploadsCancel,
        BotsCreate,
        BotsUpdate,
        BotsDisable,
        BotsList,
        GroupBotAdd,
        GroupBotRemove
    )
}
