@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package dev.messenger.feature.profile
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.core.ui.*
import dev.messenger.ui.resources.*

import androidx.compose.runtime.*
import androidx.compose.ui.interop.LocalUIViewController
import dev.messenger.domain.model.TransferSnapshot
import dev.messenger.domain.FileStorage
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.UIKit.*
import platform.UniformTypeIdentifiers.*
import platform.darwin.NSObject
import kotlin.uuid.Uuid

@Composable actual fun PlatformSettings() {
    androidx.compose.material3.Text(org.jetbrains.compose.resources.stringResource(dev.messenger.ui.resources.Res.string.background_ios))
}
