package dev.messenger.data.wire

import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import kotlin.uuid.Uuid

/** Reads the published contract in place: no duplicate schema or fixture files in client. */
class PublishedContractTest {
    private val directory = File(requireNotNull(System.getProperty("messenger.contractDirectory")))
    private val commands = wireJson.parseToJsonElement(File(directory, "commands.json").readText(Charsets.UTF_8)).jsonObject
    private val envelope = wireJson.parseToJsonElement(File(directory, "request.schema.json").readText(Charsets.UTF_8)).jsonObject
    private val id = Uuid.parse("00000000-0000-4000-8000-000000000001")

    @Test fun allPublishedRequestFixturesRoundTripThroughTypedDtos() {
        val fixtures = wireJson.parseToJsonElement(File(directory, "fixtures.json").readText(Charsets.UTF_8)).jsonArray
        var supportedFixtures = 0
        for (fixture in fixtures) {
            val request = fixture.jsonObject
            val name = request.getValue("command").jsonPrimitive.content
            // Additive server commands need not be exposed by this client yet.
            // Validate all published fixtures; round-trip every implemented command.
            validate(request, envelope, envelope)
            val publishedSchema = commands.getValue(name).jsonObject.getValue("params").jsonObject
            validate(request.getValue("params"), publishedSchema, publishedSchema)
            val command = Commands.supported.singleOrNull { it.name == name } ?: continue
            supportedFixtures++
            val params = request.getValue("params")
            val result = roundTripRequest(command, params,
                request["operationId"]?.jsonPrimitive?.content?.let(Uuid::parse))
            assertEquals(request, result, name)
            validate(result, envelope, envelope)
            val schema = commands.getValue(name).jsonObject.getValue("params").jsonObject
            validate(result.getValue("params"), schema, schema)
        }
        assertEquals(4, supportedFixtures, "Review new fixtures for client-supported commands")
        val send = fixtures.single { it.jsonObject["command"]?.jsonPrimitive?.content=="messages.send" }.jsonObject["params"]?.jsonObject?.get("content")?.jsonObject
        assertEquals("Привет 👋", send?.get("text")?.jsonPrimitive?.content)
    }

    @Test fun supportedCommandsMatchMutationFlagsAndGeneratedRequestsValidateAgainstServerSchema() {
        for (command in Commands.supported) {
            val spec = commands.getValue(command.name).jsonObject
            assertEquals(spec.getValue("mutation").jsonPrimitive.boolean, command.mutation, command.name)
            val schema = spec.getValue("params").jsonObject
            val request = roundTripRequest(command, sample(schema, schema), if (command.mutation) id else null)
            validate(request, envelope, envelope)
            validate(request.getValue("params"), schema, schema)
        }
    }

    @Test fun allSupportedResultDtosDecodeSchemaShapedResponses() {
        for (command in Commands.supported) {
            val schema = commands.getValue(command.name).jsonObject.getValue("result").jsonObject
            val result = sample(schema, schema)
            validate(result, schema, schema)
            wireJson.decodeFromJsonElement(command.result, result)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun roundTripRequest(command: Command<*, *>, params: JsonElement, operationId: Uuid?): JsonObject {
        val typed = command as Command<Any, Any>
        val value = wireJson.decodeFromJsonElement(typed.params, params)
        return wireJson.parseToJsonElement(encodeRequest(typed, value, id, operationId)).jsonObject
    }

    private fun resolve(schema: JsonObject, root: JsonObject): JsonObject {
        val reference = schema["\$ref"]?.jsonPrimitive?.content ?: return schema
        return root.getValue("\$defs").jsonObject.getValue(reference.substringAfterLast('/')).jsonObject
    }

    // A small test-only validator for the schema constructs actually used by this contract.
    private fun validate(value: JsonElement, source: JsonObject, root: JsonObject) {
        val schema = resolve(source, root)
        schema["anyOf"]?.jsonArray?.let { alternatives ->
            assertTrue(alternatives.any { candidate ->
                try { validate(value, candidate.jsonObject, root); true } catch (_: AssertionError) { false }
            }, "Value must match a published union alternative")
            return
        }
        schema["const"]?.let { assertEquals(it, value) }
        schema["enum"]?.let { assertTrue(value in it.jsonArray) }
        when (schema["type"]?.jsonPrimitive?.content) {
            "null" -> assertEquals(JsonNull, value)
            "string" -> {
                val primitive = assertIs<JsonPrimitive>(value)
                assertTrue(primitive.isString)
                val size = primitive.content.codePointCount()
                schema["minLength"]?.let { assertTrue(size >= it.jsonPrimitive.int) }
                schema["maxLength"]?.let { assertTrue(size <= it.jsonPrimitive.int) }
                schema["pattern"]?.let { assertTrue(Regex(it.jsonPrimitive.content).containsMatchIn(primitive.content)) }
            }
            "integer" -> {
                val primitive = assertIs<JsonPrimitive>(value)
                assertFalse(primitive.isString)
                val number = assertNotNull(primitive.longOrNull)
                schema["minimum"]?.let { assertTrue(number >= it.jsonPrimitive.long) }
                schema["maximum"]?.let { assertTrue(number <= it.jsonPrimitive.long) }
            }
            "boolean" -> {
                val primitive = assertIs<JsonPrimitive>(value)
                assertFalse(primitive.isString)
                assertNotNull(primitive.booleanOrNull)
            }
            "array" -> {
                val array = assertIs<JsonArray>(value)
                schema["minItems"]?.let { assertTrue(array.size >= it.jsonPrimitive.int) }
                schema["maxItems"]?.let { assertTrue(array.size <= it.jsonPrimitive.int) }
                array.forEach { validate(it, schema.getValue("items").jsonObject, root) }
            }
            "object" -> {
                val obj = assertIs<JsonObject>(value)
                val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
                schema["required"]?.jsonArray?.forEach { assertTrue(it.jsonPrimitive.content in obj) }
                obj.forEach { (key, item) ->
                    val property = properties[key]
                    if (property != null) validate(item, property.jsonObject, root)
                    else when (val additional = schema["additionalProperties"]) {
                        JsonPrimitive(false) -> fail("Unknown field: $key")
                        is JsonObject -> validate(item, additional, root)
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun sample(source: JsonObject, root: JsonObject, name: String = ""): JsonElement {
        val schema = resolve(source, root)
        schema["const"]?.let { return it }
        schema["enum"]?.let { return it.jsonArray.first() }
        schema["anyOf"]?.jsonArray?.let { choices ->
            val selected = choices.firstOrNull { it.jsonObject["type"] == JsonPrimitive("null") } ?: choices.first()
            return sample(selected.jsonObject, root, name)
        }
        return when (schema["type"]?.jsonPrimitive?.content) {
            "object" -> buildJsonObject {
                val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
                val properties = schema["properties"]?.jsonObject ?: JsonObject(emptyMap())
                required.forEach { put(it, sample(properties.getValue(it).jsonObject, root, it)) }
            }
            "array" -> JsonArray(List(schema["minItems"]?.jsonPrimitive?.int ?: 0) { sample(schema.getValue("items").jsonObject, root, name) })
            "boolean" -> JsonPrimitive(false)
            "integer" -> schema["minimum"] ?: JsonPrimitive(1)
            "null" -> JsonNull
            else -> {
                val pattern = schema["pattern"]?.jsonPrimitive?.content.orEmpty()
                JsonPrimitive(when {
                    pattern.contains("[0-9a-f]{8}") -> id.toString()
                    pattern.contains("{64}") -> "a".repeat(64)
                    pattern.contains("{6}") -> "123456"
                    pattern.contains("{8}") -> "12345678"
                    name == "accessToken" || name == "refreshToken" -> "synthetic-token".repeat(3)
                    name == "email" -> "user@example.com"
                    else -> "fixture"
                })
            }
        }
    }
}
