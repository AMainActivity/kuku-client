package dev.messenger.feature.groups
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.core.ui.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.messenger.domain.GroupRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.Uuid

class GroupViewModel(val accountKey: String, val chatId: String, private val repository: GroupRepository, val transfers:dev.messenger.domain.AttachmentRepository) : ActionViewModel() {
    data class Deletion(val chatId:String,val title:String,val version:Long,val onlyIfLastHuman:Boolean)
    private val mutableDeletion=MutableStateFlow<Deletion?>(null)
    val deletion=mutableDeletion.asStateFlow()
    private val mutableDeletionSupported=MutableStateFlow(false)
    val deletionSupported=mutableDeletionSupported.asStateFlow()
    fun cancelDeletion() { if(!busy.value) mutableDeletion.value=null }
    fun prepareDeletion(onlyIfLastHuman:Boolean) = action {
        if(!repository.supportsGroupDeletion()) throw ApplicationFailure("UNSUPPORTED_CONTRACT")
        val current=repository.chatsGet(chatId)
        if(current.membership.role!="owner" || current.membership.status!="active") throw ApplicationFailure("FORBIDDEN")
        if(onlyIfLastHuman) {
            var next:String?=null
            val seen=mutableSetOf<String>()
            do {
                val page=repository.membersList(chatId,next,100)
                if(page.items.any {it.kind!="bot" && it.status=="active" && it.userId!=accountKey.substringAfter(':')})
                    throw ApplicationFailure("TRANSFER_GROUP_OWNERSHIP_FIRST")
                next=page.nextCursor
                if(next!=null && !seen.add(next)) throw ApplicationFailure("MEMBER_CURSOR_LOOP")
            } while(next!=null)
        }
        mutableDeletion.value=Deletion(chatId,current.title,current.version,onlyIfLastHuman)
    }
    fun confirmDeletion() = action {
        val request=mutableDeletion.value ?: return@action
        try {
            repository.groupDelete(request.chatId,request.version,request.onlyIfLastHuman)
            mutableDeletion.value=null
        } catch(failure:ApplicationFailure) {
            if(failure.code in setOf("VERSION_CONFLICT","GROUP_NOT_EMPTY","FORBIDDEN")) {
                mutableDeletion.value=null
                // A fresh version needs a new explicit confirmation; never silently retry deletion.
                try {repository.chatsGet(chatId)} catch(cancelled:CancellationException) {throw cancelled} catch(_:Exception) { }
            }
            throw failure
        }
    }
    val connection=repository.connection
    fun direct(userId:String,opened:(String)->Unit)=action {opened(repository.newDirect(userId).chatId)}
    val avatarJobs=transfers.observe(accountKey,chatId).map { jobs -> jobs.filter { it.purpose=="avatar" } }
        .stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    fun avatarPicked(id:String,name:String,mime:String) = action {
        transfers.registerUpload(accountKey,chatId,id,name,mime,"avatar")
    }
    fun avatar(id:String?) = action {
        val group=requireNotNull(chat.value)
        repository.groupUpdate(chatId,group.version,group.title,group.description,id)
    }
    fun avatarTransfer(job:dev.messenger.domain.model.TransferSnapshot,cancel:Boolean) = action {
        if(cancel) transfers.cancel(accountKey,job.jobId) else transfers.resume(accountKey,job.jobId)
    }
    val chat = repository.chat(accountKey, chatId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    @OptIn(ExperimentalCoroutinesApi::class)
    val avatarImage = chat.map { it?.avatarId ?: it?.peer?.avatarId }.distinctUntilChanged().mapLatest { id ->
        if(id==null) null else try { transfers.avatarBytes(accountKey,id) }
        catch(cancelled:CancellationException) { throw cancelled } catch(_:Exception) { null }
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),null)
    val members = repository.members(accountKey, chatId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutableHasMore = MutableStateFlow(false)
    val hasMore = mutableHasMore.asStateFlow()
    private var cursor: String? = null

    fun refresh(more: Boolean = false) = action {
        mutableDeletionSupported.value=repository.supportsGroupDeletion()
        val value=repository.chatsGet(chatId)
        if(value.kind!="group" || value.membership.status!="active") {mutableHasMore.value=false;return@action}
        if (!more) cursor = null
        val page = repository.membersList(chatId,cursor)
        if(more && page.nextCursor!=null && page.nextCursor==cursor) throw ApplicationFailure("MEMBER_CURSOR_LOOP")
        cursor = page.nextCursor; mutableHasMore.value = cursor != null
    }
    fun update(title: String, description: String) = action {
        val value = requireNotNull(chat.value)
        repository.groupUpdate(chatId,value.version,title,description,value.avatarId)
    }
    fun preferences(pinned: Boolean, muted: Boolean, rank: Int) = action {
        val value = requireNotNull(chat.value)
        repository.preferencesSet(chatId,value.preferences.version,pinned,rank,muted)
    }
    fun accept() = action { repository.groupAccept(chatId); repository.synchronize() }
    fun leave(done: ()->Unit) = action { repository.groupLeave(chatId); repository.synchronize(); done() }
    fun member(command: MemberAction, userId: String) = action {
        repository.changeMember(command,chatId,userId)
        repository.synchronize()
        repository.chatsGet(chatId)
    }
    fun role(userId: String, role: String) = action {
        repository.groupRole(chatId,userId,role)
        repository.synchronize()
    }
    fun block(userId: String, blocked: Boolean) = action {
        (if (blocked) repository.userBlock(userId) else repository.userUnblock(userId))
    }

}

