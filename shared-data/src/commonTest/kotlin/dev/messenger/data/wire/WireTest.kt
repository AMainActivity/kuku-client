package dev.messenger.data.wire

import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.uuid.Uuid

class WireTest {
    private val id = "00000000-0000-4000-8000-000000000001"

    @Test fun protocolAndEnvelopeTypesAreStrict() {
        assertFailsWith<ProtocolFailure> { decodeIncoming("""{"version":"1","type":"hello"}""") }
        assertFailsWith<ProtocolFailure> { decodeIncoming("""{"version":2,"type":"hello"}""") }
        assertFailsWith<ProtocolFailure> {
            decodeIncoming("""{"version":1,"type":"response","requestId":null,"ok":"true","data":{}}""")
        }
        assertFailsWith<ProtocolFailure> { decodeIncoming("not json") }
    }

    @Test fun differentServerIdentityOrVersionIsRejectedBeforeCredentials() {
        val info = ServerInfo(id, listOf(1), CONTRACT_VERSION, "2026-11-05T00:00:00Z",
            "admin_email_allowlist", "email_otp", emptyMap(), emptyMap())
        val hello = Incoming.Hello(id, id, CONTRACT_VERSION)
        verifyServer(info, hello, id)
        assertFailsWith<ProtocolFailure> { verifyServer(info, hello, Uuid.random().toString()) }
        assertFailsWith<ProtocolFailure> { verifyServer(info, hello.copy(contractVersion = "9.0.0")) }
    }

    @Test fun operationIsStableButAttemptsHaveDifferentRequestIds() {
        val operation = Uuid.random()
        val params = SendParams(id, id, PlainContent("Hi"))
        val first = wireJson.parseToJsonElement(encodeRequest(Commands.MessagesSend, params, Uuid.random(), operation)).jsonObject
        val second = wireJson.parseToJsonElement(encodeRequest(Commands.MessagesSend, params, Uuid.random(), operation)).jsonObject
        assertNotEquals(first["requestId"], second["requestId"])
        assertEquals(first["operationId"], second["operationId"])
        assertEquals(first["params"], second["params"])
        assertFailsWith<IllegalArgumentException> { encodeRequest(Commands.MessagesSend, params, Uuid.random()) }
    }

    @Test fun unicodeMentionsUseCodePointsAndSequenceDoesNotBecomeInt() {
        assertEquals(5, "👋 @ab".codePointCount())
        PlainContent("👋 @ab", listOf(Mention(id, 2, 3)))
        assertFailsWith<IllegalArgumentException> { PlainContent("👋 @ab", listOf(Mention(id, 3, 3))) }
        val params = SyncParams(4_294_967_296)
        assertEquals(4_294_967_296L, wireJson.encodeToJsonElement(params).jsonObject["cursor"]?.jsonPrimitive?.long)
        assertFailsWith<IllegalArgumentException> { SyncParams(MAX_SAFE_INTEGER + 1) }
    }

    @Test fun unknownDurableEventsPreserveContentAndCursor() {
        val page = wireJson.decodeFromString<SyncPage>("""{
            "items":[{"cursor":4294967296,"type":"future.event","data":{"opaque":[1,"x"]},"createdAt":"now","future":true}],
            "nextCursor":4294967296,"hasMore":false,"highWatermark":4294967296,"extension":true
        }""")
        assertEquals("future.event", page.items.single().type)
        assertEquals(4_294_967_296, page.nextCursor)
        assertEquals(JsonPrimitive("x"), page.items.single().data["opaque"]?.jsonArray?.get(1))
    }

    @Test fun unknownContentIsNotInterpretedAsPlainAndTombstoneHidesText() {
        val message = Message(id, id, id, id, 1, 1,
            buildJsonObject { put("mode", "future"); put("version", 2); put("text", "private") },
            null, false, "now", null, emptyList())
        assertNull(message.plainTextOrNull())
        assertEquals(JsonPrimitive("private"), message.content?.get("text"))
        val deleted = Message(id, id, id, id, 1, 2,
            buildJsonObject { put("mode", "plain"); put("version", 1); put("text", "deleted") },
            null, true, "now", "now", emptyList())
        assertNull(deleted.plainTextOrNull())
    }

    @Test fun diagnosticStringsDoNotExposeCredentialsCodesOrContent() {
        val token = "secret".repeat(8)
        val credentials = Credentials(id,id,id,id,token,token,"now")
        listOf(credentials, ResumeParams(token), RefreshParams(token),
            EmailVerifyParams(id,"12345678",id,"Device"), PlainContent("private content"),
            RemoteFailure("RATE_LIMITED", true, buildJsonObject { put("secret", token) })
        ).forEach {
            assertFalse(it.toString().contains(token))
            assertFalse(it.toString().contains("12345678"))
            assertFalse(it.toString().contains("private content"))
        }
    }
}
