package ru.bsv.candycmd.connection

import kotlinx.coroutines.*
import ru.bsv.candycmd.shared.connection.Candidate
import ru.bsv.candycmd.shared.connection.LocalAddress
import ru.bsv.candycmd.shared.protocol.ReadResult
import ru.bsv.candycmd.shared.protocol.StatusCodec

/** A reachable HTTP server is not sufficient: require a valid dryer status, including encrypted replies. */
internal class LanDiscovery(
    private val probe: suspend (LocalAddress) -> Boolean,
    private val read: suspend (LocalAddress) -> ByteArray?,
) {
    suspend fun discover(peers: List<LocalAddress>, timeoutMillis: Long = 45_000): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        withTimeoutOrNull(timeoutMillis) {
            for (batch in peers.distinctBy { it.value }.chunked(16)) {
                currentCoroutineContext().ensureActive()
                val reachable = coroutineScope {
                    batch.map { address -> async { address.takeIf { probe(it) } } }.awaitAll().filterNotNull()
                }
                // One HTTP read per address; TCP probes never send appliance commands.
                for (address in reachable) {
                    val body = read(address) ?: continue
                    val codec = StatusCodec()
                    val status = when (val result = codec.read(body)) {
                        is ReadResult.Success -> result.value
                        is ReadResult.Failure -> (codec.recover(body) as? ReadResult.Success)?.value?.status
                    }
                    if (status != null && listOf("Pr", "StatoTD", "StatoWiFi").all { status[it]?.toIntOrNull() != null }) {
                        candidates.add(Candidate(address, null))
                        if (candidates.size == 8) return@withTimeoutOrNull
                    }
                }
            }
        }
        return candidates
    }
}
