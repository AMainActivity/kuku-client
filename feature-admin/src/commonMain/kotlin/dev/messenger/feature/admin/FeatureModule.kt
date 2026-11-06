package dev.messenger.feature.admin

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

fun adminModule() = module {
    viewModel { AdminViewModel(get(), get()) }
}
