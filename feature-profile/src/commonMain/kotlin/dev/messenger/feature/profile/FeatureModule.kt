package dev.messenger.feature.profile

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

fun profileModule() = module {
    viewModel { AccountViewModel(get(),get(),get()) }
}
