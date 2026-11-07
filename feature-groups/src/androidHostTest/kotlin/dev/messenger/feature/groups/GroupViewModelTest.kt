package dev.messenger.feature.groups

import dev.messenger.domain.*
import dev.messenger.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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
class GroupViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun setup(){Dispatchers.setMain(dispatcher)}
    @After fun finish(){Dispatchers.resetMain()}
    private val chat=Chat("group","group","Title","",null,7,Membership("owner","active",1,1),null,0,null,Preferences(false,0,false,0))
    private inline fun <reified T> fake(noinline block:(String,Array<out Any?>)->Any?):T =
        Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) {_,method,args ->
            try {block(method.name,args ?: emptyArray())} catch(e:ApplicationFailure) {
                @Suppress("UNCHECKED_CAST")
                (args.last() as Continuation<Any?>).resumeWith(Result.failure(e));COROUTINE_SUSPENDED
            }
        } as T
    private fun model(members:List<MemberInfo>,delete:(Array<out Any?>)->Unit={}):GroupViewModel {
        val repository=fake<GroupRepository> {name,args -> when(name) {
            "getConnection" -> MutableStateFlow(SessionState())
            "chat" -> flowOf(chat)
            "members" -> flowOf(members)
            "supportsGroupDeletion" -> true
            "chatsGet" -> chat
            "membersList" -> Page(members,null)
            "groupDelete" -> {delete(args);Unit}
            else -> error(name)
        }}
        return GroupViewModel("server:self","group",repository,fake<AttachmentRepository> {_,_ -> flowOf(emptyList<TransferSnapshot>())})
    }
    private fun member(id:String,kind:String="human",status:String="active")=MemberInfo(id,id,kind,null,"member",status,1,0,0)
    @Test fun ownerWithOtherHumanMustTransferBeforeLeaving()=runTest(dispatcher) {
        var deletes=0
        val model=model(listOf(member("self"),member("other"))){deletes++}
        model.prepareDeletion(true);runCurrent()
        assertNull(model.deletion.value)
        assertEquals("TRANSFER_GROUP_OWNERSHIP_FIRST",model.error.value)
        assertEquals(0,deletes)
    }
    @Test fun botsAndInvitationsDoNotPreventLastHumanDeletion()=runTest(dispatcher) {
        var args:List<Any?>?=null
        val model=model(listOf(member("self"),member("bot","bot"),member("invited",status="invited"))){args=it.toList()}
        model.prepareDeletion(true);runCurrent()
        assertNotNull(model.deletion.value)
        model.confirmDeletion();runCurrent()
        assertEquals(listOf("group",7L,true),args?.take(3))
        assertNull(model.deletion.value)
    }
    @Test fun conflictRequiresNewConfirmationAndDoesNotRetry()=runTest(dispatcher) {
        var deletes=0
        val model=model(emptyList()){deletes++;throw ApplicationFailure("VERSION_CONFLICT")}
        model.prepareDeletion(false);runCurrent()
        model.confirmDeletion();runCurrent()
        assertEquals(1,deletes)
        assertNull(model.deletion.value)
        assertEquals("VERSION_CONFLICT",model.error.value)
    }
}
