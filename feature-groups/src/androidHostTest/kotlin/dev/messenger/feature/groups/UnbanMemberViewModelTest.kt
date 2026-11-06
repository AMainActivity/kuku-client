package dev.messenger.feature.groups

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class UnbanMemberViewModelTest {
    private inline fun <reified T> fake(noinline call:(String,Array<out Any?>)->Any?):T =
        Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) {_,method,args -> call(method.name,args ?: emptyArray())} as T

    @Test fun bannedUserCanBeSelectedFromDirectoryWithoutBeingInMembers()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val person=DirectoryPerson("banned-user","Person","human",null,"",null,1)
            val directory=fake<DirectoryCatalog> {name,_ ->
                check(name=="usersList"); Page(listOf(person),null)
            }
            val calls=mutableListOf<String>()
            val groups=fake<GroupRepository> {name,args ->
                calls+=name
                if(name=="groupUnban") {assertEquals("chat",args[0]);assertEquals(person.userId,args[1])}
                Unit
            }
            val model=UnbanMemberViewModel(directory,groups)
            model.search("Person",false);runCurrent()
            assertEquals(listOf(person),model.people.value)
            var completed=false
            model.unban("chat",model.people.value.single().userId) {completed=true}
            runCurrent()
            assertEquals(listOf("groupUnban","synchronize"),calls)
            assertTrue(completed)
            assertNull(model.error.value)
        } finally {Dispatchers.resetMain()}
    }
}
