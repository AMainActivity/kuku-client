package dev.messenger.feature.auth

import androidx.lifecycle.SavedStateHandle
import dev.messenger.domain.ApplicationFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {
    @Test fun setupRestoresFromSavedState() {
        val saved=SavedStateHandle(mapOf("host" to "example.com","port" to "8443"))
        val model=SetupViewModel(saved) {}
        assertEquals("example.com:8443",model.endpoint()?.authority)
        model.changeHost("second.example.com")
        assertEquals("second.example.com",saved.get<String>("host"))
    }
    @Test fun navigationWaitsForCheckAndDoubleTapDoesNotOpenTwice()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val gate=CompletableDeferred<Unit>();var calls=0;var navigations=0
            val model=SetupViewModel(SavedStateHandle(mapOf("host" to "example.com"))) { calls++;gate.await() }
            model.connect { _,_ -> navigations++ };model.connect { _,_ -> navigations++ }
            runCurrent()
            assertTrue(model.busy.value);assertEquals(1,calls);assertEquals(0,navigations)
            gate.complete(Unit);runCurrent()
            assertFalse(model.busy.value);assertEquals(1,navigations);assertNull(model.error.value)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun failureStaysOnSetupAndAllowsRetry()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var fail=true;var navigations=0
            val model=SetupViewModel(SavedStateHandle(mapOf("host" to "example.com"))) { if(fail) throw ApplicationFailure("INVALID_DISCOVERY") }
            model.connect { _,_ -> navigations++ };runCurrent()
            assertEquals(0,navigations);assertFalse(model.busy.value);assertEquals("INVALID_DISCOVERY",model.error.value)
            fail=false;model.connect { _,_ -> navigations++ };runCurrent()
            assertEquals(1,navigations);assertNull(model.error.value)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun cancellationDoesNotNavigateOrShowConnectionError()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var navigations=0
            val model=SetupViewModel(SavedStateHandle(mapOf("host" to "example.com"))) { awaitCancellation() }
            model.connect { _,_ -> navigations++ };runCurrent();model.cancelCheck();runCurrent()
            assertEquals(0,navigations);assertFalse(model.busy.value);assertNull(model.error.value)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun timeoutStaysOnSetupWithActionableError()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var navigations=0
            val model=SetupViewModel(SavedStateHandle(mapOf("host" to "example.com"))) { withTimeout(10) { awaitCancellation() } }
            model.connect { _,_ -> navigations++ };advanceUntilIdle()
            assertEquals(0,navigations);assertFalse(model.busy.value);assertEquals("TIMEOUT",model.error.value)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun invalidHostOrPortNeverStartsConnection()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var calls=0;var navigations=0
            val model=SetupViewModel(SavedStateHandle(mapOf("host" to "999.1.1.1"))) { calls++ }
            model.connect { _,_ -> navigations++ };runCurrent()
            model.changeHost("example.com");model.changePort("65536")
            model.connect { _,_ -> navigations++ };runCurrent()
            assertEquals(0,calls);assertEquals(0,navigations);assertFalse(model.busy.value)
        } finally { Dispatchers.resetMain() }
    }}
