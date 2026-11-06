package dev.messenger.feature.conversation
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import dev.messenger.core.ui.*

import kotlinx.coroutines.flow.first
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import org.jetbrains.compose.resources.painterResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.messenger.domain.OutboxState
import dev.messenger.ui.resources.*
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ConversationScreen(accountKey: String, chatId: String, back: () -> Unit, details: () -> Unit) {
    val model = koinViewModel<ChatViewModel>(key = "$accountKey:$chatId", parameters = { parametersOf(accountKey, chatId) })
    val connection by model.connection.collectAsStateWithLifecycle()
    val syncError by model.syncError.collectAsStateWithLifecycle()
    val historyError by model.historyError.collectAsStateWithLifecycle()
    val chat by model.chat.collectAsStateWithLifecycle()
    val avatar by model.avatar.collectAsStateWithLifecycle()
    val messages by model.history.collectAsStateWithLifecycle()
    val pending by model.pending.collectAsStateWithLifecycle()
    val text by model.text.collectAsStateWithLifecycle()
    val loaded by model.loaded.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val more by model.more.collectAsStateWithLifecycle()
    val loadingHistory by model.loading.collectAsStateWithLifecycle()
    val reply by model.reply.collectAsStateWithLifecycle()
    val targets by model.targets.collectAsStateWithLifecycle()
    val files by model.files.collectAsStateWithLifecycle()
    val members by model.members.collectAsStateWithLifecycle()
    val selectedFiles by model.selectedFiles.collectAsStateWithLifecycle()
    val pickFile = rememberFilePicker(model)
    val shareLauncher=rememberShareLauncher(model)
    val preparingShare by model.preparingShare.collectAsStateWithLifecycle()
    val openFile = rememberFileOpener(model)
    var gallery by remember(accountKey,chatId) {mutableStateOf<GalleryRequest?>(null)}
    var documentId by remember(accountKey,chatId) {mutableStateOf<String?>(null)}
    val document=files.firstOrNull {it.direction=="DOWNLOAD" && it.remoteId==documentId}
    val documentPath by rememberAvailableFile(document,model.transfers.files)
    val documentLifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(documentId,documentPath,documentLifecycle) {
        documentLifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            if(documentId!=null && documentPath!=null && document!=null) {
                documentId=null
                openFile(document)
            }
        }
    }
    gallery?.let {request ->
        AttachmentViewer(request,files,model.transfers.files,model::download,
            {model.transferAction(it,"resume")},{shareLauncher(ShareBundle("",listOf(it)))},{gallery=null})
    }
    var selected by remember { mutableStateOf<dev.messenger.domain.model.Message?>(null) }
    var operation by remember { mutableStateOf("") }
    var editText by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val openingCursor by model.openingCursor.collectAsStateWithLifecycle()
    val openingReady by model.openingReady.collectAsStateWithLifecycle()
    val openingAnchor by model.openingAnchor.collectAsStateWithLifecycle()
    val latestAnchor by model.latestAnchor.collectAsStateWithLifecycle()
    val atTail by model.atTail.collectAsStateWithLifecycle()
    val bottomRequest by model.bottomRequest.collectAsStateWithLifecycle()
    val movingToBottom by model.movingToBottom.collectAsStateWithLifecycle()
    var positioned by rememberSaveable(accountKey,chatId,openingCursor) {mutableStateOf(false)}
    var handledBottom by rememberSaveable(accountKey,chatId) {mutableStateOf(0L)}
    val pendingCount=pending.count {item -> messages.none {it.clientMessageId==item.operationId.value.toString()}}
    val dividerIndex=openingCursor?.let {cursor ->
        messages.reversed().indexOfLast {it.seq>cursor}.takeIf {it>=0}?.let {it+1+pendingCount}
    }
    val scrollTarget by model.scrollTarget.collectAsStateWithLifecycle()
    val replyTargets by model.replyTargets.collectAsStateWithLifecycle()
    var highlightedMessageId by remember {mutableStateOf<String?>(null)}
    val receipts by model.receipts.collectAsStateWithLifecycle()
    var selection by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var forwardSelection by remember { mutableStateOf(false) }
    val messageClock by rememberMessageClock()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    LaunchedEffect(openingReady,openingCursor,positioned) {
        if(positioned || !openingReady) return@LaunchedEffect
        snapshotFlow {openingAnchor==null || messages.firstOrNull()?.messageId==openingAnchor}.first {it}
        val index=dividerIndex ?: 0
        snapshotFlow {listState.layoutInfo.totalItemsCount}.first {it>index || messages.isEmpty()}
        listState.scrollToItem(index)
        positioned=true
    }
    LaunchedEffect(bottomRequest) {
        if(bottomRequest==0L || bottomRequest==handledBottom) return@LaunchedEffect
        try {
            snapshotFlow {latestAnchor==null || messages.any {it.messageId==latestAnchor}}.first {it}
            // Wait for the new Room window to reach LazyColumn, then snap as in MUI.
            snapshotFlow {listState.layoutInfo.totalItemsCount}.first {it>0 || messages.isEmpty()}
            listState.scrollToItem(0)
            positioned=true;handledBottom=bottomRequest
        } finally {model.finishBottom()}
    }
    LaunchedEffect(scrollTarget) {
        val target=scrollTarget ?: return@LaunchedEffect
        try {
            androidx.compose.runtime.snapshotFlow {messages}.first {rows -> rows.any {it.messageId==target}}
            // As in MUI: a live append/outbox update can move the index during animation.
            suspend fun scrollToTarget():Boolean {
                repeat(REPLY_SCROLL_ATTEMPTS) {
                    val current=messages
                    val row=current.reversed().indexOfFirst {it.messageId==target}
                    if(row<0) return false
                    val known=current.map {it.clientMessageId}.toSet()
                    val base=row+pending.count {it.operationId.value.toString() !in known}
                    val index=base+if(dividerIndex!=null && base>=requireNotNull(dividerIndex)) 1 else 0
                    listState.animateScrollToItem(index)
                    if(listState.layoutInfo.visibleItemsInfo.any {it.key==target}) return true
                }
                return false
            }
            if(!scrollToTarget()) return@LaunchedEffect
            positioned=true
            highlightedMessageId=target
            kotlinx.coroutines.delay(REPLY_HIGHLIGHT_MILLIS)
        } finally {
            highlightedMessageId=null
            model.finishJump(target)
        }
    }
    if(forwardSelection) AlertDialog(onDismissRequest={forwardSelection=false},
        title={Text(stringResource(Res.string.forward_message))},
        text={Column(Modifier.heightIn(max=300.dp).verticalScroll(rememberScrollState())) {
            targets.filter { it.membership.status=="active" }.forEach { target ->
                TextButton(onClick={
                    model.forwardMany(messages.filter { it.messageId in selection }.sortedBy { it.seq }.map { it.messageId },target.chatId)
                    selection=emptyList();forwardSelection=false
                }) {Text(target.title.ifBlank { target.peer?.displayName.orEmpty() })}
            }
        }},confirmButton={},dismissButton={TextButton(onClick={forwardSelection=false}) {Text(stringResource(Res.string.cancel))}})
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model,lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            model.shareReady.collect { bundle -> if(bundle!=null) { shareLauncher(bundle);model.shareConsumed() } }
        }
    }
    LaunchedEffect(listState, messages, lifecycle,positioned,movingToBottom,scrollTarget) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.key }.toSet() }
                .collect { keys -> if(positioned && !movingToBottom && scrollTarget==null) messages.filter { it.messageId in keys }.maxOfOrNull { it.seq }?.let(model::read) }
        }
    }
    LaunchedEffect(listState,model,lifecycle,messages,connection.online,loadingHistory,more,scrollTarget,positioned,movingToBottom) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            snapshotFlow {
                val layout=listState.layoutInfo
                val last=layout.visibleItemsInfo.lastOrNull()?.index
                (last!=null && last>=layout.totalItemsCount-1-HISTORY_PREFETCH_ITEMS) to
                    ((layout.visibleItemsInfo.firstOrNull()?.index ?: Int.MAX_VALUE)<=HISTORY_PREFETCH_ITEMS)
            }.collect { (nearOld,nearNew) ->
                if(positioned && !movingToBottom && scrollTarget==null) model.onHistoryViewport(nearOld,nearNew)
            }
        }
    }
    val chosen = selected
    if (chosen != null) AlertDialog(onDismissRequest = { selected = null; operation = "" },
        title = { Text(stringResource(Res.string.message_actions)) },
        text = {
            Column {
                when (operation) {
                    "edit" -> OutlinedTextField(editText, { editText = it }, label = { Text(stringResource(Res.string.message)) })
                    "delete" -> Text(stringResource(Res.string.delete_message_question))
                    "receipts" -> Column(Modifier.heightIn(max=300.dp).verticalScroll(rememberScrollState())) {
                        receipts.filter { it.userId!=chosen.senderId && it.status=="active" }.forEach { member ->
                            Text(member.displayName + ": " + stringResource(when {
                                member.readSeq>=chosen.seq -> Res.string.message_read
                                member.deliveredSeq>=chosen.seq -> Res.string.message_delivered
                                else -> Res.string.message_sent
                            }))
                        }
                    }
                    "forward" -> Column(Modifier.heightIn(max=300.dp).verticalScroll(rememberScrollState())) {
                        targets.filter { it.membership.status == "active" }.forEach { target ->
                            TextButton(onClick = { model.forward(chosen, target.chatId); selected = null; operation = "" }) {
                                Text(target.title.ifBlank { target.peer?.displayName.orEmpty() })
                            }
                        }
                    }
                    else -> {
                        TextButton(onClick = { model.selectReply(chosen.messageId); selected = null }) { Text(stringResource(Res.string.reply_message)) }
                        TextButton(onClick={clipboard.setText(androidx.compose.ui.text.AnnotatedString(chosen.plainTextOrNull().orEmpty()));selected=null}) {Text(stringResource(Res.string.copy_text))}
                        TextButton(onClick={model.share(listOf(chosen.messageId));selected=null}) {Text(stringResource(Res.string.share_messages))}
                        TextButton(onClick={selection=listOf(chosen.messageId);selected=null}) {Text(stringResource(Res.string.select_messages))}
                        TextButton(onClick={operation="receipts";model.loadReceipts()}) {Text(stringResource(Res.string.message_receipts))}
                        TextButton(onClick = { operation = "forward" }) { Text(stringResource(Res.string.forward_message)) }
                        if(chosen.senderId == accountKey.substringAfter(':')) {
                            TextButton(onClick = { operation = "edit"; editText = chosen.plainTextOrNull().orEmpty() }) { Text(stringResource(Res.string.edit_message)) }
                            TextButton(onClick = { operation = "delete" }) { Text(stringResource(Res.string.delete_message)) }
                        }
                    }
                }
            }
        }, confirmButton = {
            if(operation == "edit" || operation == "delete") TextButton(onClick = {
                if(operation == "edit") model.update(chosen, editText) else model.delete(chosen)
                selected = null; operation = ""
            }) { Text(stringResource(Res.string.confirm)) }
        }, dismissButton = { TextButton(onClick={ selected=null; operation="" }) { Text(stringResource(Res.string.cancel)) } })
    val chatTitle=chat?.title?.ifBlank {chat?.peer?.displayName.orEmpty()}.orEmpty()
    AppPage(chatTitle,back,titleContent={
        Row(Modifier.fillMaxWidth().clickable(onClick=details).padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            AvatarImage(avatar,chatTitle,40.dp)
            Column {
                Text(chatTitle,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,style=MaterialTheme.typography.titleMedium)
            }
        }
    }) {
    Column(Modifier.fillMaxSize().imePadding().padding(12.dp)) {

        ErrorNotice(error,onDismiss={model.reportError(null)})
        ErrorNotice(historyError ?: syncError)
        if(preparingShare) { LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick=model::cancelShare) {Text(stringResource(Res.string.cancel_share))} }
        if(selection.isNotEmpty()) Row {
            Text(selection.size.toString()+"/20")
            TextButton(onClick={model.share(selection);selection=emptyList()}) {Text(stringResource(Res.string.share_messages))}
            TextButton(onClick={forwardSelection=true}) {Text(stringResource(Res.string.forward_message))}
            TextButton(onClick={selection=emptyList()}) {Text(stringResource(Res.string.cancel))}
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        // Cached content is visible even when the unread boundary is unavailable.
        // The positioned flag separately gates read receipts and automatic pagination.
        LazyColumn(Modifier.fillMaxSize(), state = listState, reverseLayout = true, contentPadding=PaddingValues(top=if(openingCursor!=null && !more) maxHeight else 0.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val known = messages.map { it.clientMessageId }.toSet()
            items(pending.filterNot { it.operationId.value.toString() in known }.reversed(), key = { "pending-${it.operationId.value}" }) { item ->
                MessageBubble(own=true) {
                        Text(item.text)
                        Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text(formatMessageTimestamp(kotlin.time.Instant.fromEpochMilliseconds(item.createdAtMillis).toString(),messageClock),
                                style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(item.state==OutboxState.FAILED) {
                                val retryLabel=stringResource(Res.string.retry)
                                Icon(painterResource(Res.drawable.message_failed),stringResource(Res.string.send_failed),
                                    Modifier.size(18.dp).clickable(onClickLabel=retryLabel,role=androidx.compose.ui.semantics.Role.Button) {
                                        model.retry(item.operationId.value.toString())
                                    },tint=MaterialTheme.colorScheme.error)
                            } else {
                                Icon(painterResource(Res.drawable.message_pending),stringResource(Res.string.pending),
                                    Modifier.size(18.dp),tint=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
            }
            messages.reversed().forEachIndexed { index,message ->
            item(key=message.messageId) {
                val own = message.senderId == accountKey.substringAfter(':')
                MessageBubble(own=own,highlighted=highlightedMessageId==message.messageId,bot=members.any {it.userId==message.senderId && it.kind=="bot"},modifier=Modifier.combinedClickable(enabled = !message.deleted,onLongClick={if(selection.isEmpty()) selected=message},onClick={if(selection.isNotEmpty()) selection=if(message.messageId in selection) selection-message.messageId else if(selection.size<20) selection+message.messageId else selection})) {
                        if(message.messageId in selection) Text("✓")
                        message.replyTo?.let {target ->
                            val quoted=messages.firstOrNull {it.messageId==target} ?: replyTargets[target]
                            val author=members.firstOrNull {it.userId==quoted?.senderId}?.displayName
                                ?: chat?.peer?.takeIf {it.userId==quoted?.senderId}?.displayName
                            ReplyQuote(quoted,author,loading=scrollTarget==target && loadingHistory,onClick={model.jump(target)})
                        }
                        Text(message.plainTextOrNull() ?: stringResource(if (message.deleted) Res.string.deleted_message else Res.string.unsupported_message))
                        message.attachments.forEach { attachment ->
                            val job=files.firstOrNull {it.direction=="DOWNLOAD" && it.remoteId==attachment.attachmentId}
                            AttachmentTile(attachment,job,model.transfers.files,playbackAllowed=gallery==null,open={
                                when {
                                    galleryMedia(attachment.mime) -> gallery=GalleryRequest(message.attachments,attachment.attachmentId)
                                    audioMedia(attachment.mime) -> model.download(attachment)
                                    else -> {documentId=attachment.attachmentId;model.download(attachment)}
                                }
                                                        },retry={
                                if(!galleryMedia(attachment.mime) && !audioMedia(attachment.mime)) documentId=attachment.attachmentId
                                if(job!=null) model.transferAction(job,"resume") else model.download(attachment)
                            })
                        }
                        if(members.any { it.userId==message.senderId && it.kind=="bot" }) Text(stringResource(Res.string.bot))
                        Row(Modifier.align(Alignment.End),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                            Text(formatMessageTimestamp(message.createdAt,messageClock),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            if(own) {
                                val status=messageReceipt(message.seq,message.senderId,members)
                                Icon(painterResource(if(status==MessageReceipt.SENT) Res.drawable.mui_done else Res.drawable.mui_done_all),
                                    stringResource(when(status) {
                                        MessageReceipt.READ -> Res.string.message_read
                                        MessageReceipt.DELIVERED -> Res.string.message_delivered
                                        MessageReceipt.SENT -> Res.string.message_sent
                                    }),Modifier.size(18.dp),
                                    tint=if(status==MessageReceipt.READ) androidx.compose.ui.graphics.Color(9,139,233) else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                }
            }
            if(dividerIndex==index+1+pendingCount) item(key="unread-divider") {
                Surface(Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.secondaryContainer) {
                    Text(stringResource(Res.string.unread_divider),Modifier.padding(vertical=7.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center,style=MaterialTheme.typography.labelMedium)
                }
            }
            }
            if (loadingHistory && scrollTarget==null) item(key="history-loading") {
                Box(Modifier.fillMaxWidth().padding(12.dp),contentAlignment=Alignment.Center) {CircularProgressIndicator(Modifier.size(24.dp))}
            }
        }
        val awayFromLatest by remember {derivedStateOf {
            listState.firstVisibleItemIndex>0 || listState.firstVisibleItemScrollOffset>0
        }}
        if(positioned && (!atTail || awayFromLatest || (chat?.lastMessage?.seq ?: 0)>(messages.lastOrNull()?.seq ?: 0))) {
            SmallFloatingActionButton(onClick=model::latest,modifier=Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                BadgedBox(badge={if((chat?.unreadCount ?: 0)>0) Badge {Text(requireNotNull(chat).unreadCount.toString())}}) {
                    Icon(painterResource(Res.drawable.mui_down),stringResource(Res.string.latest_messages))
                }
            }
        }
        if(loaded && messages.isEmpty() && pending.isEmpty() && !more && error==null && syncError==null) {
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {
                EmptyState(stringResource(Res.string.no_messages_title),stringResource(if(chat?.membership?.status=="active") Res.string.no_messages_hint else Res.string.no_messages_readonly))
            }
        }
        }
        reply?.let { target ->
            TextButton(onClick={model.selectReply(null)}) {
                Text(stringResource(Res.string.reply_message)+": "+(messages.firstOrNull {it.messageId==target}?.plainTextOrNull() ?: "…")+" ×")
            }
        }
        if(text.substringAfterLast(' ').startsWith("@")) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            members.filter { it.status=="active" && it.handle?.startsWith(text.substringAfterLast(' ').removePrefix("@"))==true }.take(10).forEach { member ->
                TextButton(onClick={model.edit(text.dropLast(text.substringAfterLast(' ').length)+"@"+member.handle+" ")}) { Text(member.displayName + if(member.kind=="bot") " ["+stringResource(Res.string.bot)+"]" else "") }
            }
        }
        Column(Modifier.heightIn(max=170.dp).verticalScroll(rememberScrollState())) {
            files.filter { it.state !in setOf("CANCELLED","READY") || it.jobId in selectedFiles }.forEach { job ->
                Text(job.filename + " " + job.progress + "/" + job.size)
                Row {
                    if(job.state in setOf("PAUSED","FAILED")) TextButton(onClick={model.transferAction(job,"resume")}) { Text(stringResource(Res.string.retry)) }
                    else if(job.state!="READY") TextButton(onClick={model.transferAction(job,"pause")}) { Text(stringResource(Res.string.pause_transfer)) }
                    TextButton(onClick={model.transferAction(job,"cancel")}) { Text(stringResource(Res.string.cancel)) }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick=pickFile, enabled=loaded && chat?.membership?.status=="active") {Icon(painterResource(Res.drawable.mui_attach),stringResource(Res.string.attach_file))}
            OutlinedTextField(text, model::edit, enabled = loaded && chat?.membership?.status == "active", modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(Res.string.message)) }, maxLines = 5)
            FilledIconButton(onClick = model::send, enabled = loaded && chat?.membership?.status == "active" && (text.isNotBlank() || selectedFiles.isNotEmpty())) {Icon(painterResource(Res.drawable.mui_send),stringResource(Res.string.send))}
        }
    }
    }
}

private const val HISTORY_PREFETCH_ITEMS=6

private const val REPLY_HIGHLIGHT_MILLIS=1500L
private const val REPLY_SCROLL_ATTEMPTS=3

