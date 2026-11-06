package dev.messenger.data.application

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.domain.model.Page
import dev.messenger.domain.model.DirectoryPerson
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal class DefaultDirectoryCatalog(private val commands:RepositoryCommands):DirectoryCatalog {
    override suspend fun usersList(cursor: String?, limit: Int, query: String):Page<DirectoryPerson> = applicationCall { commands.execute(MoreCommands.UsersList,PageParams(cursor,limit,query)).let {Page(it.items.map {row -> row.toModel()},it.nextCursor)} }
    override suspend fun botsList(cursor: String?, limit: Int, query: String):Page<DirectoryPerson> = applicationCall { commands.execute(MoreCommands.BotsList,PageParams(cursor,limit,query)).let {Page(it.items.map {row -> row.toModel()},it.nextCursor)} }
}
