package dev.messenger.feature.admin

import dev.messenger.domain.AdminRepository
import dev.messenger.domain.ApplicationFailure
import dev.messenger.domain.ProfileRepository
import dev.messenger.domain.model.AdmissionInfo
import dev.messenger.domain.model.Page
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

@OptIn(ExperimentalCoroutinesApi::class)
class AdminViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun finish() { Dispatchers.resetMain() }

    private inline fun <reified T> fake(noinline call: (String) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            try {
                call(method.name)
            } catch (failure: ApplicationFailure) {
                // Deliver suspend failures through the continuation, avoiding Java proxy wrapping.
                @Suppress("UNCHECKED_CAST")
                val continuation = args.last() as Continuation<Any?>
                continuation.resumeWith(Result.failure(failure))
                COROUTINE_SUSPENDED
            }
        } as T

    @Test fun confirmationRetriesRefreshWithoutRepeatingMutation() = runTest(dispatcher) {
        var mutations = 0
        var reads = 0
        val repository = fake<AdminRepository> {
            when (it) {
                "admissionsCreate" -> { mutations++; Unit }
                "admissionsList" -> {
                    reads++
                    if (reads == 1) throw ApplicationFailure("STEP_UP_REQUIRED")
                    Page<AdmissionInfo>(emptyList(), null)
                }
                else -> error(it)
            }
        }
        val model = AdminViewModel(repository, fake<ProfileRepository> { Unit })
        model.admission("test@example.com", true)
        runCurrent()
        assertTrue(model.confirmation.value)
        model.confirmAccess("123456")
        runCurrent()
        assertEquals(1, mutations)
        assertEquals(2, reads)
        assertFalse(model.confirmation.value)
        assertFalse(model.busy.value)
        assertNull(model.error.value)
    }

    @Test fun invalidCodeKeepsRequestPendingAndCancelDoesNotRetry() = runTest(dispatcher) {
        var requests = 0
        val model = AdminViewModel(
            fake<AdminRepository> { requests++; throw ApplicationFailure("STEP_UP_REQUIRED") },
            fake<ProfileRepository> { throw ApplicationFailure("INVALID_CODE") },
        )
        model.load(0, "")
        runCurrent()
        model.confirmAccess("123456")
        runCurrent()
        assertEquals("INVALID_CODE", model.confirmationError.value)
        assertTrue(model.confirmation.value)
        assertEquals(1, requests)
        model.cancelConfirmation()
        runCurrent()
        assertFalse(model.confirmation.value)
        assertFalse(model.busy.value)
        assertNull(model.error.value)
        assertEquals(1, requests)
    }

    @Test fun missingEnrollmentAndForbiddenAreNotRetried() = runTest(dispatcher) {
        for (code in listOf("MFA_REQUIRED", "FORBIDDEN", "CONNECTION_ERROR")) {
            var requests = 0
            val model = AdminViewModel(
                fake<AdminRepository> { requests++; throw ApplicationFailure(code) },
                fake<ProfileRepository> { error("Must not confirm access") },
            )
            model.load(0, "")
            runCurrent()
            assertEquals(code, model.error.value)
            assertFalse(model.confirmation.value)
            assertEquals(1, requests)
        }
    }
}