package dev.messenger.data

import androidx.room.Room
import dev.messenger.data.network.MessengerNetwork
import dev.messenger.data.session.*
import dev.messenger.data.wire.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
class AttachmentDownloadTest {
    @Test fun reopeningPreservesProgressAndRequeuesMissingCachedFile()=runBlocking {
        val db=buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication()))
        val owner=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val client=HttpClient(MockEngine {error("Opening a cached attachment must not perform HTTP")})
        val server=Uuid.random().toString();val user=Uuid.random().toString()
        val device=Uuid.random().toString();val account="$server:$user"
        val vault=wireJson.encodeToString(Vault(device,SavedSession("example.com",443,
            Credentials(Uuid.random().toString(),user,device,server,"a".repeat(40),"r".repeat(40),"2026-11-07T00:00:00Z"))))
        val secure=object:SecureStore {
            override suspend fun read()=vault
            override suspend fun write(value:String)=Unit
        }
        val sessions=SessionCoordinator(secure,Connector {_,_,_->error("No connection expected")},owner)
        val files=ManagedFiles(RuntimeEnvironment.getApplication().cacheDir.resolve("viewer-test").absolutePath)
        val id=Uuid.random().toString()
        try {
            sessions.restore()
            val repository=TransferRepository(db,files,MessengerNetwork(client),sessions,owner)
            val attachment=Attachment(id,"image.jpg","image/jpeg",4,"a".repeat(64),"ready")
            val running=TransferEntity(account,id,"chat","DOWNLOAD","image.jpg","image/jpeg",4,attachment.sha256,
                state="RUNNING",remoteId=id,progress=2,etag="version")
            db.transfers().put(running)
            coroutineScope {repeat(8) {launch {repository.enqueueDownload(account,"chat",attachment)}}}
            assertEquals(running,db.transfers().get(account,id))
            val ready=running.copy(state="READY",progress=4)
            db.transfers().put(ready)
            files.write(account,id,0,byteArrayOf(1,2,3,4))
            repository.enqueueDownload(account,"chat",attachment)
            assertEquals(ready,db.transfers().get(account,id))
            files.remove(account,id)
            repository.enqueueDownload(account,"chat",attachment)
            val requeued=requireNotNull(db.transfers().get(account,id))
            assertEquals("QUEUED",requeued.state)
            assertEquals(0L,requeued.progress)
            assertNull(requeued.etag)
        } finally {
            files.remove(account,id)
            owner.cancel()
            client.close()
            db.close()
        }
    }
}