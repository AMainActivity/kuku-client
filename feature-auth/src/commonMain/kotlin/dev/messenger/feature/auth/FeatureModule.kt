package dev.messenger.feature.auth

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

fun authModule() = module {
    viewModel { val coordinator=get<dev.messenger.domain.SessionAccess>(); SetupViewModel(get(),coordinator::prepareLogin) }
    viewModel { AuthViewModel(get()) }
}
