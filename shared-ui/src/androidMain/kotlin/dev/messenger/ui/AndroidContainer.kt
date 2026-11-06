package dev.messenger.ui
import dev.messenger.runtime.*
import dev.messenger.core.ui.*
import dev.messenger.feature.chats.*
import dev.messenger.feature.auth.*
import dev.messenger.feature.profile.*
import dev.messenger.feature.conversation.*
import dev.messenger.feature.groups.*
import dev.messenger.feature.admin.*


import android.content.Context
import dev.messenger.data.*

fun androidContainer(context: Context): AppContainer =
    AppContainer(androidLocalStore(context), androidNetwork(), AndroidSecureStore(context), ManagedFiles(java.io.File(context.filesDir, "attachments").absolutePath), androidUiSettings(context), AndroidBackgroundConnection(context), onAccountRemoved={ MessageNotifications.clearAccount(context,it) }).also { AndroidRuntime.container = it.runtime }

