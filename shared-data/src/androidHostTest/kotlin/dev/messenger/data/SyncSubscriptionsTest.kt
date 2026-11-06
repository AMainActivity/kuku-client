package dev.messenger.data

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import dev.messenger.data.network.ConnectionStatus
import dev.messenger.data.session.*
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SyncSubscriptionsTest {
    private val serverId = Uuid.random().toString()
    private val ownId = Uuid.random().toString()
    private val key = serverId + ":" + ownId
    private val groupId = Uuid.random().toString()
    private val directId = Uuid.random().toString()
    private val invitedId = Uuid.random().toString()
    private val otherGroupId = Uuid.random().toString()
    private val peer = User(Uuid.random().toString(), "Before", "human", null, "", null, 1, false)
    private val renamed = peer.copy(displayName = "After", avatarId = Uuid.random().toString(), version = 2)
    private val me = Me(ownId, "Self", "human", null, "", null, 1, false, "self@example.com", "user", false)
    private val newMe = me.copy(displayName = "New self", role = "admin", version = 2)
    private val removedId = Uuid.random().toString()
    private val joinedId = Uuid.random().toString()
    private fun member(id: String, name: String = "Before", role: String = "member", version: Long = 1) =
        MemberInfo(id, name, "human", null, role, "active", version, 0, 0)
    private val members = listOf(member(peer.userId), member(removedId))
    private val newMembers = listOf(member(peer.userId, "After", "admin", 2), member(joinedId, "Joined"))
    private fun chat(id: String, updated: Boolean = false): Chat = Chat(
        id, if (id == directId) "direct" else "group",
        if (id == directId) "" else if (updated) "New group" else "Group",
        if (updated) "New description" else "", if (updated) renamed.avatarId else null,
        if (updated) 2 else 1, Membership("member", if (id == invitedId) "invited" else "active", 1, 1),
        null, 0, if (id == directId) (if (updated) renamed else peer) else null,
        Preferences(updated, 0, updated, if (updated) 1 else 0))
    private fun public(me: Me) = User(me.userId, me.displayName, me.kind, me.handle, me.description, me.avatarId, me.version, me.deleted)
    private fun profileEvent(cursor: Long, user: User) = SyncEvent(cursor, "user.updated", wireJson.encodeToJsonElement(user).jsonObject, "")
    private fun chatEvent(cursor: Long, type: String, id: String = groupId) =
        SyncEvent(cursor, type, buildJsonObject { put("chatId", id) }, "")

    @Test fun hintsApplyAllSubscribedEntitiesBeforeAckAndHandleInvitationsAndRevocation() = verify(false)
    @Test fun migrationFrom12RepairsAcknowledgedProfilesAndMembershipsWithoutResettingCursor() = verify(true)

    @Test fun rejoinedMemberRefreshesVersionedProfileWhenRenameEventWasNotDelivered() =
        verify(false, deliverRenameEvent = false)

    @Test fun laterChatSnapshotWithDifferentNameTriggersOneVersionedProfileFetch() =
        verify(false, deliverRenameEvent = false, staleFirstSnapshot = true)

    private fun verify(legacy: Boolean, deliverRenameEvent: Boolean = true, staleFirstSnapshot: Boolean = false) = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication()
        val name = File(System.getProperty("java.io.tmpdir"), "subscriptions-" + Uuid.random() + ".db").absolutePath
        val initialCursor = if (legacy) 50L else 0L
        if (legacy) {
            val path = File(name)
            path.parentFile?.mkdirs()
            val schema = Json.parseToJsonElement(File(requireNotNull(System.getProperty("messenger.schemaDirectory")),
                "dev.messenger.data.MessengerDatabase/12.json").readText()).jsonObject.getValue("database").jsonObject
            val connection = BundledSQLiteDriver().open(path.absolutePath)
            try {
                fun sql(value: String) { connection.prepare(value).use { it.step() } }
                for (entity in schema.getValue("entities").jsonArray) {
                    val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                    sql(entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("$" + "{TABLE_NAME}", table))
                    for (index in entity.jsonObject["indices"]?.jsonArray.orEmpty()) {
                        sql(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("$" + "{TABLE_NAME}", table))
                    }
                }
                for (query in schema.getValue("setupQueries").jsonArray) sql(query.jsonPrimitive.content)
                sql("PRAGMA user_version=12")
                fun insert(statement: String, vararg values: String) {
                    connection.prepare(statement).use { query ->
                        values.forEachIndexed { index, value -> query.bindText(index + 1, value) }
                        query.step()
                    }
                }
                insert("INSERT INTO sync_state VALUES (?,50,NULL)", key)
                for (id in listOf(groupId, directId)) {
                    insert("INSERT INTO chats VALUES (?,?,?,0,0,'','')", key, id, wireJson.encodeToString(chat(id)))
                }
                for (member in members) insert("INSERT INTO members_cache VALUES (?,?,?,?)", key, groupId, member.userId, wireJson.encodeToString(member))
                insert("INSERT INTO account_profiles VALUES (?,?)", key, wireJson.encodeToString(me))
            } finally { connection.close() }
        }
        val database = if (legacy) buildDatabase(Room.databaseBuilder<MessengerDatabase>(context, name))
            else buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(context))
        val dao = database.conversations()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val current = AtomicReference(SyncPage(emptyList(), initialCursor, false, initialCursor))
        val ack = MutableStateFlow(-1L)
        val hint = MutableStateFlow<Long?>(null)
        val memberRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val profileRequests = java.util.concurrent.CopyOnWriteArrayList<String>()
        val sessionId = Uuid.random().toString()
        val deviceId = Uuid.random().toString()
        val secure = object : SecureStore {
            var value = wireJson.encodeToString(Vault(deviceId, SavedSession("example.com", 443,
                Credentials(sessionId, ownId, deviceId, serverId, "a".repeat(40), "r".repeat(40), "2100-01-01T00:00:00Z"))))
            override suspend fun read() = value
            override suspend fun write(value: String) { this.value = value }
        }
        val sessions = SessionCoordinator(secure, Connector { _, _, _ ->
            object : RpcChannel {
                override val serverId = this@SyncSubscriptionsTest.serverId
                override val status = MutableStateFlow(ConnectionStatus.OPEN)
                override val highWatermark = hint
                @Suppress("UNCHECKED_CAST")
                override suspend fun <P, R> request(command: Command<P, R>, params: P, operationId: Uuid?): R {
                    val response: Any = when (command.name) {
                        "auth.resume" -> SessionIdentity(sessionId, ownId, deviceId, serverId)
                        "sync.pull" -> {
                            val page = current.get()
                            assertTrue((params as SyncParams).cursor >= initialCursor, "Committed cursor was reset")
                            page.copy(items = page.items.filter { it.cursor > params.cursor })
                        }
                        "chats.get" -> chat((params as ChatParams).chatId, updated = true)
                        "groups.members.list" -> {
                            val request = params as ChatPageParams
                            assertNotEquals(invitedId, request.chatId, "Invitees cannot list members")
                            memberRequests.add(request.chatId)
                            if (request.chatId == otherGroupId) Page(listOf(members.first()), null)
                            else if (request.cursor == null) Page(listOf(newMembers.first()), "next")
                            else Page(listOf(newMembers.last()), null)
                        }
                        "users.get" -> {
                            val userId = (params as DirectParams).userId
                            profileRequests.add(userId)
                            when (userId) {
                                peer.userId -> renamed
                                joinedId -> User(userId, "Joined", "human", null, "", null, 2, false)
                                else -> User(userId, "Cached user", "human", null, "", null, 2, false)
                            }
                        }
                        "users.me" -> newMe
                        "sync.ack" -> {
                            val cursor = (params as AckParams).cursor
                            assertEquals(cursor, dao.syncState(key)?.cursor)
                            if (cursor == 8L || legacy) {
                                if (legacy || deliverRenameEvent) assertEquals("After", wireJson.decodeFromString<Chat>(dao.cachedChats(key).first { it.chatId == directId }.json).peer?.displayName)
                                assertNull(dao.member(key, groupId, removedId))
                                assertEquals("admin", wireJson.decodeFromString<MemberInfo>(requireNotNull(dao.member(key, groupId, peer.userId)).json).role)
                                assertEquals("New self", wireJson.decodeFromString<Me>(requireNotNull(dao.ownProfile(key)).json).displayName)
                            }
                            ack.value = cursor
                            Acknowledged(cursor)
                        }
                        "receipts.set" -> ReceiptAccepted(true)
                        "ping" -> Pong("alive")
                        else -> error("Unexpected command " + command.name)
                    }
                    return response as R
                }
                override suspend fun close() { status.value = ConnectionStatus.CLOSED }
            }
        }, owner)
        val repository = ConversationRepository(database, sessions, owner)
        try {
            if (!legacy) {
                dao.installCatalog(key, 0, "generation")
                dao.finishCatalog(key)
                dao.cacheChat(key, chat(groupId))
                dao.cacheChat(key, chat(directId))
                dao.cacheMe(key, me)
                dao.cacheUser(key, peer.copy(userId = removedId))
                dao.replaceMembers(key, groupId, members)
            }
            sessions.restore()
            repository.start()
            withTimeout(10_000) { ack.first { it == initialCursor } }
            if (legacy) {
                assertEquals(50L, dao.syncState(key)?.cursor)
                assertNotNull(dao.userProfile(key, peer.userId))
                return@runBlocking
            }
            val message = Message(Uuid.random().toString(), Uuid.random().toString(), groupId, peer.userId,
                1, 1, wireJson.encodeToJsonElement(PlainContent("New message")).jsonObject, null, false, "", null, emptyList())
            val edited = Message(message.messageId, message.clientMessageId, groupId, peer.userId,
                1, 2, wireJson.encodeToJsonElement(PlainContent("Edited")).jsonObject, null, false, "", null, emptyList())
            val receipt = SyncEvent(8, "receipt.updated", buildJsonObject {
                put("chatId", groupId); put("userId", peer.userId); put("kind", "read"); put("throughSeq", 1)
            }, "")
            if (!deliverRenameEvent) {
                // No shared active membership while the peer changed their name.
                dao.revoke(key, directId)
                dao.replaceMembers(key, groupId, listOf(members.last()))
            }
            val renameEvent = if (deliverRenameEvent) profileEvent(1, renamed)
                else chatEvent(1, "group.membershipChanged", if (staleFirstSnapshot) otherGroupId else groupId)
            current.set(SyncPage(listOf(renameEvent, profileEvent(2, public(newMe)),
                chatEvent(3, "group.membershipChanged"), chatEvent(4, "group.updated"), chatEvent(5, "chat.preferencesChanged"),
                SyncEvent(6, "message.created", wireJson.encodeToJsonElement(message).jsonObject, ""),
                SyncEvent(7, "message.updated", wireJson.encodeToJsonElement(edited).jsonObject, ""), receipt), 8, false, 8))
            hint.value = 8
            withTimeout(10_000) { ack.first { it == 8L } }
            val actualChat = requireNotNull(repository.chat(key, groupId).first())
            if (!deliverRenameEvent) {
                assertEquals(1, profileRequests.count { it == peer.userId })
                assertEquals("After", repository.members(key, groupId).first().first { it.userId == peer.userId }.displayName)
                assertEquals(2L, wireJson.decodeFromString<User>(requireNotNull(dao.userProfile(key, peer.userId)).json).version)
            }
            assertEquals("New group", actualChat.title)
            assertEquals(renamed.avatarId, actualChat.avatarId)
            assertTrue(actualChat.preferences.pinned)
            assertEquals(1L, repository.members(key, groupId).first().first { it.userId == peer.userId }.readSeq)
            assertEquals(2L, dao.message(key, message.messageId)?.version)
            assertEquals(2, memberRequests.count { it == groupId }, "The complete paginated membership snapshot is required")

            current.set(SyncPage(listOf(chatEvent(9, "chat.accessRevoked"), chatEvent(10, "group.invited", invitedId)), 10, false, 10))
            hint.value = 10
            withTimeout(10_000) { ack.first { it == 10L } }
            assertNull(repository.chat(key, groupId).first())
            assertTrue(repository.members(key, groupId).first().isEmpty())
            assertEquals("invited", repository.chat(key, invitedId).first()?.membership?.status)
        } finally {
            repository.stop()
            sessions.disconnect()
            owner.cancel()
            database.close()
            if (legacy) context.deleteDatabase(name)
        }
    }
}
