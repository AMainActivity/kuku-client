package dev.messenger.ui
import dev.messenger.runtime.*
import dev.messenger.core.ui.*
import dev.messenger.feature.chats.*
import dev.messenger.feature.auth.*
import dev.messenger.feature.profile.*
import dev.messenger.feature.conversation.*
import dev.messenger.feature.groups.*
import dev.messenger.feature.admin.*


import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.ComposeUIViewController
import dev.messenger.data.*

fun MainViewController() = ComposeUIViewController {
    val container = remember { AppContainer(iosLocalStore(), iosNetwork(), IosSecureStore(), iosManagedFiles(), iosUiSettings()) }
    DisposableEffect(container) { onDispose { container.close() } }
    App(container)
}
