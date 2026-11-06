package dev.messenger.feature.conversation

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

fun conversationModule() = module {
    viewModel { parameters -> ChatViewModel(parameters[0], parameters[1], get(), get(), get()) }
}
