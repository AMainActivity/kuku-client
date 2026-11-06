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

class AdminViewModel(private val repository: AdminRepository) : ActionViewModel() {
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
        if (!append) { cursor = null; query = search; mutableUsers.value = emptyList(); mutableAdmissions.value = emptyList(); mutableBots.value = emptyList(); mutableAudit.value = emptyList() }
        when (tab) {
            0 -> repository.admissionsList(cursor,30,query).also { mutableAdmissions.value += it.items; cursor = it.nextCursor }
            1 -> repository.adminUsersList(cursor,30,query).also { mutableUsers.value += it.items; cursor = it.nextCursor }
            2 -> repository.botsList(cursor,30,query).also { mutableBots.value += it.items; cursor = it.nextCursor }
            else -> repository.adminAudit(cursor,30,query).also { mutableAudit.value += it.items; cursor = it.nextCursor }
        }
        mutableMore.value = cursor != null
    }
    fun admission(email: String, active: Boolean) = action {
        (if(active) repository.admissionsCreate(email) else repository.admissionsRevoke(email))
        mutableAdmissions.value = repository.admissionsList().items
    }
    fun ban(userId: String, reason: String, until: String?) = action {
        until?.let { kotlin.time.Instant.parse(it) }
        repository.adminBan(userId,reason,until)
        mutableUsers.value = repository.adminUsersList().items
    }
    fun userAction(command: UserAdminAction, userId: String) = action {
        repository.changeUser(command,userId); mutableUsers.value = repository.adminUsersList().items
    }
    fun role(userId: String, role: String) = action {
        repository.adminRole(userId,role); mutableUsers.value = repository.adminUsersList().items
    }
    fun bot(handle: String, name: String, description: String, existing: DirectoryPerson? = null) = action {
        if(existing == null) repository.botsCreate(handle,name,description)
        else repository.botsUpdate(existing.userId,existing.version,handle,name,description)
        mutableBots.value = repository.botsList().items
    }
    fun disableBot(userId: String) = action {
        repository.botsDisable(userId); mutableBots.value = repository.botsList().items
    }
}

