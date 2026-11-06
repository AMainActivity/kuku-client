package dev.messenger.data.network

import dev.messenger.data.wire.*
import dev.messenger.domain.ServerEndpoint
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.io.readByteArray
import io.ktor.utils.io.readBuffer

/** Owns its HttpClient; callers own and close each returned RpcConnection. */
class MessengerNetwork(private val client: HttpClient) {
    suspend fun discover(endpoint: ServerEndpoint): ServerInfo = withTimeout(CONNECT_TIMEOUT_MILLIS) {
        val response = client.get("https://${endpoint.authority}/v1/server-info")
        if (response.status.value != 200) {
            response.bodyAsChannel().cancel(null)
            throw ProtocolFailure("DISCOVERY_HTTP_ERROR")
        }
        val channel = response.bodyAsChannel()
        val bytes = try { channel.readBuffer(MAX_RECEIVE_BYTES.toLong() + 1).readByteArray() }
            finally { channel.cancel(null) }
        if (bytes.size > MAX_RECEIVE_BYTES) throw ProtocolFailure("FRAME_TOO_LARGE")
        val info = try { wireJson.decodeFromString<ServerInfo>(bytes.decodeToString(throwOnInvalidSequence = true)) }
            catch (_: Exception) { throw ProtocolFailure("INVALID_DISCOVERY") }
        if (info.contractVersion != CONTRACT_VERSION || PROTOCOL_VERSION !in info.protocolVersions) {
            throw ProtocolFailure("UNSUPPORTED_CONTRACT")
        }
        info
    }

    suspend fun connect(
        endpoint: ServerEndpoint, owner: CoroutineScope, expectedServerId: String? = null,
    ): RpcConnection {
        val info = discover(endpoint)
        if (expectedServerId != null && expectedServerId != info.serverId) {
            throw ProtocolFailure("SERVER_IDENTITY_CHANGED")
        }
        var socket: TextSocket? = null
        try {
            return withTimeout(CONNECT_TIMEOUT_MILLIS) {
                val opened = KtorTextSocket(client.webSocketSession("wss://${endpoint.authority}/v1/ws"))
                socket = opened
                val hello = decodeIncoming(opened.receive() ?: throw ConnectionLost()) as? Incoming.Hello
                    ?: throw ProtocolFailure("HELLO_REQUIRED")
                verifyServer(info, hello, expectedServerId)
                RpcConnection(opened, owner, info.serverId,onFailure=::reportConnectionFailure)
            }
        } catch (error: Exception) {
            socket?.close()
            throw error
        }
    }

    suspend fun putPart(authority: String, token: String, uploadId: String, number: Int, sha256: String, bytes: ByteArray) {
        uuid(uploadId)
        val response = client.put("https://$authority/v1/files/$uploadId/parts/$number") {
            bearerAuth(token)
            header("X-Content-SHA256", sha256)
            header("Content-Type", "application/octet-stream")
            setBody(bytes)
            timeout { requestTimeoutMillis = 120_000 }
        }
        response.bodyAsChannel().cancel(null)
        if (response.status.value !in 200..299) throw FileHttpFailure(response.status.value)
    }
    internal suspend fun downloadPart(authority: String, token: String, attachmentId: String, offset: Long, end: Long, etag: String?): DownloadPart {
        uuid(attachmentId)
        val response = client.get("https://$authority/v1/files/$attachmentId") {
            bearerAuth(token)
            header("Range", "bytes=$offset-$end")
            if (etag != null) header("If-Range", etag)
            timeout { requestTimeoutMillis = 120_000 }
        }
        val channel = response.bodyAsChannel()
        if(response.status.value !in setOf(200,206)) {
            channel.cancel(null)
            throw FileHttpFailure(response.status.value)
        }
        val bytes = try { channel.readBuffer(end - offset + 2).readByteArray() } finally { channel.cancel(null) }
        return DownloadPart(response.status.value, response.headers["ETag"], response.headers["Content-Range"], bytes)
    }

    fun close() = client.close()

    companion object { const val CONNECT_TIMEOUT_MILLIS = 15_000L }
}

private class KtorTextSocket(private val session: DefaultClientWebSocketSession) : TextSocket {
    override suspend fun receive(): String? {
        while (true) {
            val frame = session.incoming.receiveCatching().getOrNull()
            if (frame == null) {
                if (session.closeReason.await()?.code?.toInt() == 4401) throw ProtocolFailure("AUTH_REQUIRED")
                return null
            }
            when (frame) {
                is Frame.Text -> {
                    if (frame.data.size > MAX_RECEIVE_BYTES) throw ProtocolFailure("FRAME_TOO_LARGE")
                    return frame.readText()
                }
                is Frame.Binary -> throw ProtocolFailure("BINARY_FRAME")
                is Frame.Close -> {
                    if (frame.readReason()?.code?.toInt() == 4401) throw ProtocolFailure("AUTH_REQUIRED")
                    return null
                }
                else -> Unit
            }
        }
    }
    override suspend fun send(text: String) = session.send(Frame.Text(text))
    override fun close() = session.cancel()
}

/** TLS and hostname verification use the platform defaults. No trust-all or redirect credential forwarding. */
fun messengerHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    followRedirects = false
    expectSuccess = false
    // OkHttp rejects a finite maxFrameSize at session creation. KtorTextSocket validates
    // received frame length before decoding; the wire decoder also bounds incoming messages.
    install(WebSockets)
    install(HttpTimeout) { connectTimeoutMillis = MessengerNetwork.CONNECT_TIMEOUT_MILLIS }
}

internal class FileHttpFailure(val status: Int) : Exception("FILE_HTTP_$status")
internal class DownloadPart(val status: Int, val etag: String?, val range: String?, val bytes: ByteArray)