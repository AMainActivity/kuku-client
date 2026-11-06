package dev.messenger.feature.conversation
import dev.messenger.domain.*
import dev.messenger.domain.model.*

import dev.messenger.domain.MessagingRepository
import dev.messenger.domain.model.Message
import dev.messenger.domain.model.History
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class HistoryBounds(val lower:Long=0,val upper:Long?=null)

/** Only this bounded Room query is collected by the screen. Eviction never deletes cached history. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class HistoryWindow(private val source:HistoryWindowSource) {
    constructor(repository:MessagingRepository,account:String,chat:String):this(object:HistoryWindowSource {
        override val recovery=combine(repository.connection.map {it.online}.distinctUntilChanged(),repository.historyGeneration) {online,generation -> online to generation}
        override val online get()=repository.connection.value.online
        override fun observe(lower:Long,upper:Long?,limit:Int)=repository.observeHistoryWindow(account,chat,lower,upper,limit)
        override suspend fun page(direction:String,anchor:Long?,limit:Int)=repository.historyPage(account,chat,direction,anchor,limit)
        override suspend fun sequence(id:String)=repository.messageSequence(account,chat,id)
        override suspend fun unreadCursor()=repository.initialUnreadCursor(account,chat)
        override suspend fun members() {repository.loadMembers(account,chat)}
    })
    companion object { const val CAPACITY=150;const val PAGE_SIZE=50 }
    private val bounds=MutableStateFlow(HistoryBounds())
    private val mutex=Mutex()
    private var rows:List<Message> = emptyList()
    private var initialized=false
    private var oldAttempt:Long?=null
    private var newAttempt:Long?=null
    val openingAnchor=MutableStateFlow<String?>(null)
    val latestAnchor=MutableStateFlow<String?>(null)
    val openingCursor=MutableStateFlow<Long?>(null)
    val openingReady=MutableStateFlow(false)
    val atTail=MutableStateFlow(true)
    val loading=MutableStateFlow(false)
    val error=MutableStateFlow<String?>(null)
    val moreBefore=MutableStateFlow(true)
    private var moreAfter=true
    val history:Flow<List<Message>> = channelFlow {
        launch {
            openingReady.first {it}
            bounds.flatMapLatest { source.observe(it.lower,it.upper,CAPACITY) }.collect {
                rows=it;if(it.isNotEmpty() && it.first().seq>bounds.value.lower) moreBefore.value=true;send(it)
            }
        }
        launch {
            source.recovery.distinctUntilChanged().collectLatest { (online,_) ->
                oldAttempt=null;newAttempt=null
                if(online || !initialized) runOperation {
                    if(!initialized) {
                        val cursor=source.unreadCursor()
                        val page=source.page(if(cursor==null) "before" else "around",cursor?.plus(1),PAGE_SIZE)
                        openingCursor.value=cursor
                        openingAnchor.value=page.items.firstOrNull()?.messageId
                        bounds.value=HistoryBounds(page.beforeSeq ?: 0,if(cursor==null) null else page.afterSeq)
                        atTail.value=cursor==null || !page.hasMoreAfter
                        moreBefore.value=page.hasMoreBefore;moreAfter=page.hasMoreAfter;initialized=true
                        openingReady.value=true
                    } else {
                        // Revalidate only the displayed range, never all accumulated cache.
                        val current=rows
                        var anchor=current.firstOrNull()?.seq?.minus(1)
                        val last=current.lastOrNull()?.seq
                        if(last!=null) do {
                            val page=source.page("after",anchor,PAGE_SIZE)
                            val next=page.afterSeq ?: break
                            if(anchor!=null && next<=anchor) break
                            anchor=next
                        } while(page.hasMoreAfter && next<last)
                        if(bounds.value.upper==null) source.page("before",null,PAGE_SIZE)
                    }
                    if(online) source.members()
                }
            }
        }
    }
    private suspend fun runOperation(block:suspend ()->Unit) = mutex.withLock {
        loading.value=true
        try {block();error.value=null}
        catch(cancelled:CancellationException) {throw cancelled}
        catch(failure:Exception) {error.value=failureCode(failure)}
        finally {loading.value=false}
    }
    suspend fun viewport(nearOld:Boolean,nearNew:Boolean) {
        if(loading.value || rows.isEmpty()) return
        val current=rows
        // Freeze the upper edge when reading history so arriving messages cannot evict the viewport.
        if(!nearNew && bounds.value.upper==null) {
            bounds.value=HistoryBounds(current.first().seq,current.last().seq);moreAfter=true;atTail.value=false
        }
        if(!nearOld) oldAttempt=null
        if(!nearNew) newAttempt=null
        if(nearOld && moreBefore.value && oldAttempt!=current.first().seq) {
            oldAttempt=current.first().seq
            runOperation {
                val page=source.page("before",current.first().seq,PAGE_SIZE)
                val combined=(page.items+current).distinctBy {it.messageId}.sortedBy {it.seq}.take(CAPACITY)
                if(page.items.isNotEmpty()) {
                    bounds.value=HistoryBounds(combined.first().seq,combined.last().seq)
                    moreAfter=true;atTail.value=false
                }
                if(source.online) moreBefore.value=page.hasMoreBefore
            }
        } else if(nearNew && bounds.value.upper!=null && newAttempt!=current.last().seq) {
            newAttempt=current.last().seq
            runOperation {
                val page=source.page("after",current.last().seq,PAGE_SIZE)
                val combined=(current+page.items).distinctBy {it.messageId}.sortedBy {it.seq}.takeLast(CAPACITY)
                val tail=source.online && !page.hasMoreAfter
                bounds.value=HistoryBounds(combined.first().seq,if(tail) null else combined.last().seq)
                moreAfter=!tail;atTail.value=tail;moreBefore.value=true
            }
        }
    }
    suspend fun latest(onReady:()->Unit = {}) = runOperation {
        // Show the local tail/outbox immediately, without waiting for the network.
        val cached=source.observe(0,null,PAGE_SIZE).first()
        bounds.value=HistoryBounds(cached.firstOrNull()?.seq ?: 0,null)
        latestAnchor.value=cached.lastOrNull()?.messageId
        atTail.value=true;oldAttempt=null;newAttempt=null
        onReady()
        val page=source.page("before",null,PAGE_SIZE)
        bounds.value=HistoryBounds(page.beforeSeq ?: 0,null)
        latestAnchor.value=page.items.lastOrNull()?.messageId
        atTail.value=true;moreBefore.value=page.hasMoreBefore;moreAfter=false
        oldAttempt=null;newAttempt=null;initialized=true
    }
    suspend fun jump(messageId:String) {
        // A target already in the active Room window needs only UI scrolling.
        if(rows.any {it.messageId==messageId}) {error.value=null;return}
        runOperation {
            val seq=source.sequence(messageId)
            val page=source.page("around",seq,PAGE_SIZE)
            if(page.items.none {it.messageId==messageId}) error("MESSAGE_UNAVAILABLE")
            bounds.value=HistoryBounds(requireNotNull(page.beforeSeq),page.afterSeq)
            moreBefore.value=page.hasMoreBefore;moreAfter=page.hasMoreAfter;atTail.value=!page.hasMoreAfter
            oldAttempt=null;newAttempt=null;initialized=true
        }
    }
}
internal interface HistoryWindowSource {
    val recovery:Flow<Pair<Boolean,Long>>
    val online:Boolean
    fun observe(lower:Long,upper:Long?,limit:Int):Flow<List<Message>>
    suspend fun page(direction:String,anchor:Long?,limit:Int):History
    suspend fun sequence(id:String):Long
    suspend fun unreadCursor():Long? = null
    suspend fun members()
}
