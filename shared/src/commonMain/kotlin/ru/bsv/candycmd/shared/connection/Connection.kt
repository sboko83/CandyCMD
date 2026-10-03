package ru.bsv.candycmd.shared.connection

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.bsv.candycmd.shared.protocol.*

/** Only canonical RFC1918 IPv4 literals are accepted; DNS and URLs are never accepted. */
class LocalAddress private constructor(val value: String, val octets: List<Int>) {
    override fun toString() = "LocalAddress(<redacted>)"

    companion object {
        fun parse(value: String): LocalAddress? {
            val parts = value.split('.')
            if (parts.size != 4) return null
            val bytes = parts.map { part ->
                if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
                val byte = part.toIntOrNull() ?: return null
                if (byte !in 0..255 || byte.toString() != part) return null
                byte
            }
            if (!(bytes[0] == 10 || bytes[0] == 172 && bytes[1] in 16..31 ||
                    bytes[0] == 192 && bytes[1] == 168)) return null
            return LocalAddress(value, bytes)
        }
    }
}

/** Matches an on-link subnet, excluding its network, broadcast and the phone itself. */
fun LocalAddress.isPeerOf(phone: LocalAddress, prefixLength: Int): Boolean {
    if (prefixLength !in 1..30) return false
    fun LocalAddress.number() = octets.fold(0L) { value, byte -> (value shl 8) or byte.toLong() }
    val mask = (0xffff_ffffL shl (32 - prefixLength)) and 0xffff_ffffL
    val network = phone.number() and mask
    val host = number()
    return (host and mask) == network && host != network &&
        host != (network or (mask xor 0xffff_ffffL)) && host != phone.number()
}

class Candidate(val address: LocalAddress, val mac: String?) {
    override fun toString() = "Candidate(<redacted>)"
}

/** Bound active discovery to at most 1023 peers around the phone, inside its actual subnet. */
fun discoveryPeers(phone: LocalAddress, prefixLength: Int): List<LocalAddress> {
    if (prefixLength !in 1..30) return emptyList()
    val prefix = prefixLength.coerceAtLeast(22)
    val number = phone.octets.fold(0L) { value, byte -> (value shl 8) or byte.toLong() }
    val mask = (0xffff_ffffL shl (32 - prefix)) and 0xffff_ffffL
    val first = number and mask
    val last = first or (mask xor 0xffff_ffffL)
    return (first..last).mapNotNull { value ->
        LocalAddress.parse(listOf(24, 16, 8, 0).joinToString(".") { ((value shr it) and 255).toString() })
            ?.takeIf { it.isPeerOf(phone, prefixLength) }
    }.sortedBy { if (it.octets.take(3) == phone.octets.take(3)) 0 else 1 }
}

object Heartbeat {
    /** Observed 110-byte format only. Advertised address must equal the UDP sender. */
    fun parse(packet: ByteArray, sender: String): Candidate? {
        if (packet.size != 110) return null
        val mac = packet.copyOfRange(61, 73).decodeToString()
        if (mac.length != 12 || mac.any { it !in "0123456789abcdefABCDEF" } ||
            mac.all { it == '0' } || mac.all { it == 'f' || it == 'F' }) return null
        if ((mac.substring(0, 2).toInt(16) and 1) != 0) return null
        val end = (75 until packet.size).firstOrNull { packet[it] == 0.toByte() } ?: return null
        val address = LocalAddress.parse(packet.copyOfRange(75, end).decodeToString()) ?: return null
        if (address.value != sender) return null
        return Candidate(address, mac.uppercase())
    }
}

enum class ConnectionProblem {
    INVALID_ADDRESS, OFF_LINK, PERMISSION_DENIED, NO_WIFI, WIFI_SELECTION_REQUIRED, NETWORK_LOST,
    TIMEOUT, UNAVAILABLE, HTTP_STATUS, RESPONSE_TOO_LARGE, UNEXPECTED_DEVICE, INVALID_RESPONSE,
    KEY_RECOVERY_FAILED, DISCOVERY_UNAVAILABLE, NO_CANDIDATES, STORAGE_UNAVAILABLE, NO_PROFILE,
}

class ConnectionException(val problem: ConnectionProblem) : Exception(problem.name)

class DeviceProfile(val address: LocalAddress, val mac: String?, key: ByteArray?) {
    private val bytes = key?.copyOf()
    fun keyBytes(): ByteArray? = bytes?.copyOf()
    override fun toString() = "DeviceProfile(<redacted>)"
}

interface ProfileStore {
    suspend fun load(): DeviceProfile?
    suspend fun save(profile: DeviceProfile)
    suspend fun delete()
}

interface DeviceNetwork {
    suspend fun read(address: LocalAddress): ByteArray
    suspend fun discover(): List<Candidate>
}

class ConnectedDevice(val profile: DeviceProfile, val status: DryerStatus) {
    override fun toString() = "ConnectedDevice(<redacted>)"
}

/** All discovery, reads, profile changes and deletion share one serialized channel. */
class DeviceConnection(
    private val network: DeviceNetwork,
    private val store: ProfileStore,
    private val codec: StatusCodec = StatusCodec(),
) {
    private val mutex = Mutex()

    suspend fun discover(): List<Candidate> = mutex.withLock {
        network.discover().ifEmpty { throw ConnectionException(ConnectionProblem.NO_CANDIDATES) }
    }

    suspend fun connect(ip: String, candidate: Candidate? = null): ConnectedDevice = mutex.withLock {
        val address = LocalAddress.parse(ip) ?: throw ConnectionException(ConnectionProblem.INVALID_ADDRESS)
        require(candidate == null || candidate.address.value == address.value)
        val previous = store.load()
        val sameDevice = previous != null && (candidate?.mac?.let { it == previous.mac }
            ?: (previous.address.value == address.value))
        val savedKey = if (sameDevice) previous.keyBytes() else null
        val body = network.read(address)
        val first = codec.read(body, savedKey)
        var key = savedKey
        val status = if (first is ReadResult.Success) first.value else {
            val failure = first as ReadResult.Failure
            if (savedKey != null || failure.error != ReadError.Format.DECRYPTION_FAILED) fail(failure.error)
            when (val recovered = codec.recover(body)) {
                is ReadResult.Failure -> fail(recovered.error)
                is ReadResult.Success -> {
                    key = recovered.value.keyBytes()
                    // A recovered key is not persisted until a separate read validates it.
                    checked(codec.read(network.read(address), key))
                }
            }
        }
        val profile = DeviceProfile(address, candidate?.mac ?: if (sameDevice) previous.mac else null, key)
        store.save(profile)
        ConnectedDevice(profile, status)
    }

    suspend fun restore(): ConnectedDevice = mutex.withLock {
        val profile = store.load() ?: throw ConnectionException(ConnectionProblem.NO_PROFILE)
        ConnectedDevice(profile, checked(codec.read(network.read(profile.address), profile.keyBytes())))
    }

    suspend fun forget() = mutex.withLock { store.delete() }

    private fun checked(result: ReadResult<DryerStatus>): DryerStatus = when (result) {
        is ReadResult.Success -> result.value
        is ReadResult.Failure -> fail(result.error)
    }

    private fun fail(error: ReadError): Nothing = throw ConnectionException(when (error) {
        is ReadError.Compatibility -> ConnectionProblem.UNEXPECTED_DEVICE
        is ReadError.Recovery -> ConnectionProblem.KEY_RECOVERY_FAILED
        ReadError.Format.TOO_LARGE -> ConnectionProblem.RESPONSE_TOO_LARGE
        else -> ConnectionProblem.INVALID_RESPONSE
    })
}
