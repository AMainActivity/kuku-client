package dev.messenger.data.application

import dev.messenger.data.ConversationRepository
import dev.messenger.domain.*

/** Composition of independent adapters. Shared persistence/session remain owned by AppRuntime. */
class ApplicationRepositories(source:ConversationRepository, session:SessionAccess) {
    private val commands=RepositoryCommands({session.state.value.account},object:CommandExecutor {
        override suspend fun <P,R> execute(command:dev.messenger.data.wire.Command<P,R>,params:P,operationId:kotlin.uuid.Uuid?):R =
            source.execute(command,params,operationId)
    })
    private val catalog=DefaultChatCatalog(source,session,commands)
    private val directoryCatalog=DefaultDirectoryCatalog(commands)
    private val groupCreation=DefaultGroupCreation(commands)
    val messaging:MessagingRepository=DefaultMessagingRepository(source,catalog,commands)
    val directory:DirectoryRepository=DefaultDirectoryRepository(catalog,directoryCatalog,groupCreation)
    val groups:GroupRepository=DefaultGroupRepository(catalog,groupCreation,commands)
    val profile:ProfileRepository=DefaultProfileRepository(source,commands)
    val admin:AdminRepository=DefaultAdminRepository(directoryCatalog,commands)
}
