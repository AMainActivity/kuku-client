package dev.messenger.data.application

import dev.messenger.data.wire.*
import dev.messenger.domain.ActiveAccount
import dev.messenger.domain.ApplicationFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import kotlin.uuid.Uuid

class RepositoryCommandsTest {
    private class Recorder:CommandExecutor {
        val ids=mutableListOf<Uuid?>()
        var failure:Exception?=null
        @Suppress("UNCHECKED_CAST")
        override suspend fun <P,R> execute(command:Command<P,R>,params:P,operationId:Uuid?):R {
            ids+=operationId
            failure?.let {throw it}
            return when(command.name) {
                "auth.mfa.setup" -> MfaEnrollment("test-secret","otpauth://test")
                "sessions.list" -> Items<DeviceSession>(emptyList())
                "admin.admissions.create" -> AdmissionChanged("example@example.com",true)
                else -> error("Unexpected command")
            } as R
        }
    }
    @Test fun ephemeralRetryKeepsIdentityUntilSuccess()=runTest {
        val recorder=Recorder()
        val gateway=RepositoryCommands({ActiveAccount("server","user","session")},recorder)
        recorder.failure=ProtocolFailure("TIMEOUT")
        assertFailsWith<ApplicationFailure> {gateway.execute(MoreCommands.MfaSetup,EmptyParams())}
        recorder.failure=null
        gateway.execute(MoreCommands.MfaSetup,EmptyParams())
        gateway.execute(MoreCommands.MfaSetup,EmptyParams())
        assertNotNull(recorder.ids[0])
        assertEquals(recorder.ids[0],recorder.ids[1])
        assertNotEquals(recorder.ids[1],recorder.ids[2])
    }
    @Test fun newSessionDoesNotReusePreviousEphemeralOperation()=runTest {
        val recorder=Recorder()
        var account=ActiveAccount("server","user","first")
        val gateway=RepositoryCommands({account},recorder)
        recorder.failure=ProtocolFailure("TIMEOUT")
        assertFailsWith<ApplicationFailure> {gateway.execute(MoreCommands.MfaSetup,EmptyParams())}
        account=account.copy(sessionId="second")
        recorder.failure=null
        gateway.execute(MoreCommands.MfaSetup,EmptyParams())
        assertNotEquals(recorder.ids[0],recorder.ids[1])
    }
    @Test fun readsHaveNoIdentityAndDurableMutationsDelegateIdentityToJournal()=runTest {
        val recorder=Recorder()
        val gateway=RepositoryCommands({ActiveAccount("server","user","session")},recorder)
        gateway.execute(MoreCommands.SessionsList,EmptyParams())
        gateway.execute(MoreCommands.AdmissionsCreate,EmailParams("example@example.com"))
        assertEquals(listOf<Uuid?>(null,null),recorder.ids)
    }
    @Test fun cancellationRemainsCancellationAndKeepsRetryIdentity()=runTest {
        val recorder=Recorder()
        val gateway=RepositoryCommands({ActiveAccount("server","user","session")},recorder)
        val cancellation=CancellationException("cancelled")
        recorder.failure=cancellation
        assertSame(cancellation,assertFailsWith<CancellationException> {gateway.execute(MoreCommands.MfaSetup,EmptyParams())})
        recorder.failure=null
        gateway.execute(MoreCommands.MfaSetup,EmptyParams())
        assertEquals(recorder.ids[0],recorder.ids[1])
    }
}
