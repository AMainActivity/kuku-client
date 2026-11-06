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
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlin.test.*

class NavigationTest {
    @Test fun allRoutesCanRoundTripWithExplicitMultiplatformSerializers() {
        val json = Json { serializersModule = navigationConfig.serializersModule }
        val serializer = PolymorphicSerializer(NavKey::class)
        listOf(ServerRoute, LoginRoute("example.com", 443), ChatsRoute, AboutRoute, LicensesRoute).forEach { route ->
            assertEquals(route, json.decodeFromString(serializer, json.encodeToString(serializer, route)))
        }
    }
    @Test fun backCannotEmptyTheRoot() {
        val stack = mutableListOf<NavKey>(ServerRoute, LoginRoute("example.com", 443))
        popIfNotRoot(stack)
        popIfNotRoot(stack)
        assertEquals(listOf<NavKey>(ServerRoute), stack)
    }
}
