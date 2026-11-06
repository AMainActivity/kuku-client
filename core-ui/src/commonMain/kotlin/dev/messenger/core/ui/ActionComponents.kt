package dev.messenger.core.ui
import dev.messenger.domain.*
import dev.messenger.domain.model.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable fun ActionPage(title: StringResource, model: ActionViewModel, back: ()->Unit, content: @Composable ColumnScope.()->Unit) {
    val busy by model.busy.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    AppPage(stringResource(title),back) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=12.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ErrorNotice(error)
            content()
        }
    }
}
@Composable fun Field(value: String, change: (String)->Unit, label: StringResource) =
    OutlinedTextField(value,change,label={Text(stringResource(label))},modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.medium)
@Composable fun Action(label: StringResource, enabled: Boolean = true, run: ()->Unit) =
    TextButton(onClick=run,enabled=enabled) { Text(stringResource(label)) }
@Composable fun role(value: String) = stringResource(when(value) {
    "owner" -> Res.string.role_owner
    "admin" -> Res.string.role_admin
    else -> Res.string.role_member
})


@Composable fun PrimaryAction(label:StringResource,enabled:Boolean=true,run:()->Unit) {
    Button(onClick=run,enabled=enabled,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {Text(stringResource(label))}
}
