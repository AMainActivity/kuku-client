package dev.messenger.ui

import androidx.navigation3.runtime.NavKey
import kotlin.test.*

class RevokedNavigationTest {
    @Test fun removesAllRelatedRoutesAndPreservesOtherChat() {
        val stack=mutableListOf<NavKey>(ChatsRoute,ConversationRoute("other"),ConversationRoute("deleted"),GroupRoute("deleted"),InviteRoute("deleted",false))
        assertEquals("group_deleted",removeRevokedChatRoutes(stack,mapOf("deleted" to "group_deleted")))
        assertEquals(listOf(ChatsRoute,ConversationRoute("other")),stack)
        assertNull(removeRevokedChatRoutes(stack,mapOf("deleted" to "group_deleted")))
    }
    @Test fun restoredDeletedRouteReturnsToChatList() {
        val stack=mutableListOf<NavKey>(GroupRoute("deleted"))
        assertEquals("group_deleted",removeRevokedChatRoutes(stack,mapOf("deleted" to "group_deleted")))
        assertEquals(listOf<NavKey>(ChatsRoute),stack)
    }
}
