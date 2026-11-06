package dev.messenger.data.session

import okio.ByteString.Companion.encodeUtf8

import dev.messenger.data.network.*
import dev.messenger.data.wire.*
import dev.messenger.domain.ServerEndpoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** Atomic, device-protected storage. Implementations must never fall back to plaintext. */
interface SecureStore {
    suspend fun read(): String?
    suspend fun write(value: String)
}
@Serializable class RefreshAttempt(val operationId: String, val startedAtMillis: Long)
@Serializable class SavedSession(
    val host: String, val port: Int, val credentials: Credentials,
    val refresh: RefreshAttempt? = null,
    val maxTextBytes: Long = MAX_TEXT_BYTES,
) { override fun toString() = "SavedSession([redacted])" }
@Serializable class Vault(val deviceId: String, val session: SavedSession? = null) {
    override fun toString() = "Vault([redacted])"
}
data class ActiveAccount(val serverId: String, val userId: String, val sessionId: String) {
    val key: String get() = "$serverId:$userId"
}
data class SessionState(val loaded: Boolean = false, val account: ActiveAccount? = null, val online: Boolean = false, val maxTextBytes: Long = MAX_TEXT_BYTES)

interface RpcChannel {
    val maxTextBytes: Long get() = MAX_TEXT_BYTES
    val serverId: String
    val status: StateFlow<ConnectionStatus>
    val highWatermark: StateFlow<Long?>
    suspend fun <P, R> request(command: Command<P, R>, params: P, operationId: Uuid? = null): R
    suspend fun close()
}
fun interface Connector {
    suspend fun open(endpoint: ServerEndpoint, owner: CoroutineScope, expectedServerId: String?): RpcChannel
}

private const val SERVER_CHECK_TIMEOUT_MILLIS = 30_000L

class SessionCoordinator(
    private val secure: SecureStore,
    private val connector: Connector,
    private val owner: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val onTokenRefreshed:()->Unit = {},
) {
    private val rateLimits = RateLimitGate()
    private val lock = Mutex()
    private var vault: Vault? = null
    private var channel: RpcChannel? = null
    private var channelObserver:Job?=null
    private var refreshJob:Job?=null
    private var loginEndpoint: ServerEndpoint? = null
    private var loginServerId: String? = null
    private var challenge: Challenge? = null
    private val mutableState = MutableStateFlow(SessionState())
    val state = mutableState.asStateFlow()

    private fun observeChannel(opened:RpcChannel) {
        refreshJob?.cancel();refreshJob=null
        channelObserver?.cancel()
        channelObserver=owner.launch {
            opened.status.collect {
                lock.withLock {
                    if(channel===opened) vault?.let {publish(it,it.session!=null && opened.status.value==ConnectionStatus.OPEN)}
                }
            }
        }
    }

    private fun scheduleRefresh(opened:RpcChannel) {
        refreshJob?.cancel()
        refreshJob=owner.launch {
            try {
                while(isActive) {
                    val expiry=lock.withLock {
                        if(channel!==opened) return@launch
                        requireNotNull(load().session).credentials.accessExpiresAt
                    }
                    val remaining=(Instant.parse(expiry).toEpochMilliseconds()-now()).coerceAtLeast(0)
                    // Refresh at least half a short token lifetime before expiry, up to one minute early.
                    val lead=minOf(REFRESH_LEAD_MILLIS,remaining/2)
                    delay((remaining-lead).coerceAtLeast(1_000))
                    lock.withLock {
                        if(channel!==opened) return@launch
                        val current=load()
                        val saved=current.session ?: return@launch
                        if(saved.credentials.accessExpiresAt==expiry) {
                            rotate(opened,current,saved)
                        }
                    }
                }
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(failure:Exception) {
                reportConnectionFailure(failure)
                withContext(NonCancellable) {disconnect(opened)}
            }
        }
    }

    private suspend fun load(): Vault {
        vault?.let { return it }
        val existing = secure.read()?.let { wireJson.decodeFromString<Vault>(it) }
        val value = existing ?: Vault(Uuid.random().toString()).also { secure.write(wireJson.encodeToString(it)) }
        vault = value
        publish(value, false)
        return value
    }
    suspend fun restore() = lock.withLock { load(); Unit }

    private fun publish(value: Vault, online: Boolean) {
        val c = value.session?.credentials
        mutableState.value = SessionState(true,
            c?.let { ActiveAccount(it.serverId, it.userId, it.sessionId) }, online, value.session?.maxTextBytes ?: MAX_TEXT_BYTES)
    }
    private suspend fun persist(value: Vault) = withContext(NonCancellable) {
        secure.write(wireJson.encodeToString(value))
        vault = value
        publish(value, channel?.status?.value == ConnectionStatus.OPEN && value.session != null)
    }

    /** A checked anonymous connection is retained for the email screen; no mail is sent here. */
    suspend fun prepareLogin(endpoint: ServerEndpoint) = withTimeout(SERVER_CHECK_TIMEOUT_MILLIS) {
        lock.withLock {
            check(load().session == null) { "Sign out before changing accounts" }
            try { loginConnection(endpoint).request(Commands.Ping, EmptyParams()); Unit }
            catch(failure: Exception) {
                withContext(NonCancellable) { channel?.close(); channel=null }
                throw failure
            }
        }
    }

    // Caller owns lock. Reconnection to the same selected endpoint must retain its checked identity.
    private suspend fun loginConnection(endpoint:ServerEndpoint):RpcChannel {
        val sameEndpoint=loginEndpoint==endpoint
        if(sameEndpoint) channel?.takeIf { it.status.value==ConnectionStatus.OPEN }?.let { return it }
        channel?.close(); channel=null
        if(!sameEndpoint) { challenge=null; loginServerId=null }
        val opened=connector.open(endpoint,owner,loginServerId.takeIf { sameEndpoint })
        loginEndpoint=endpoint; loginServerId=opened.serverId; channel=opened
        return opened
    }

    /** Caller first cancels its pending auth action. Never tears down an authenticated session. */
    suspend fun cancelLogin():Boolean = lock.withLock {
        if(load().session!=null) return@withLock false
        withContext(NonCancellable) {
            channel?.close();channel=null
            challenge=null;loginEndpoint=null;loginServerId=null
        }
        true
    }
    suspend fun startEmail(endpoint: ServerEndpoint, email: String): Challenge = lock.withLock {
        check(load().session == null) { "Sign out before changing accounts" }
        challenge = null
        loginConnection(endpoint).request(Commands.EmailStart, EmailStartParams(email, Uuid.random().toString()))
            .also { challenge = it }
    }
    suspend fun verifyEmail(code: String, deviceName: String, mfaCode: String? = null) = lock.withLock {
        val current = load()
        val endpoint = requireNotNull(loginEndpoint)
        val pending = requireNotNull(challenge)
        // OTP challenges are server-side; the anonymous socket may have disconnected while waiting.
        val knownServerId = requireNotNull(loginServerId)
        val connection = channel?.takeIf { it.status.value == ConnectionStatus.OPEN }
            ?: connector.open(endpoint, owner, knownServerId).also { channel = it }
        val result = connection.request(Commands.EmailVerify,
            EmailVerifyParams(pending.challengeId, code, current.deviceId, deviceName, mfaCode))
        if (result.serverId != knownServerId || result.deviceId != current.deviceId) {
            connection.close(); channel = null
            throw ProtocolFailure("SESSION_IDENTITY_CHANGED")
        }
        persist(Vault(current.deviceId, SavedSession(endpoint.host, endpoint.port, result, maxTextBytes = connection.maxTextBytes)))
        challenge = null
        observeChannel(connection)
        scheduleRefresh(connection)
    }

    suspend fun connection(): RpcChannel = lock.withLock {
        val current = load()
        val saved = current.session ?: throw ProtocolFailure("LOGIN_REQUIRED")
        channel?.takeIf { it.status.value == ConnectionStatus.OPEN }?.let { return@withLock it }
        channel?.close(); channel = null
        publish(current, false)
        val endpoint = requireNotNull(ServerEndpoint.parse(saved.host, saved.port.toString()))
        val opened = connector.open(endpoint, owner, saved.credentials.serverId)
        try {
            // A journaled refresh must finish before trying the potentially superseded access token.
            if (saved.refresh != null) rotate(opened, current, saved)
            else {
                try {
                    val identity = opened.request(Commands.Resume, ResumeParams(saved.credentials.accessToken))
                    requireIdentity(saved.credentials, identity)
                } catch (error: RemoteFailure) {
                    if (error.code != "UNAUTHENTICATED") throw error
                    rotate(opened, current, saved)
                }
            }
            val latest = requireNotNull(vault)
            val latestSaved = requireNotNull(latest.session)
            if (latestSaved.maxTextBytes != opened.maxTextBytes) {
                persist(Vault(latest.deviceId, SavedSession(latestSaved.host, latestSaved.port,
                    latestSaved.credentials, latestSaved.refresh, opened.maxTextBytes)))
            }
            channel = opened
            publish(requireNotNull(vault), opened.status.value==ConnectionStatus.OPEN)
            observeChannel(opened)
            scheduleRefresh(opened)
            opened
        } catch (error: Exception) {
            opened.close()
            if (error is RemoteFailure && error.code in TERMINAL_AUTH_ERRORS ||
                error is ProtocolFailure && error.code == "REFRESH_WINDOW_EXPIRED") {
                persist(Vault(current.deviceId))
            }
            throw error
        }
    }

    private suspend fun rotate(opened: RpcChannel, current: Vault, saved: SavedSession) {
        val attempt = saved.refresh ?: RefreshAttempt(Uuid.random().toString(), now()).also {
            persist(Vault(current.deviceId, SavedSession(saved.host, saved.port, saved.credentials, it, saved.maxTextBytes)))
        }
        val age = now() - attempt.startedAtMillis
        if (age !in 0 until REFRESH_RETRY_WINDOW_MILLIS) throw ProtocolFailure("REFRESH_WINDOW_EXPIRED")
        val result = opened.request(Commands.Refresh, RefreshParams(saved.credentials.refreshToken),
            Uuid.parse(attempt.operationId))
        requireIdentity(saved.credentials, result.identity())
        persist(Vault(current.deviceId, SavedSession(saved.host, saved.port, result, maxTextBytes = opened.maxTextBytes)))
        onTokenRefreshed()
    }

    private fun requireIdentity(expected: Credentials, actual: SessionIdentity) {
        if (expected.serverId != actual.serverId || expected.userId != actual.userId ||
            expected.deviceId != actual.deviceId || expected.sessionId != actual.sessionId) {
            throw ProtocolFailure("SESSION_IDENTITY_CHANGED")
        }
    }

    suspend fun <P, R> request(command: Command<P, R>, params: P, operationId: Uuid? = null): R {
        if (state.value.account == null) connection()
        val account = state.value.account ?: throw ProtocolFailure("AUTH_REQUIRED")
        val operation = command.name + ":" + (operationId?.toString()
            ?: wireJson.encodeToString(command.params, params).encodeUtf8().sha256().hex())
        return rateLimits.run(account.key, command, operation) {
            if (state.value.account != account) throw CancellationException("Account changed")
            requestOnce(command, params, operationId)
        }
    }

    private suspend fun <P, R> requestOnce(command: Command<P, R>, params: P, operationId: Uuid?): R {
        val connection = connection()
        try { return connection.request(command, params, operationId) }
        catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is RemoteFailure && error.code == "UNAUTHENTICATED") {
                disconnect(connection)
                return connection().request(command, params, operationId)
            }
            if (error !is RemoteFailure) disconnect(connection)
            throw error
        }
    }


    suspend fun transferCredentials(): SavedSession {
        connection()
        return lock.withLock { requireNotNull(load().session) }
    }

    suspend fun disconnect(expected: RpcChannel? = null) = lock.withLock {
        if (expected == null || expected === channel) {
            refreshJob?.cancel();refreshJob=null
            channelObserver?.cancel();channelObserver=null
            channel?.close(); channel = null
            vault?.let { publish(it, false) }
        }
    }

    /** Returns whether server revocation was acknowledged. Local sign-out always removes credentials. */
    suspend fun logout(): Boolean {
        var revoked = false
        try { request(Commands.Logout, EmptyParams(), Uuid.random()); revoked = true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Explicit local logout remains available offline. */ }
        lock.withLock {
            refreshJob?.cancel();refreshJob=null
            channelObserver?.cancel();channelObserver=null
            channel?.close(); channel = null; challenge = null; loginEndpoint = null; loginServerId = null
            persist(Vault(load().deviceId))
        }
        return revoked
    }
    companion object {
        const val REFRESH_RETRY_WINDOW_MILLIS = 30_000L
        private const val REFRESH_LEAD_MILLIS=60_000L
        private val TERMINAL_AUTH_ERRORS = setOf("UNAUTHENTICATED", "REFRESH_REUSE", "REFRESH_SUPERSEDED")
    }
}
