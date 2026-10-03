package ru.bsv.candycmd.shared

import ru.bsv.candycmd.shared.protocol.*
import kotlin.test.*

class StatusCodecTest {
    private val key = "fixture-key-1234".encodeToByteArray()
    private val codec = StatusCodec()
    private val payload = """{"statusTD":{"StatoWiFi":"700","DoorState":"722","RecipeId":"NULL","Future":"☃"}}"""
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }.encodeToByteArray()
    private fun encrypted(text: String) = hex(text.encodeToByteArray().mapIndexed { i, b -> (b.toInt() xor key[i % 16].toInt()).toByte() }.toByteArray())
    private fun error(expected: ReadError, result: ReadResult<*>) = assertEquals(expected, assertIs<ReadResult.Failure>(result).error)

    @Test fun formatsAreSeparateFromSchema() {
        for ((body, format) in listOf(payload.encodeToByteArray() to ResponseFormat.JSON,
            hex(payload.encodeToByteArray()) to ResponseFormat.HEX_JSON, encrypted(payload) to ResponseFormat.XOR_JSON)) {
            val decoded = assertIs<ReadResult.Success<DecodedResponse>>(codec.decode(body, key)).value
            assertEquals(format, decoded.format)
            val status = assertIs<ReadResult.Success<DryerStatus>>(codec.validate(decoded)).value
            assertEquals("722", status["DoorState"])
            assertEquals("NULL", status["RecipeId"])
            assertEquals("☃", status["Future"])
            assertNull(status["RemTime"])
        }
        val decoded = assertIs<ReadResult.Success<DecodedResponse>>(codec.decode("[]".encodeToByteArray())).value
        error(ReadError.Compatibility.EXPECTED_STATUS_TD, codec.validate(decoded))
    }

    @Test fun rejectsDamageAndWrongKeys() {
        error(ReadError.Format.EMPTY, codec.read(" \n".encodeToByteArray()))
        for (text in listOf("abc", "xx", "aa gg")) error(ReadError.Format.INVALID_HEX, codec.read(text.encodeToByteArray()))
        error(ReadError.Format.INVALID_JSON, codec.read("{\"statusTD\":".encodeToByteArray()))
        error(ReadError.Format.DECRYPTION_FAILED, codec.read(encrypted(payload), ByteArray(16)))
        error(ReadError.Format.INVALID_KEY_LENGTH, codec.read(encrypted(payload), ByteArray(15)))
        error(ReadError.Format.DECRYPTION_FAILED, codec.read(encrypted(payload.dropLast(1)), key))
        error(ReadError.Format.INVALID_JSON, codec.read(byteArrayOf(0xc3.toByte(), 0x28)))
        error(ReadError.Format.TOO_LARGE, StatusCodec(maxBodyBytes = 8).read(ByteArray(9)))
        error(ReadError.Format.TOO_LARGE, StatusCodec(maxBodyBytes = 8).recover(ByteArray(9)))
    }

    @Test fun rejectsIncompatibleSchemaInEveryFormat() {
        for ((text, expected) in listOf("""{"response":"BAD REQUEST"}""" to ReadError.Compatibility.BAD_REQUEST,
            """{"statusWM":{}}""" to ReadError.Compatibility.EXPECTED_STATUS_TD,
            """{"statusTD":null}""" to ReadError.Compatibility.EXPECTED_STATUS_TD)) {
            for (body in listOf(text.encodeToByteArray(), hex(text.encodeToByteArray()), encrypted(text))) error(expected, codec.read(body, key))
        }
        for (value in listOf("null", "1", "true", "[]", "{}")) {
            error(ReadError.Compatibility.NON_STRING_FIELD, codec.read("{\"statusTD\":{\"DoorState\":$value}}".encodeToByteArray()))
        }
        val status = assertIs<ReadResult.Success<DryerStatus>>(codec.read("""{"statusTD":{"RemTime":"oops","DoorState":""}}""".encodeToByteArray())).value
        assertEquals("oops", status["RemTime"])
        assertEquals("", status["DoorState"])
        assertEquals(emptyMap(), assertIs<ReadResult.Success<DryerStatus>>(codec.read("""{"statusTD":{}}""".encodeToByteArray())).value.fields)
    }

    @Test fun recoversObservedCrLfLayoutWithSyntheticKey() {
        val formatted = "{\r\n\t\"statusTD\":\r\n{\"StatoWiFi\":\"700\",\"DoorState\":\"722\"}\r\n}"
        val recovered = assertIs<ReadResult.Success<RecoveredStatus>>(codec.recover(encrypted(formatted))).value
        assertContentEquals(key, recovered.keyBytes())
        assertEquals("722", recovered.status["DoorState"])
        val wrongType = formatted.replace("statusTD", "statusWM")
        assertIs<ReadResult.Failure>(codec.recover(encrypted(wrongType)))
    }

    @Test fun recoversKeyAndDoesNotExposeMutableSecret() {
        for (first in listOf("StatoWiFi", "DoorState", "Pr", "RemTime")) {
            val text = "{\"statusTD\":{\"$first\":\"701\",\"RecipeId\":\"NULL\"}}"
            val recovered = codec.recover(encrypted(text))
            val result = assertIs<ReadResult.Success<RecoveredStatus>>(recovered, "$first: $recovered").value
            assertContentEquals(key, result.keyBytes())
            assertEquals("701", result.status[first])
            result.keyBytes().fill(0)
            assertContentEquals(key, result.keyBytes())
            assertFalse(result.toString().contains(key.decodeToString()))
        }
    }

    @Test fun recoveryBudgetsAndUnsupportedBodiesFailExplicitly() {
        error(ReadError.Recovery.CANDIDATE_LIMIT, StatusCodec(maxCandidates = 1).recover(encrypted(payload)))
        var clock = 0L
        error(ReadError.Recovery.TIME_LIMIT, StatusCodec(recoveryTimeoutMillis = 1, nowMillis = { clock++ }).recover(encrypted(payload)))
        var calls = 0
        error(ReadError.Recovery.TIME_LIMIT, StatusCodec(recoveryTimeoutMillis = 1, nowMillis = { if (calls++ < 2) 0L else 1L }).recover(encrypted(payload)))
        error(ReadError.Recovery.NO_MATCH, codec.recover("00".encodeToByteArray()))
        error(ReadError.Recovery.NO_MATCH, codec.recover(encrypted("""{"anotherRoot":{"field":"value"}}""")))
        error(ReadError.Recovery.NO_MATCH, codec.recover(encrypted(payload.dropLast(1))))
    }
}
