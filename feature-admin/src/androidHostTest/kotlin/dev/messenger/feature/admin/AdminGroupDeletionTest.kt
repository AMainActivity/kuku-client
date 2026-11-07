package dev.messenger.feature.admin

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class AdminGroupDeletionTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun setup(){Dispatchers.setMain(dispatcher)}
    @After fun finish(){Dispatchers.resetMain()}
    private val group=AdminGroup("group","Title",7,"owner",2,"")
    private inline fun <reified T> fake(noinline block:(String,Array<out Any?>)->Any?):T =
        Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) {_,method,args ->
            try {block(method.name,args ?: emptyArray())} catch(e:ApplicationFailure) {
                @Suppress("UNCHECKED_CAST")
                (args.last() as Continuation<Any?>).resumeWith(Result.failure(e));COROUTINE_SUSPENDED
            }
        } as T

    @Test fun deletionWaitsForStepUpAndRetriesOnlyRejectedRequest()=runTest(dispatcher) {
        var confirmed=false
        var attempts=0
        var closed=false
        val model=AdminViewModel(fake<AdminRepository> {name,args -> when(name) {
            "adminGroupDelete" -> {
                attempts++
                assertEquals(listOf("group",7L,"reason"),args.take(3))
                if(!confirmed) throw ApplicationFailure("STEP_UP_REQUIRED")
                Unit
            }
            else -> error(name)
        }},fake<ProfileRepository> {_,_ -> confirmed=true;Unit})
        model.deleteGroup(group," reason "){closed=true};runCurrent()
        assertTrue(model.confirmation.value)
        assertFalse(closed)
        model.confirmAccess("123456");runCurrent()
        assertEquals(2,attempts)
        assertTrue(closed)
        assertNull(model.error.value)
    }

    @Test fun conflictRefreshesListButNeverDeletesNewVersion()=runTest(dispatcher) {
        var attempts=0
        var closed=false
        val newer=group.copy(version=8)
        val model=AdminViewModel(fake<AdminRepository> {name,_ -> when(name) {
            "adminGroupDelete" -> {attempts++;throw ApplicationFailure("VERSION_CONFLICT")}
            "adminGroupsList" -> Page(listOf(newer),null)
            else -> error(name)
        }},fake<ProfileRepository> {_,_ -> error("No step-up required")})
        model.deleteGroup(group,"reason"){closed=true};runCurrent()
        assertEquals(1,attempts)
        assertTrue(closed)
        assertEquals(listOf(newer),model.groups.value)
        assertEquals("VERSION_CONFLICT",model.error.value)
    }
}
