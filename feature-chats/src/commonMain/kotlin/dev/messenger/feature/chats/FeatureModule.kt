package dev.messenger.feature.chats

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

fun chatsModule() = module {
    viewModel { IncomingShareViewModel(get(),get(),get()) }
    viewModel { ChatsViewModel(get(), get(), get(), get()) }
    viewModel { DirectoryViewModel(get()) }
}
