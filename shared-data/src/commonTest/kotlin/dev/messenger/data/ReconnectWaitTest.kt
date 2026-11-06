package dev.messenger.data

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectWaitTest {
    @Test fun delaysGrowAndStayCappedUntilSuccess()=runTest {
        val retry=ReconnectWait()
        for(expected in listOf(1000L,2000L,4000L,8000L,15000L,15000L,15000L)) {
            retry.attemptStarted()
            val before=currentTime
            retry.afterFailure(null)
            assertEquals(expected,currentTime-before)
        }
        retry.connected()
        retry.attemptStarted()
        val before=currentTime
        retry.afterFailure(null)
        assertEquals(1000L,currentTime-before)
    }
    @Test fun networkRecoveryInterruptsLocalWaitAndResetsBackoff()=runTest {
        val retry=ReconnectWait()
        repeat(5) {retry.attemptStarted();retry.afterFailure(null)}
        retry.attemptStarted()
        val before=currentTime
        val waiting=launch {retry.afterFailure(null)}
        runCurrent();advanceTimeBy(100)
        retry.networkAvailable()
        waiting.join()
        assertEquals(100L,currentTime-before)
        retry.attemptStarted()
        val restored=currentTime
        retry.afterFailure(null)
        assertEquals(1000L,currentTime-restored)
    }
    @Test fun initiallyOfflineDoesNotStartAttempts()=runTest {
        val retry=ReconnectWait()
        retry.networkUnavailable()
        val attempt=launch {retry.attemptStarted()}
        runCurrent();advanceTimeBy(60_000)
        assertTrue(attempt.isActive)
        retry.networkAvailable()
        attempt.join()
        assertEquals(60_000L,currentTime)
    }
    @Test fun lostNetworkWaitsForCallbackAfterTimerExpires()=runTest {
        val retry=ReconnectWait()
        retry.attemptStarted()
        retry.networkUnavailable()
        val waiting=launch {retry.afterFailure(null)}
        runCurrent();advanceTimeBy(60_000)
        assertTrue(waiting.isActive)
        retry.networkAvailable()
        waiting.join()
        assertEquals(60_000L,currentTime)
    }
    @Test fun networkChangeDuringAttemptIsNotLost()=runTest {
        val retry=ReconnectWait()
        retry.attemptStarted()
        retry.networkAvailable()
        retry.afterFailure(null)
        assertEquals(0L,currentTime)
    }
    @Test fun networkEventDoesNotBypassOrTruncateServerThrottle()=runTest {
        val retry=ReconnectWait()
        retry.attemptStarted()
        val waiting=launch {retry.afterFailure(600)}
        runCurrent();advanceTimeBy(100)
        retry.networkAvailable()
        runCurrent()
        assertTrue(waiting.isActive)
        waiting.join()
        assertEquals(600_000L,currentTime)
    }
    @Test fun stoppingCancelsOfflineWait()=runTest {
        val retry=ReconnectWait()
        retry.networkUnavailable()
        val waiting=launch {retry.attemptStarted()}
        runCurrent()
        waiting.cancelAndJoin()
        assertEquals(0L,currentTime)
    }
}
