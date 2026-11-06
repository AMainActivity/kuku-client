package dev.messenger.feature.conversation

import androidx.lifecycle.ViewModelStore
import dev.messenger.domain.*
import dev.messenger.domain.model.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test
import kotlin.test.*
import kotlin.uuid.Uuid

@OptIn(ExperimentalCoroutinesApi::class)
class ComposerLimitTest {
    private inline fun <reified T> fake(noinline call:(String)->Any?):T =
        Proxy.newProxyInstance(T::class.java.classLoader,arrayOf(T::class.java)) {_,method,_ -> call(method.name)} as T

    @Test fun rejectedTextDoesNotClearComposerOrItsAttachments()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val owner=ViewModelStore()
        try {
            val account=ActiveAccount(Uuid.random().toString(),Uuid.random().toString(),Uuid.random().toString())
            val chat=ChatId(Uuid.random())
            val file=Uuid.random().toString()
            var sends=0
            val delegate=fake<MessagingRepository> {name -> when(name) {
                "getConnection" -> MutableStateFlow(SessionState(true,account,false))
                "getError" -> MutableStateFlow<String?>(null)
                "getHistoryGeneration" -> MutableStateFlow(0L)
                "chat" -> flowOf<Chat?>(null)
                "members", "chats" -> flowOf(emptyList<Any>())
                "send" -> {sends++;Unit}
                else -> error(name)
            } }
            val repository=object:MessagingRepository by delegate {
                override fun validateText(text:String) {throw ApplicationFailure("MESSAGE_TOO_LARGE")}
            }
            val local=fake<LocalMessages> {name -> when(name) {
                "observeDraft" -> flowOf(Draft(chat,"ЯЯЯ",1))
                "observePending" -> flowOf(emptyList<PendingMessage>())
                else -> error(name)
            } }
            val store=fake<DraftRepository> {name -> when(name) {
                "messages" -> local
                "composer" -> ComposerExtras("reply",listOf(file))
                else -> error(name)
            } }
            val transfers=fake<AttachmentRepository> {name -> check(name=="observe");flowOf(emptyList<TransferSnapshot>())}
            val model=ChatViewModel(account.key,chat.value.toString(),repository,store,transfers)
            owner.put("chat",model);runCurrent()
            model.send();runCurrent()
            assertEquals("ЯЯЯ",model.text.value)
            assertEquals("reply",model.reply.value)
            assertEquals(listOf(file),model.selectedFiles.value)
            assertEquals("MESSAGE_TOO_LARGE",model.error.value)
            assertEquals(0,sends)
        } finally {owner.clear();Dispatchers.resetMain()}
    }
}
