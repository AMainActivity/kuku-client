package dev.messenger.data.application

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class DefaultDirectoryRepository(catalog:ChatCatalog,directory:DirectoryCatalog,creation:GroupCreation):DirectoryRepository,ChatCatalog by catalog,DirectoryCatalog by directory,GroupCreation by creation {

}
