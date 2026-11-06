package dev.messenger.data.application

import dev.messenger.data.wire.ProtocolFailure
import dev.messenger.domain.ApplicationFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ApplicationFailureTest {
    @Test fun nestedBoundaryKeepsServerFailureCode() = runTest {
        val failure = assertFailsWith<ApplicationFailure> {
            applicationCall { applicationCall { throw ProtocolFailure("FORBIDDEN") } }
        }
        assertEquals("FORBIDDEN", failure.code)
    }
    @Test fun cancellationIsNeverConvertedIntoConnectionFailure() = runTest {
        val cancellation = CancellationException("screen closed")
        assertSame(cancellation, assertFailsWith<CancellationException> {
            applicationCall { throw cancellation }
        })
    }
}
