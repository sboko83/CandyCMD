package ru.bsv.candycmd.shared.protocol

import kotlinx.serialization.json.*
import kotlin.time.TimeSource

/** Errors carry no response bodies, addresses or keys. */
sealed interface ReadError {
    enum class Transport : ReadError { TIMEOUT, UNAVAILABLE, PERMISSION_DENIED, HTTP_STATUS }
    enum class Format : ReadError { EMPTY, TOO_LARGE, INVALID_HEX, INVALID_JSON, INVALID_KEY_LENGTH, DECRYPTION_FAILED }
    enum class Compatibility : ReadError { BAD_REQUEST, EXPECTED_STATUS_TD, NON_STRING_FIELD }
    enum class Recovery : ReadError { CANDIDATE_LIMIT, TIME_LIMIT, NO_MATCH, AMBIGUOUS }
}

sealed interface ReadResult<out T> {
    data class Success<T>(val value: T) : ReadResult<T>
    data class Failure(val error: ReadError) : ReadResult<Nothing>
}

enum class ResponseFormat { JSON, HEX_JSON, XOR_JSON }
data class DecodedResponse(val json: JsonElement, val format: ResponseFormat)

/** Raw observations only: no inferred readiness, defaults or numeric coercion. */
data class DryerStatus(val fields: Map<String, String>) {
    operator fun get(field: String): String? = fields[field]
}

class RecoveredStatus internal constructor(val status: DryerStatus, key: ByteArray) {
    private val bytes = key.copyOf()
    fun keyBytes(): ByteArray = bytes.copyOf()
    override fun toString(): String = "RecoveredStatus(key=<redacted>)"
}

/** Limits apply before parsing; recovery uses a monotonic clock and a finite prefix dictionary. */
class StatusCodec(
    private val maxBodyBytes: Int = 64 * 1024,
    private val maxCandidates: Int = 64,
    private val recoveryTimeoutMillis: Long = 250,
    private val nowMillis: () -> Long = monotonicClock(),
) {
    init {
        require(maxBodyBytes > 0 && maxCandidates > 0 && recoveryTimeoutMillis > 0)
    }

    fun decode(body: ByteArray, key: ByteArray? = null): ReadResult<DecodedResponse> {
        val prepared = prepare(body)
        if (prepared is ReadResult.Failure) return prepared
        val text = (prepared as ReadResult.Success).value
        parse(text)?.let { return ReadResult.Success(DecodedResponse(it, ResponseFormat.JSON)) }
        if (text.first() == '{' || text.first() == '[') return ReadResult.Failure(ReadError.Format.INVALID_JSON)
        val encrypted = hex(text) ?: return ReadResult.Failure(ReadError.Format.INVALID_HEX)
        parseBytes(encrypted)?.let { return ReadResult.Success(DecodedResponse(it, ResponseFormat.HEX_JSON)) }
        if (key == null) return ReadResult.Failure(ReadError.Format.DECRYPTION_FAILED)
        if (key.size != 16) return ReadResult.Failure(ReadError.Format.INVALID_KEY_LENGTH)
        val json = parseBytes(xor(encrypted, key))
            ?: return ReadResult.Failure(ReadError.Format.DECRYPTION_FAILED)
        return ReadResult.Success(DecodedResponse(json, ResponseFormat.XOR_JSON))
    }

    fun validate(response: DecodedResponse): ReadResult<DryerStatus> {
        val root = response.json as? JsonObject
            ?: return ReadResult.Failure(ReadError.Compatibility.EXPECTED_STATUS_TD)
        if ((root["response"] as? JsonPrimitive)?.content == "BAD REQUEST") {
            return ReadResult.Failure(ReadError.Compatibility.BAD_REQUEST)
        }
        val status = root["statusTD"] as? JsonObject
            ?: return ReadResult.Failure(ReadError.Compatibility.EXPECTED_STATUS_TD)
        val fields = mutableMapOf<String, String>()
        for ((name, value) in status) {
            if (value !is JsonPrimitive || !value.isString) {
                return ReadResult.Failure(ReadError.Compatibility.NON_STRING_FIELD)
            }
            fields[name] = value.content
        }
        return ReadResult.Success(DryerStatus(fields.toMap()))
    }

    fun read(body: ByteArray, key: ByteArray? = null): ReadResult<DryerStatus> =
        when (val result = decode(body, key)) {
            is ReadResult.Failure -> result
            is ReadResult.Success -> validate(result.value)
        }

    /** Supports compact and observed CRLF statusTD layouts; never brute-forces arbitrary keys. */
    fun recover(body: ByteArray): ReadResult<RecoveredStatus> {
        val started = nowMillis()
        val prepared = prepare(body)
        if (prepared is ReadResult.Failure) return prepared
        val encrypted = hex((prepared as ReadResult.Success).value)
            ?: return ReadResult.Failure(ReadError.Format.INVALID_HEX)
        if (encrypted.size < 16) return ReadResult.Failure(ReadError.Recovery.NO_MATCH)
        val prefixes = ROOT_PREFIXES.flatMap { root ->
            FIRST_FIELDS.map { (root + "\"" + it + "\":").take(16) }
        }.distinct()
        var found: RecoveredStatus? = null
        var tried = 0
        for (prefix in prefixes) {
            if (nowMillis() - started >= recoveryTimeoutMillis) return ReadResult.Failure(ReadError.Recovery.TIME_LIMIT)
            if (tried >= maxCandidates) return ReadResult.Failure(ReadError.Recovery.CANDIDATE_LIMIT)
            tried++
            val key = xor(encrypted.copyOf(16), prefix.encodeToByteArray())
            val json = parseBytes(xor(encrypted, key))
            val status = json?.let { validate(DecodedResponse(it, ResponseFormat.XOR_JSON)) }
            if (nowMillis() - started >= recoveryTimeoutMillis) return ReadResult.Failure(ReadError.Recovery.TIME_LIMIT)
            if (status is ReadResult.Success && status.value.fields.keys.firstOrNull() in FIRST_FIELDS) {
                if (found != null) return ReadResult.Failure(ReadError.Recovery.AMBIGUOUS)
                found = RecoveredStatus(status.value, key)
            }
        }
        return found?.let { ReadResult.Success(it) } ?: ReadResult.Failure(ReadError.Recovery.NO_MATCH)
    }

    private fun prepare(body: ByteArray): ReadResult<String> {
        if (body.size > maxBodyBytes) return ReadResult.Failure(ReadError.Format.TOO_LARGE)
        val text = try { body.decodeToString(throwOnInvalidSequence = true).trim() } catch (_: CharacterCodingException) {
            return ReadResult.Failure(ReadError.Format.INVALID_JSON)
        }
        return if (text.isEmpty()) ReadResult.Failure(ReadError.Format.EMPTY) else ReadResult.Success(text)
    }

    private fun hex(text: String): ByteArray? {
        if (text.length % 2 != 0) return null
        val bytes = ByteArray(text.length / 2)
        for (i in bytes.indices) {
            val high = text[i * 2].digitToIntOrNull(16) ?: return null
            val low = text[i * 2 + 1].digitToIntOrNull(16) ?: return null
            bytes[i] = (high * 16 + low).toByte()
        }
        return bytes
    }

    private fun xor(bytes: ByteArray, key: ByteArray) =
        ByteArray(bytes.size) { (bytes[it].toInt() xor key[it % key.size].toInt()).toByte() }

    private fun parseBytes(bytes: ByteArray): JsonElement? = try {
        parse(bytes.decodeToString(throwOnInvalidSequence = true))
    } catch (_: CharacterCodingException) { null }

    private fun parse(text: String): JsonElement? {
        // The protocol uses JSON containers. Bare hex must never be accepted as a JSON literal.
        if (text.trimStart().firstOrNull() !in listOf('{', '[')) return null
        return try {
            Json.parseToJsonElement(text)
        } catch (_: IllegalArgumentException) { null }
    }

    companion object {
        private val ROOT_PREFIXES = listOf("{\"statusTD\":{", "{\r\n\t\"statusTD\":\r\n{")
        private val FIRST_FIELDS = ("StatoWiFi StatoTD CodiceErrore Pr PrPh RemTime DryLev Time Rapido " +
            "Opt1 Opt2 Opt3 Opt4 Opt5 Opt6 Opt7 Opt8 Refresh CleanFilter WaterTankFull " +
            "DryingManagerLevel DelVal DoorState RecipeId CheckUpState").split(' ')
        private fun monotonicClock(): () -> Long {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow().inWholeMilliseconds }
        }
    }
}
