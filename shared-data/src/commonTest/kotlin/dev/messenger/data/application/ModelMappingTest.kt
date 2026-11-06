package dev.messenger.data.application

import dev.messenger.data.wire.*
import kotlinx.serialization.json.*
import kotlin.test.*

class ModelMappingTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    @Test fun messageBoundaryPreservesReplyReceiptsAndAttachments() {
        val file = Attachment(id, "image.jpg", "image/jpeg", 128, "a".repeat(64), "ready")
        val source = Message(id, id, id, id, 42, 3,
            buildJsonObject { put("mode", "plain"); put("version", 1); put("text", "private message") },
            id, false, "2026-11-06T12:00:00Z", null, listOf(file))
        val model = source.toModel()
        assertEquals(source.messageId, model.messageId)
        assertEquals(42L, model.seq)
        assertEquals(3L, model.version)
        assertEquals(id, model.replyTo)
        assertEquals("private message", model.plainTextOrNull())
        assertEquals(file, model.attachments.single().toWire())
        assertFalse(model.toString().contains("private message"))
        val member = MemberInfo(id, "Name", "human", null, "member", "active", 1, 42, 40).toModel()
        assertEquals(42L, member.deliveredSeq)
        assertEquals(40L, member.readSeq)
    }
    @Test fun deletedMessageDoesNotExposeText() {
        val source = Message(id, id, id, id, 42, 4,
            buildJsonObject { put("mode", "plain"); put("version", 1); put("text", "deleted private text") },
            null, true, "2026-11-06T12:00:00Z", null, emptyList())
        assertNull(source.toModel().plainTextOrNull())
    }
    @Test fun historyBoundaryKeepsBothWindowCursors() {
        val model = History(emptyList(), 20, 70, true, false).toModel()
        assertEquals(20L, model.beforeSeq)
        assertEquals(70L, model.afterSeq)
        assertTrue(model.hasMoreBefore)
        assertFalse(model.hasMoreAfter)
    }
}
