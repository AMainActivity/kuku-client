package dev.messenger.feature.conversation

import dev.messenger.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import kotlin.test.*
import kotlin.uuid.Uuid

class HistoryWindowTest {
    private class Source:HistoryWindowSource {
        val chat=Uuid.random().toString()
        val messages=MutableStateFlow((1L..400L).map {seq -> Message(Uuid.random().toString(),Uuid.random().toString(),chat,Uuid.random().toString(),seq,1,null,null,false,"",null,emptyList())})
        override val recovery=MutableStateFlow(true to 0L)
        override val online get()=recovery.value.first
        var pageGate:CompletableDeferred<Unit>?=null
        var unread:Long?=null
        override suspend fun unreadCursor()=unread
        val calls=mutableListOf<Triple<String,Long?,Int>>()
        override fun observe(lower:Long,upper:Long?,limit:Int)=messages.map {all -> all.filter {it.seq>=lower && (upper==null || it.seq<=upper)}.takeLast(limit)}
        override suspend fun page(direction:String,anchor:Long?,limit:Int):History {
            pageGate?.await()
            calls+=Triple(direction,anchor,limit)
            return cachedPage(direction,anchor,limit)
        }
        override suspend fun cachedPage(direction:String,anchor:Long?,limit:Int):History {
            val all=messages.value
            val items=when(direction) {
                "before" -> all.filter {it.seq<(anchor ?: Long.MAX_VALUE)}.takeLast(limit)
                "after" -> all.filter {it.seq>(anchor ?: 0)}.take(limit)
                else -> all.filter {it.seq<(anchor ?: 0)}.takeLast(limit/2)+all.filter {it.seq>=(anchor ?: 0)}.take(limit/2)
            }
            return History(items,items.firstOrNull()?.seq,items.lastOrNull()?.seq,items.firstOrNull()?.seq?.let {it>1} ?: false,items.lastOrNull()?.seq?.let {it<all.last().seq} ?: false)
        }
        override suspend fun sequence(id:String)=messages.value.first {it.messageId==id}.seq
        override suspend fun members() {}
    }
    @Test fun opensAroundUnreadCursorAndLatestRestoresLiveTail()=runBlocking {
        val source=Source().also {it.unread=100}
        val window=HistoryWindow(source)
        val rendered=MutableStateFlow(emptyList<Message>())
        val job=launch {window.history.collect {rendered.value=it}}
        try {
            withTimeout(3000) {rendered.first {it.firstOrNull()?.seq==76L}}
            assertEquals(100L,window.openingCursor.value)
            assertFalse(window.atTail.value)
            assertTrue(window.openingReady.value)
            assertEquals(Triple("around",101L,50),source.calls.first())
            window.latest()
            withTimeout(3000) {rendered.first {it.lastOrNull()?.seq==400L && it.firstOrNull()?.seq==351L}}
            assertTrue(window.atTail.value)
            assertEquals(source.messages.value.last().messageId,window.latestAnchor.value)
            // An explicit jump must not change the opening divider's immutable read cursor.
            assertEquals(100L,window.openingCursor.value)
        } finally {job.cancelAndJoin()}
    }
    @Test fun offlineOpeningUsesCachedCursorWithoutMarkingLatestRead()=runBlocking {
        val source=Source().also {it.unread=200;it.recovery.value=false to 0L}
        val window=HistoryWindow(source)
        val job=launch {window.history.collect()}
        try {
            withTimeout(3000) {window.openingReady.first {it}}
            assertEquals(200L,window.openingCursor.value)
            assertTrue(source.calls.isEmpty(), "Offline opening must only read the local page")
            assertEquals(source.messages.value.first { it.seq==176L }.messageId,window.openingAnchor.value)
        } finally {job.cancelAndJoin()}
    }
    @Test fun latestPositionsCachedTailBeforeNetworkResponds()=runBlocking {
        val source=Source().also {it.unread=100}
        val window=HistoryWindow(source)
        val job=launch {window.history.collect()}
        try {
            withTimeout(3000) {window.openingReady.first {it}}
            val gate=CompletableDeferred<Unit>()
            source.pageGate=gate
            val positioned=CompletableDeferred<Unit>()
            val move=launch {window.latest {positioned.complete(Unit)}}
            withTimeout(3000) {positioned.await()}
            assertFalse(move.isCompleted)
            assertEquals(source.messages.value.last().messageId,window.latestAnchor.value)
            gate.complete(Unit)
            move.join()
        } finally {job.cancelAndJoin()}
    }
    @Test fun latestPositionsLocallyEvenWhileOpeningRequestHoldsHistoryLock()=runBlocking<Unit> {
        val gate=CompletableDeferred<Unit>()
        val source=Source().also {it.unread=100;it.pageGate=gate}
        val window=HistoryWindow(source)
        val collect=launch {window.history.collect()}
        var move:Job?=null
        try {
            withTimeout(3000) {window.loading.first {it}}
            val positioned=CompletableDeferred<Unit>()
            move=launch {window.latest {positioned.complete(Unit)}}
            withTimeout(3000) {positioned.await()}
            assertEquals(source.messages.value.last().messageId,window.latestAnchor.value)
            assertTrue(window.atTail.value)
        } finally {gate.complete(Unit);move?.cancelAndJoin();collect.cancelAndJoin()}
    }
    @Test fun jumpInsideWindowDoesNotReloadOrShowLoading()=runBlocking {
        val source=Source();val window=HistoryWindow(source)
        val rendered=MutableStateFlow(emptyList<Message>())
        val job=launch {window.history.collect {rendered.value=it}}
        try {
            withTimeout(3000) {rendered.first {it.firstOrNull()?.seq==351L}}
            val before=rendered.value.map {it.messageId}
            val calls=source.calls.toList()
            window.jump(rendered.value[10].messageId)
            assertFalse(window.loading.value)
            assertEquals(calls,source.calls)
            assertEquals(before,rendered.value.map {it.messageId})
        } finally {job.cancelAndJoin()}
    }
    @Test fun scrollBothWaysAndJumpStayBounded()=runBlocking {
        val source=Source();val window=HistoryWindow(source);val rendered=MutableStateFlow(emptyList<Message>())
        val job=launch {window.history.collect {rendered.value=it}}
        suspend fun range(first:Long,last:Long) {withTimeout(3000) {rendered.first {it.firstOrNull()?.seq==first && it.lastOrNull()?.seq==last}};assertTrue(rendered.value.size<=150)}
        try {
            range(351,400)
            window.viewport(true,false);range(301,400)
            window.viewport(true,false);range(251,400)
            window.viewport(true,false);range(201,350)
            window.viewport(false,true);range(251,400)
            window.jump(source.messages.value.first {it.seq==100L}.messageId);range(75,124)
            val before=rendered.value.map {it.messageId}
            source.recovery.value=false to 0L;yield();source.recovery.value=true to 0L
            withTimeout(3000) {while(window.loading.value) yield()};yield()
            assertEquals(before,rendered.value.map {it.messageId})
            assertTrue(source.calls.filter {it.first=="after"}.all {it.third<=50})
        } finally {job.cancelAndJoin()}
    }
    @Test fun incomingMessagesDoNotDisplaceFrozenHistoryAndTailResumes()=runBlocking<Unit> {
        val source=Source();val window=HistoryWindow(source);val rendered=MutableStateFlow(emptyList<Message>())
        val job=launch {window.history.collect {rendered.value=it}}
        try {
            withTimeout(3000) {rendered.first {it.firstOrNull()?.seq==351L}}
            window.viewport(false,false)
            yield()
            val latest=source.messages.value.last()
            source.messages.value+=Message(Uuid.random().toString(),Uuid.random().toString(),source.chat,latest.senderId,401,1,null,null,false,"",null,emptyList())
            yield()
            assertEquals(400L,rendered.value.last().seq)
            window.viewport(false,true)
            withTimeout(3000) {rendered.first {it.lastOrNull()?.seq==401L}}
            assertTrue(rendered.value.size<=150)
            // Jump to the latest item must also regain live following on reaching the new edge.
            window.jump(source.messages.value.last().messageId)
            yield();window.viewport(false,true);yield()
            source.messages.value+=Message(Uuid.random().toString(),Uuid.random().toString(),source.chat,latest.senderId,402,1,null,null,false,"",null,emptyList())
            withTimeout(3000) {rendered.first {it.lastOrNull()?.seq==402L}}
        } finally {job.cancelAndJoin()}
    }}
