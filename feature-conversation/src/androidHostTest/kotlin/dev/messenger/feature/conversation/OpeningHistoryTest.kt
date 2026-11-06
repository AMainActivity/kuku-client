package dev.messenger.feature.conversation

import dev.messenger.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import kotlin.test.*
import kotlin.uuid.Uuid

class OpeningHistoryTest {
    private class Source(val serverRead: Long, cachedRead: Long, val connected: Boolean) : HistoryWindowSource {
        private val chat = Uuid.random().toString()
        private val all = (1L..1000L).map { seq -> Message(Uuid.random().toString(), Uuid.random().toString(), chat, Uuid.random().toString(), seq, 1, null, null, false, "", null, emptyList()) }
        val cache = MutableStateFlow(all.takeLast(1)) // Initial catalog stores lastMessage only.
        fun cacheAll() { cache.value=all }
        var localRead = cachedRead
        val refreshed = CompletableDeferred<Unit>()
        var membersGate: CompletableDeferred<Unit>? = null
        val requests = mutableListOf<Pair<String,Long?>>()
        override val recovery = flowOf(connected to 0L)
        override val online = connected
        override suspend fun unreadCursor() = localRead
        override fun observe(lower: Long, upper: Long?, limit: Int) = cache.map { rows -> rows.filter { it.seq >= lower && (upper == null || it.seq <= upper) }.takeLast(limit) }
        private fun select(rows: List<Message>, direction: String, anchor: Long?, limit: Int): History {
            val items = if (direction == "around") rows.filter { it.seq < requireNotNull(anchor) }.takeLast(limit/2) + rows.filter { it.seq >= requireNotNull(anchor) }.take(limit/2)
                else rows.filter { it.seq < (anchor ?: Long.MAX_VALUE) }.takeLast(limit)
            return History(items, items.firstOrNull()?.seq, items.lastOrNull()?.seq, (items.firstOrNull()?.seq ?: 1) > 1, (items.lastOrNull()?.seq ?: 0) < 1000)
        }
        override suspend fun cachedPage(direction: String, anchor: Long?, limit: Int) = select(cache.value, direction, anchor, limit)
        override suspend fun page(direction: String, anchor: Long?, limit: Int): History {
            requests += direction to anchor
            return select(all, direction, anchor, limit).also { page -> cache.value = (cache.value + page.items).distinctBy { it.seq }.sortedBy { it.seq } }
        }
        override suspend fun members() { membersGate?.await(); localRead = serverRead; refreshed.complete(Unit) }
        override suspend fun sequence(id: String) = all.first { it.messageId == id }.seq
    }

    @Test fun offlineCachedHistoryIsAvailableWithoutAllowingReadReceipts() = runBlocking<Unit> {
        val source = Source(serverRead = 100, cachedRead = 100, connected = false)
        val window = HistoryWindow(source)
        val rendered = MutableStateFlow(emptyList<Message>())
        val collector = launch { window.history.collect { rendered.value = it } }
        try {
            withTimeout(3000) { rendered.first { it.isNotEmpty() } }
            assertEquals(1000L, rendered.value.single().seq)
            // The screen renders these rows independently of its read-receipt gate.
            assertFalse(window.openingReady.value)
        } finally { collector.cancelAndJoin() }
    }

    @Test fun firstOpeningMustReconcileMissingLocalReadCursor() = runBlocking<Unit> {
        // initialUnreadCursor returns 0 when catalog has unreadCount=1 but own MemberInfo is absent.
        val source = Source(serverRead = 999, cachedRead = 0, connected = true)
        val window = HistoryWindow(source)
        val rendered = MutableStateFlow(emptyList<Message>())
        val collector = launch { window.history.collect { rendered.value = it } }
        try {
            withTimeout(3000) { source.refreshed.await(); rendered.first { it.firstOrNull()?.seq == 975L } }
            assertEquals(999L, source.localRead)
            assertEquals(listOf<Pair<String, Long?>>("around" to 1000L), source.requests)
            assertEquals(999L, window.openingCursor.value, "Fresh members say readSeq=999, but the screen stays at message 1")
        } finally { collector.cancelAndJoin() }
    }

    @Test fun explicitLatestWinsOverDelayedReadCursorRefresh() = runBlocking<Unit> {
        val gate=CompletableDeferred<Unit>()
        val source=Source(serverRead=999,cachedRead=0,connected=true).also {it.membersGate=gate}
        val window=HistoryWindow(source)
        val rendered=MutableStateFlow(emptyList<Message>())
        val collector=launch {window.history.collect {rendered.value=it}}
        var move:Job?=null
        try {
            withTimeout(3000) {window.loading.first {it}}
            val positioned=CompletableDeferred<Unit>()
            move=launch {window.latest {positioned.complete(Unit)}}
            withTimeout(3000) {positioned.await()}
            gate.complete(Unit)
            withTimeout(3000) {move.join();rendered.first {it.size==50}}
            assertEquals(0L,window.openingCursor.value,"Explicit navigation must not be replaced by delayed opening positioning")
            assertTrue(window.atTail.value)
            assertEquals(1000L,rendered.value.last().seq)
        } finally {gate.complete(Unit);move?.cancelAndJoin();collector.cancelAndJoin()}
    }

    @Test fun readingCachedMessagesPreservesOpeningPositionDuringRefresh() = runBlocking<Unit> {
        val gate=CompletableDeferred<Unit>()
        val source=Source(serverRead=999,cachedRead=100,connected=true).also {it.cacheAll();it.membersGate=gate}
        val window=HistoryWindow(source)
        val collector=launch {window.history.collect()}
        try {
            withTimeout(3000) {window.loading.first {it}}
            assertTrue(window.openingReady.value)
            window.onVisibleMessagesRead(110)
            gate.complete(Unit)
            withTimeout(3000) {source.refreshed.await();window.loading.first {!it}}
            assertEquals(100L,window.openingCursor.value)
        } finally {gate.complete(Unit);collector.cancelAndJoin()}
    }
}
