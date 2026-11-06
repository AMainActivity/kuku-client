package dev.messenger.feature.groups

import androidx.lifecycle.viewModelScope
import dev.messenger.core.ui.ActionViewModel
import dev.messenger.domain.*
import dev.messenger.domain.model.DirectoryPerson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class UnbanMemberViewModel(private val directory: DirectoryCatalog, private val groups: GroupRepository) : ActionViewModel() {
    private val mutablePeople = MutableStateFlow<List<DirectoryPerson>>(emptyList())
    val people = mutablePeople.asStateFlow()
    private val mutableLoading = MutableStateFlow(false)
    val loading = mutableLoading.asStateFlow()
    private val mutableMore = MutableStateFlow(false)
    val more = mutableMore.asStateFlow()
    private var cursor: String? = null
    private var searchJob: Job? = null

    fun search(query: String, bots: Boolean, next: Boolean = false) {
        if (next && (mutableLoading.value || !mutableMore.value || mutableError.value != null)) return
        searchJob?.cancel()
        if (!next) { cursor = null; mutablePeople.value = emptyList(); mutableMore.value = false }
        mutableLoading.value = true
        mutableError.value = null
        searchJob = viewModelScope.launch {
            try {
                val before = cursor
                val page = if (bots) directory.botsList(cursor, 30, query) else directory.usersList(cursor, 30, query)
                ensureActive()
                if (page.nextCursor != null && page.nextCursor == before) throw ApplicationFailure("CATALOG_CURSOR_LOOP")
                mutablePeople.value = (mutablePeople.value + page.items).distinctBy { it.userId }
                cursor = page.nextCursor
                mutableMore.value = cursor != null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableError.value = failureCode(failure) }
            finally { if (isActive) mutableLoading.value = false }
        }
    }
    fun stopSearch() { searchJob?.cancel(); mutableLoading.value = false }
    fun unban(chatId: String, userId: String, done: () -> Unit) = action {
        groups.groupUnban(chatId, userId)
        groups.synchronize()
        done()
    }
}
