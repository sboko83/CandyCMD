package ru.bsv.candycmd.shared

import kotlinx.serialization.json.*
import ru.bsv.candycmd.shared.protocol.*
import kotlin.test.*

class StatusFixturesTest {
    @Test
    fun committedSyntheticFixtures() {
        val root = Json.parseToJsonElement(checkNotNull(javaClass.getResource("/read-status.json")).readText()).jsonObject
        assertEquals(true, root.getValue("synthetic").jsonPrimitive.boolean)
        val key = root.getValue("key_ascii").jsonPrimitive.content.encodeToByteArray()
        assertEquals(16, key.size)
        val codec = StatusCodec()
        for (entry in root.getValue("cases").jsonArray) {
            val case = entry.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val result = codec.read(case.getValue("body").jsonPrimitive.content.encodeToByteArray(), key)
            if (case.getValue("outcome").jsonPrimitive.content == "valid_status") {
                val expected = case.getValue("expected").jsonObject.getValue("statusTD").jsonObject
                    .mapValues { it.value.jsonPrimitive.content }
                assertEquals(expected, assertIs<ReadResult.Success<DryerStatus>>(result, name).value.fields, name)
            } else {
                assertIs<ReadResult.Failure>(result, name)
            }
            if (name == "unknown-codes") {
                val recovered = assertIs<ReadResult.Success<RecoveredStatus>>(
                    codec.recover(case.getValue("body").jsonPrimitive.content.encodeToByteArray())
                ).value
                assertContentEquals(key, recovered.keyBytes())
                assertEquals(assertIs<ReadResult.Success<DryerStatus>>(result).value, recovered.status)
            }
        }
    }
}
