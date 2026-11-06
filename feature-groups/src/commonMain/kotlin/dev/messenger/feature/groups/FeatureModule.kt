package dev.messenger.feature.groups

import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

fun groupsModule() = module {
    viewModel { parameters -> GroupViewModel(parameters[0], parameters[1], get(), get()) }
}
