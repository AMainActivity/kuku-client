package dev.messenger.feature.groups

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.messenger.core.ui.*
import dev.messenger.domain.model.DirectoryPerson
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

private const val SEARCH_DEBOUNCE_MILLIS = 300L

@Composable
internal fun UnbanMemberDialog(account: String, chatId: String, dismiss: () -> Unit) {
    val model = koinViewModel<UnbanMemberViewModel>(key = "unban:$account:$chatId")
    val people by model.people.collectAsStateWithLifecycle()
    val loading by model.loading.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val more by model.more.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var bots by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<DirectoryPerson?>(null) }
    val list = rememberLazyListState()
    DisposableEffect(model) { onDispose { model.stopSearch() } }
    LaunchedEffect(query, bots) {
        model.stopSearch()
        if (query.isNotBlank()) kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MILLIS)
        model.search(query, bots)
    }
    LaunchedEffect(list, people.size, more, loading, error) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index }.collect { last ->
            if (last != null && last >= people.size - 4 && more && !loading && error == null) model.search(query, bots, true)
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() },
        title = { Text(stringResource(Res.string.unban_member)) },
        text = { Column {
            Text(stringResource(Res.string.unban_choose_person))
            Field(query, { query = it }, Res.string.search_people)
            Row {
                Checkbox(bots, { bots = it }, enabled = !busy)
                Text(stringResource(Res.string.unban_search_bots))
            }
            ErrorNotice(error)
            if (error != null) Action(Res.string.retry, !busy) { model.search(query, bots) }
            if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(Modifier.heightIn(max = 320.dp), state = list) {
                items(people, key = { it.userId }) { person ->
                    ListItem(headlineContent = { Text(person.displayName) },
                        supportingContent = { Text(person.description) },
                        modifier = Modifier.clickable(enabled = !busy) { selected = person })
                }
            }
        } }, confirmButton = { Action(Res.string.back, !busy, dismiss) })
    selected?.let { person ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(person.displayName) },
            text = { Text(stringResource(Res.string.unban_member)) },
            confirmButton = { Action(Res.string.confirm, !busy) { selected = null; model.unban(chatId, person.userId, dismiss) } },
            dismissButton = { Action(Res.string.cancel) { selected = null } })
    }
}
