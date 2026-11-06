package dev.messenger.feature.conversation

import dev.messenger.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import kotlin.test.*
import kotlin.uuid.Uuid

class HistoryRecoveryTest {
    @Test fun latestReplacesUnresolvedUnreadAnchorWithVisibleCachedTail()=runBlocking<Unit> {
        val chat=Uuid.random().toString()
        fun message(seq:Long)=Message(Uuid.random().toString(),Uuid.random().toString(),chat,Uuid.random().toString(),seq,1,null,null,false,"",null,emptyList())
        val cached=MutableStateFlow((351L..400L).map(::message))
        val gate=CompletableDeferred<Unit>()
        val source=object:HistoryWindowSource {
            override val recovery=MutableStateFlow(true to 0L)
            override val online=true
            override fun observe(lower:Long,upper:Long?,limit:Int)=cached.map {all -> all.filter {it.seq>=lower && (upper==null || it.seq<=upper)}.takeLast(limit)}
            override suspend fun unreadCursor()=100L
            override suspend fun page(direction:String,anchor:Long?,limit:Int):History {
                gate.await();return History(emptyList(),null,null,false,false)
            }
            override suspend fun sequence(id:String)=0L
            override suspend fun members() {}
        }
        val window=HistoryWindow(source)
        val collector=launch {window.history.collect()}
        var move:Job?=null
        try {
            withTimeout(3000) {window.loading.first {it}}
            assertFalse(window.openingReady.value, "Missing unread boundary must not trigger read ACK")
            cached.value+=message(401)
            val positioned=CompletableDeferred<Unit>()
            move=launch {window.latest {positioned.complete(Unit)}}
            withTimeout(3000) {positioned.await()}
            assertTrue(window.openingReady.value)
            assertEquals(cached.value.first {it.seq==352L}.messageId,window.openingAnchor.value)
        } finally {move?.cancelAndJoin();collector.cancelAndJoin();gate.complete(Unit)}
    }
    @Test fun revalidationOfFirstMessageMustUseValidWireAnchor()=runBlocking<Unit> {
        val chatId=Uuid.random().toString()
        val cached=Message(Uuid.random().toString(),Uuid.random().toString(),chatId,
            Uuid.random().toString(),1,1,null,null,false,"",null,emptyList())
        val state=MutableStateFlow(true to 0L)
        val attempted=CompletableDeferred<Unit>()
        val source=object:HistoryWindowSource {
            override val recovery=state
            override val online=true
            override fun observe(lower:Long,upper:Long?,limit:Int)=flowOf(listOf(cached))
            override suspend fun page(direction:String,anchor:Long?,limit:Int):History {
                if(direction=="after") attempted.complete(Unit)
                dev.messenger.data.wire.HistoryParams(chatId,direction,anchor,limit)
                return History(listOf(cached),1,1,false,false)
            }
            override suspend fun sequence(id:String)=1L
            override suspend fun members() {}
        }
        val rendered=MutableStateFlow(emptyList<Message>())
        val window=HistoryWindow(source)
        val collector=launch {window.history.collect {rendered.value=it}}
        try {
            withTimeout(3000) {rendered.first {it.isNotEmpty()}}
            state.value=true to 1L
            withTimeout(3000) {attempted.await()}
            yield()
            assertNull(window.error.value,"Revalidation built anchorSeq=0, rejected by HistoryParams")
        } finally {collector.cancelAndJoin()}
    }
    @Test fun cachedHistoryMustRenderWhileInitialNetworkRequestWaits()=runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val cached=Message(Uuid.random().toString(),Uuid.random().toString(),Uuid.random().toString(),
            Uuid.random().toString(),1,1,null,null,false,"",null,emptyList())
        val source=object:HistoryWindowSource {
            override val recovery=MutableStateFlow(true to 0L)
            override val online=true
            override fun observe(lower:Long,upper:Long?,limit:Int)=flowOf(listOf(cached))
            override suspend fun page(direction:String,anchor:Long?,limit:Int):History {
                entered.complete(Unit);release.await()
                return History(listOf(cached),1,1,false,false)
            }
            override suspend fun sequence(id:String)=1L
            override suspend fun members() {}
        }
        val rendered=MutableStateFlow(emptyList<Message>())
        val window=HistoryWindow(source)
        val collector=launch {window.history.collect {rendered.value=it}}
        try {
            entered.await()
            val visible=withTimeoutOrNull(500) {rendered.first {it.isNotEmpty()}}
            assertNotNull(visible,"Room subscription is gated on network completion despite cached data")
        } finally {release.complete(Unit);collector.cancelAndJoin()}
    }
}
