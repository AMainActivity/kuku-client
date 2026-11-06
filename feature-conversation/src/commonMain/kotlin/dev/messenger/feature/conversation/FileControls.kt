package dev.messenger.feature.conversation
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.core.ui.*

import androidx.compose.runtime.Composable
import dev.messenger.domain.model.TransferSnapshot

@Composable expect fun rememberFilePicker(model: ChatViewModel): () -> Unit
@Composable expect fun rememberFileOpener(model: ChatViewModel): (TransferSnapshot) -> Unit


@Composable expect fun rememberShareLauncher(model:ChatViewModel):(ShareBundle)->Unit

