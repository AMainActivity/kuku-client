package dev.messenger.data

import androidx.room.Room
import dev.messenger.data.wire.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*
import kotlin.uuid.Uuid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ReactiveEventsTest {
    private val userId = Uuid.random().toString()
    private val account = Uuid.random().toString() + ":" + userId
    private val chatId = Uuid.random().toString()
    private val groupId = Uuid.random().toString()
    private val peer = User(Uuid.random().toString(), "Old", "human", null, "Old bio", null, 1, false)
    private fun db() = buildDatabase(Room.inMemoryDatabaseBuilder<MessengerDatabase>(RuntimeEnvironment.getApplication()))
    private fun chat(peer: User? = this.peer, id: String = chatId) = Chat(id,
        if (peer == null) "group" else "direct", if (peer == null) "Group" else "", "", null,
        1, Membership("member", "active", 1, 1), null, 0, peer, Preferences(false, 0, false, 0))
    private fun member(id: String = peer.userId) = MemberInfo(id, "Old", "human", null, "member", "active", 30, 12, 10)
    private fun row(chatId: String, member: MemberInfo) = MemberCacheEntity(account, chatId, member.userId, wireJson.encodeToString(member))
    private fun event(cursor: Long, user: User) = SyncEvent(cursor, "user.updated",
        wireJson.encodeToJsonElement(user).jsonObject, "")
    private suspend fun Channel<Boolean>.awaitTrue() = withTimeout(10_000) { while (!receive()) Unit }

    @Test fun openObserversReceiveProfileAndMembershipChangesWithoutReopening() = runBlocking<Unit> {
        val database = db()
        val dao = database.conversations()
        val observer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            dao.installCatalog(account, 0, "generation")
            dao.cacheChat(account, chat())
            dao.cacheChat(account, chat(null, groupId))
            dao.cacheMembers(listOf(row(chatId, member()), row(groupId, member())))
            val otherAccount = Uuid.random().toString() + ":" + Uuid.random()
            dao.cacheChat(otherAccount, chat())
            val own = Me(userId, "Self", "human", null, "", null, 1, false, "self@example.com", "user", false)
            dao.cacheMe(account, own)
            val renamed = peer.copy(displayName = "New", handle = "new_name", description = "New bio",
                avatarId = Uuid.random().toString(), version = 2)
            val self = User(userId, "New self", "human", null, "", null, 2, false)
            val newMember = member(Uuid.random().toString()).copy(displayName = "Joined", version = 1)
            val chats = Channel<Boolean>(Channel.UNLIMITED)
            val members = Channel<Boolean>(Channel.UNLIMITED)
            val profiles = Channel<Boolean>(Channel.UNLIMITED)
            observer.launch {
                dao.observeChats(account).collect { rows ->
                    chats.send(rows.any { it.chatId == chatId && wireJson.decodeFromString<Chat>(it.json).peer == renamed })
                }
            }
            observer.launch {
                dao.observeMembers(account, groupId).collect { rows ->
                    val values = rows.map { wireJson.decodeFromString<MemberInfo>(it.json) }
                    members.send(values.size == 2 && values.any { it.userId == peer.userId && it.displayName == "New" &&
                        it.role == "admin" && it.readSeq == 10L } && values.any { it.userId == newMember.userId })
                }
            }
            observer.launch {
                database.profiles().observe(account).collect { row ->
                    val me = row?.let { wireJson.decodeFromString<Me>(it.json) }
                    profiles.send(me?.displayName == "New self" && me.role == "admin" && me.email == own.email)
                }
            }
            // Ensure each observer was already subscribed before the transaction.
            assertFalse(withTimeout(10_000) { chats.receive() })
            assertFalse(withTimeout(10_000) { members.receive() })
            assertFalse(withTimeout(10_000) { profiles.receive() })
            val page = SyncPage(listOf(event(1, renamed), event(2, self)), 2, false, 2)
            dao.applyPage(account, 0, page, emptyList(), emptyList(), emptySet(),
                mapOf(groupId to listOf(member().copy(role = "admin", version = 31, readSeq = 2), newMember)),
                own.copy(displayName = self.displayName, version = 2, role = "admin"))
            chats.awaitTrue(); members.awaitTrue(); profiles.awaitTrue()
            assertEquals(2L, dao.syncState(account)?.cursor)
            assertEquals("Group", wireJson.decodeFromString<Chat>(dao.cachedChats(account).first { it.chatId == groupId }.json).title)
            assertEquals("Old", wireJson.decodeFromString<Chat>(dao.cachedChats(otherAccount).single().json).peer?.displayName)

            // A membership version (30) is unrelated to the public profile version (2).
            dao.cacheMembers(listOf(row(groupId, member().copy(version = 32))))
            dao.cacheChat(account, chat())
            dao.cacheUser(account, peer)
            assertEquals("New", wireJson.decodeFromString<MemberInfo>(requireNotNull(dao.member(account, groupId, peer.userId)).json).displayName)
            assertEquals(renamed, wireJson.decodeFromString<Chat>(dao.cachedChats(account).first { it.chatId == chatId }.json).peer)
            dao.cacheMe(account, own)
            assertEquals("New self", wireJson.decodeFromString<Me>(requireNotNull(dao.ownProfile(account)).json).displayName)
        } finally { observer.cancel(); database.close() }
    }

    @Test fun fullMembershipSnapshotRemovesDepartedMembersAndRevocationClearsObservers() = runBlocking<Unit> {
        val database = db()
        try {
            val dao = database.conversations()
            dao.installCatalog(account, 0, "generation")
            dao.cacheChat(account, chat(null, groupId))
            val removed = member()
            val retained = member(Uuid.random().toString())
            dao.replaceMembers(account, groupId, listOf(removed, retained))
            val requestedRevision = dao.memberRevision(account, groupId)
            val change = SyncEvent(1, "group.membershipChanged", buildJsonObject { put("chatId", groupId) }, "")
            dao.applyPage(account, 0, SyncPage(listOf(change), 1, false, 1), emptyList(), emptyList(), emptySet(),
                mapOf(groupId to listOf(retained.copy(role = "admin", version = 31))))
            assertNull(dao.member(account, groupId, removed.userId))
            assertEquals("admin", wireJson.decodeFromString<MemberInfo>(dao.members(account, groupId).single().json).role)
            dao.cacheMemberResponse(account, groupId, listOf(removed, retained), requestedRevision, complete = true)
            assertNull(dao.member(account, groupId, removed.userId), "An older in-flight response resurrected a removed member")
            val revoked = SyncEvent(2, "chat.accessRevoked", buildJsonObject { put("chatId", groupId) }, "")
            dao.applyPage(account, 1, SyncPage(listOf(revoked), 2, false, 2), emptyList(), emptyList(), setOf(groupId))
            assertTrue(dao.observeMembers(account, groupId).first().isEmpty())
            assertNull(dao.observeChat(account, groupId).first())
        } finally { database.close() }
    }

    @Test fun invalidPageRollsBackProfilesMembershipAndCursorTogether() = runBlocking<Unit> {
        val database = db()
        try {
            val dao = database.conversations()
            dao.installCatalog(account, 0, "generation")
            dao.cacheChat(account, chat())
            dao.replaceMembers(account, chatId, listOf(member()))
            val invalid = SyncEvent(2, "receipt.updated", buildJsonObject {
                put("chatId", chatId); put("userId", peer.userId); put("kind", "read"); put("throughSeq", -1)
            }, "")
            assertFailsWith<IllegalArgumentException> {
                dao.applyPage(account, 0, SyncPage(listOf(event(1, peer.copy(displayName = "New", version = 2)), invalid), 2, false, 2),
                    emptyList(), emptyList(), emptySet(), mapOf(chatId to listOf(member().copy(role = "admin", version = 31))))
            }
            assertEquals(0L, dao.syncState(account)?.cursor)
            assertEquals(peer, wireJson.decodeFromString<User>(requireNotNull(dao.userProfile(account, peer.userId)).json))
            assertEquals(member(), wireJson.decodeFromString<MemberInfo>(dao.members(account, chatId).single().json))
        } finally { database.close() }
    }

    @Test fun unrelatedEventsAndMessagesDoNotDiscardFirstMemberLoad() = runBlocking<Unit> {
        val database = db()
        try {
            val dao = database.conversations()
            dao.installCatalog(account, 0, "generation")
            val revision = dao.memberRevision(account, groupId)
            val message = Message(Uuid.random().toString(), Uuid.random().toString(), groupId, peer.userId,
                1, 1, wireJson.encodeToJsonElement(PlainContent("Message")).jsonObject, null, false, "", null, emptyList())
            val events = listOf(
                SyncEvent(1, "chat.preferencesChanged", buildJsonObject { put("chatId", chatId) }, ""),
                SyncEvent(2, "message.created", wireJson.encodeToJsonElement(message).jsonObject, ""),
            )
            dao.applyPage(account, 0, SyncPage(events, 2, false, 2), emptyList(), listOf(message), emptySet())
            dao.cacheMemberResponse(account, groupId, listOf(member()), revision, complete = true)
            assertEquals(member(), wireJson.decodeFromString<MemberInfo>(dao.members(account, groupId).single().json))
        } finally { database.close() }
    }

    @Test fun revocationRejectsInFlightMemberLoadEvenWhenCacheWasEmpty() = runBlocking<Unit> {
        val database = db()
        try {
            val dao = database.conversations()
            val revision = dao.memberRevision(account, groupId)
            dao.revoke(account, groupId)
            dao.cacheMemberResponse(account, groupId, listOf(member()), revision, complete = true)
            assertTrue(dao.members(account, groupId).isEmpty())
        } finally { database.close() }
    }

    @Test fun newerFullSnapshotSupersedesInFlightPagesWithoutLosingReceipts() = runBlocking<Unit> {
        val database = db()
        try {
            val dao = database.conversations()
            val revision = dao.memberRevision(account, groupId)
            val latest = member().copy(role = "admin", version = 31, readSeq = 12)
            dao.replaceMembers(account, groupId, listOf(latest))
            dao.cacheMemberResponse(account, groupId, listOf(member()), revision, complete = false)
            assertEquals(latest, wireJson.decodeFromString<MemberInfo>(dao.members(account, groupId).single().json))
        } finally { database.close() }
    }

    @Test fun emptyMembershipSnapshotClearsAllRows() = runBlocking<Unit> {
        val database = db()
        try {
            val dao = database.conversations()
            dao.replaceMembers(account, groupId, listOf(member()))
            dao.replaceMembers(account, groupId, emptyList())
            assertTrue(dao.members(account, groupId).isEmpty())
        } finally { database.close() }
    }
}
