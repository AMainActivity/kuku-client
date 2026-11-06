package dev.messenger.feature.chats
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.core.ui.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.messenger.domain.DirectoryRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.uuid.Uuid

class DirectoryViewModel(private val repository: DirectoryRepository) : ActionViewModel() {
    @OptIn(ExperimentalCoroutinesApi::class)
    val existingChats=repository.connection.map {it.account?.key}.distinctUntilChanged().flatMapLatest {key ->
        if(key==null) flowOf(emptyList()) else repository.chats(key)
    }.stateIn(viewModelScope,SharingStarted.WhileSubscribed(5000),emptyList())
    private val mutablePeople = MutableStateFlow<List<DirectoryPerson>>(emptyList())
    val people = mutablePeople.asStateFlow()
    private val mutableHasMore = MutableStateFlow(false)
    val hasMore = mutableHasMore.asStateFlow()
    private var cursor: String? = null
    private var query = ""
    fun search(value: String, more: Boolean = false, bots: Boolean = false) = action {
        if (!more) { cursor = null; query = value; mutablePeople.value = emptyList() }
        val page = (if (bots) repository.botsList(cursor, 30, query) else repository.usersList(cursor, 30, query))
        mutablePeople.value = (mutablePeople.value + page.items).distinctBy { it.userId }
        if(more && page.nextCursor!=null && page.nextCursor==cursor) throw dev.messenger.domain.ApplicationFailure("CATALOG_CURSOR_LOOP")
        cursor = page.nextCursor; mutableHasMore.value = cursor != null
    }
    fun direct(userId: String, opened: (String)->Unit) = action {
        opened(repository.chatsDirect(userId).chatId)
    }
    fun group(title: String, users: Set<String>, opened: (String)->Unit) = action {
        opened(repository.groupCreate(title, userIds = users.toList()).chatId)
    }
    fun invite(chatId: String, users: Set<String>, bots: Boolean, done: ()->Unit) = action {
        for (userId in users) (if (bots) repository.groupBotAdd(chatId,userId) else repository.groupInvite(chatId,userId))
        repository.synchronize(); done()
    }
}

