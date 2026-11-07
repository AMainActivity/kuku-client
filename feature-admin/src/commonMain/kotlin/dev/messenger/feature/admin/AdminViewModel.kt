package dev.messenger.feature.admin
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.core.ui.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.messenger.domain.AdminRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.Uuid

class AdminViewModel(private val repository: AdminRepository, private val profile: ProfileRepository) : ActionViewModel() {
    private val mutableConfirmation = MutableStateFlow(false)
    val confirmation = mutableConfirmation.asStateFlow()
    private val mutableConfirming = MutableStateFlow(false)
    val confirming = mutableConfirming.asStateFlow()
    private val mutableConfirmationError = MutableStateFlow<String?>(null)
    val confirmationError = mutableConfirmationError.asStateFlow()
    private var confirmationResult: CompletableDeferred<Unit>? = null

    private suspend fun <T> authorized(request: suspend () -> T): T {
        try {
            return request()
        } catch (failure: ApplicationFailure) {
            if (failure.code != "STEP_UP_REQUIRED") throw failure
        }
        val result = CompletableDeferred<Unit>()
        confirmationResult = result
        mutableConfirmationError.value = null
        mutableConfirmation.value = true
        try {
            result.await()
        } finally {
            confirmationResult = null
            mutableConfirmation.value = false
            result.cancel()
        }
        // Retry only this rejected request, once. Never replay a completed mutation.
        return request()
    }

    fun confirmAccess(code: String) {
        val result = confirmationResult ?: return
        if (mutableConfirming.value || code.length != 6 || code.any { it !in '0'..'9' }) return
        mutableConfirming.value = true
        mutableConfirmationError.value = null
        viewModelScope.launch {
            try {
                profile.mfaStepUp(code)
                result.complete(Unit)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableConfirmationError.value = failureCode(failure)
            } finally {
                mutableConfirming.value = false
            }
        }
    }

    fun cancelConfirmation() {
        if (!mutableConfirming.value) confirmationResult?.cancel()
    }

    private val mutableGroups=MutableStateFlow<List<AdminGroup>>(emptyList())
    val groups=mutableGroups.asStateFlow()
    private val mutableGroupDeletionSupported=MutableStateFlow(false)
    val groupDeletionSupported=mutableGroupDeletionSupported.asStateFlow()

    fun deleteGroup(group:AdminGroup,reason:String,done:()->Unit) = action {
        try {
            authorized {repository.adminGroupDelete(group.chatId,group.version,reason.trim())}
            mutableGroups.value=mutableGroups.value.filterNot {it.chatId==group.chatId}
            done()
        } catch(failure:ApplicationFailure) {
            if(failure.code=="VERSION_CONFLICT") {
                done()
                // Discard the outdated confirmation and reload before another destructive action.
                val page=authorized {repository.adminGroupsList(query=query)}
                mutableGroups.value=page.items;cursor=page.nextCursor;mutableMore.value=cursor!=null
            }
            throw failure
        }
    }
    private val mutableUsers = MutableStateFlow<List<AdminUser>>(emptyList())
    val users = mutableUsers.asStateFlow()
    private val mutableAdmissions = MutableStateFlow<List<AdmissionInfo>>(emptyList())
    val admissions = mutableAdmissions.asStateFlow()
    private val mutableBots = MutableStateFlow<List<DirectoryPerson>>(emptyList())
    val bots = mutableBots.asStateFlow()
    private val mutableAudit = MutableStateFlow<List<AuditEntry>>(emptyList())
    val audit = mutableAudit.asStateFlow()
    private val mutableMore = MutableStateFlow(false)
    val more = mutableMore.asStateFlow()
    private var cursor: String? = null
    private var query = ""
    fun load(tab: Int, search: String, append: Boolean = false) = action {
        if (!append) { cursor = null; query = search; mutableUsers.value = emptyList(); mutableAdmissions.value = emptyList(); mutableBots.value = emptyList(); mutableAudit.value = emptyList(); mutableGroups.value=emptyList() }
        when (tab) {
            0 -> authorized { repository.admissionsList(cursor,30,query) }.also { mutableAdmissions.value += it.items; cursor = it.nextCursor }
            1 -> authorized { repository.adminUsersList(cursor,30,query) }.also { mutableUsers.value += it.items; cursor = it.nextCursor }
            2 -> authorized { repository.botsList(cursor,30,query) }.also { mutableBots.value += it.items; cursor = it.nextCursor }
            4 -> {
                mutableGroupDeletionSupported.value=repository.supportsGroupDeletion()
                if(!mutableGroupDeletionSupported.value) throw ApplicationFailure("UNSUPPORTED_CONTRACT")
                authorized { repository.adminGroupsList(cursor,30,query) }.also {mutableGroups.value+=it.items;cursor=it.nextCursor}
            }
            else -> authorized { repository.adminAudit(cursor,30,query) }.also { mutableAudit.value += it.items; cursor = it.nextCursor }
        }
        mutableMore.value = cursor != null
        if(tab!=4) mutableGroupDeletionSupported.value=repository.supportsGroupDeletion()
    }
    fun admission(email: String, active: Boolean) = action {
        (if(active) authorized { repository.admissionsCreate(email) } else authorized { repository.admissionsRevoke(email) })
        mutableAdmissions.value = authorized { repository.admissionsList() }.items
    }
    fun ban(userId: String, reason: String, until: String?) = action {
        until?.let { kotlin.time.Instant.parse(it) }
        authorized { repository.adminBan(userId,reason,until) }
        mutableUsers.value = authorized { repository.adminUsersList() }.items
    }
    fun userAction(command: UserAdminAction, userId: String) = action {
        authorized { repository.changeUser(command,userId) }; mutableUsers.value = authorized { repository.adminUsersList() }.items
    }
    fun role(userId: String, role: String) = action {
        authorized { repository.adminRole(userId,role) }; mutableUsers.value = authorized { repository.adminUsersList() }.items
    }
    fun bot(handle: String, name: String, description: String, existing: DirectoryPerson? = null) = action {
        if(existing == null) authorized { repository.botsCreate(handle,name,description) }
        else authorized { repository.botsUpdate(existing.userId,existing.version,handle,name,description) }
        mutableBots.value = authorized { repository.botsList() }.items
    }
    fun disableBot(userId: String) = action {
        authorized { repository.botsDisable(userId) }; mutableBots.value = authorized { repository.botsList() }.items
    }
}

