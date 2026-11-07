package dev.messenger.ui
import dev.messenger.runtime.*
import dev.messenger.core.ui.*
import dev.messenger.feature.chats.*
import dev.messenger.feature.auth.*
import dev.messenger.feature.profile.*
import dev.messenger.feature.conversation.*
import dev.messenger.feature.groups.*
import dev.messenger.feature.admin.*


import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.*

@Serializable data object ServerRoute : NavKey
@Serializable data class LoginRoute(val host: String, val port: Int) : NavKey
// Reserved for the authenticated graph; the shell cannot manufacture an authenticated account.
@Serializable data object ChatsRoute : NavKey
@Serializable data object NewChatRoute : NavKey
@Serializable data class ConversationRoute(val chatId: String) : NavKey

@Serializable data object AboutRoute : NavKey
@Serializable data object LicensesRoute : NavKey
@Serializable data object AccountRoute : NavKey
@Serializable data object AdminRoute : NavKey
@Serializable data class GroupRoute(val chatId: String) : NavKey
@Serializable data class InviteRoute(val chatId: String, val bots: Boolean) : NavKey

val navigationConfig = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(ServerRoute::class, ServerRoute.serializer())
            subclass(AboutRoute::class,AboutRoute.serializer())
            subclass(LicensesRoute::class, LicensesRoute.serializer())
            subclass(AccountRoute::class, AccountRoute.serializer())
            subclass(AdminRoute::class, AdminRoute.serializer())
            subclass(GroupRoute::class, GroupRoute.serializer())
            subclass(InviteRoute::class, InviteRoute.serializer())
            subclass(LoginRoute::class, LoginRoute.serializer())
            subclass(ChatsRoute::class, ChatsRoute.serializer())
            subclass(NewChatRoute::class, NewChatRoute.serializer())
            subclass(ConversationRoute::class, ConversationRoute.serializer())
        }
    }
}

/** Remove revoked chats from the whole stack, including restored info/invitation routes. */
internal fun removeRevokedChatRoutes(stack:MutableList<NavKey>,revoked:Map<String,String?>):String? {
    fun chatId(route:NavKey):String? = when(route) {
        is ConversationRoute -> route.chatId
        is GroupRoute -> route.chatId
        is InviteRoute -> route.chatId
        else -> null
    }
    val affected=stack.mapNotNull(::chatId).filter {it in revoked}
    if(affected.isEmpty()) return null
    val reason=if(affected.any {revoked[it]=="group_deleted"}) "group_deleted" else "access_revoked"
    stack.removeAll {chatId(it) in revoked}
    if(stack.isEmpty()) stack.add(ChatsRoute)
    return reason
}

fun popIfNotRoot(stack: MutableList<NavKey>) {
    if (stack.size > 1) stack.removeAt(stack.lastIndex)
}
