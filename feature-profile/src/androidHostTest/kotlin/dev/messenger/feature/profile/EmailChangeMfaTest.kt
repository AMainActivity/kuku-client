package dev.messenger.feature.profile

import androidx.lifecycle.ViewModelStore
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class EmailChangeMfaTest {
    private inline fun <reified T> fake(noinline call:(String,Array<out Any?>)->Any?):T =
        Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) {_,method,args -> call(method.name,args ?: emptyArray())} as T

    @Test fun enrolledAccountRequiresAndPassesTotpForEmailChange()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner=ViewModelStore()
        try {
            val me=Me("user","Name","human",null,"",null,1,false,"old@example.com","member",true)
            val session=fake<SessionAccess> {name,_ ->
                check(name=="getState"); MutableStateFlow(SessionState(true,ActiveAccount("server","user","session"),true))
            }
            val verified=mutableListOf<String?>()
            val profile=fake<ProfileRepository> {name,args -> when(name) {
                "profile" -> flowOf(me)
                "usersMe" -> me
                "sessionsList" -> Items<DeviceSession>(emptyList())
                "emailChangeStart" -> EmailChange("change","expiry","Codes sent")
                "emailChangeVerify" -> {verified+=args[3] as String?;Unit}
                "logout" -> true
                else -> error(name)
            } }
            val model=AccountViewModel(profile,fake<AttachmentRepository> {name,_ -> error(name)},session)
            owner.put("account",model);runCurrent()
            model.changeEmail("new@example.com");runCurrent()
            model.verifyEmail("12345678","87654321","");runCurrent()
            assertEquals("MFA_REQUIRED",model.error.value)
            assertTrue(verified.isEmpty())
            assertNotNull(model.emailChange.value)
            model.verifyEmail("12345678","87654321","123456");runCurrent()
            assertEquals(listOf<String?>("123456"),verified.toList())
            assertNull(model.emailChange.value)
        } finally {owner.clear();Dispatchers.resetMain()}
    }
}
