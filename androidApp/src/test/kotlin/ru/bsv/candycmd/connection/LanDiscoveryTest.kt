package ru.bsv.candycmd.connection

import kotlinx.coroutines.*
import kotlin.test.*
import ru.bsv.candycmd.shared.connection.LocalAddress

class LanDiscoveryTest {
    private val peers = (1..4).map { requireNotNull(LocalAddress.parse(listOf(192, 168, 1, it).joinToString("."))) }
    private val status = "{\"statusTD\":{\"StatoWiFi\":\"1\",\"StatoTD\":\"1\",\"Pr\":\"15\"}}".encodeToByteArray()

    @Test fun detectsPlainAndEncryptedDryersButRejectsOtherHttpServers() = runBlocking {
        val key = "abcdefghijklmnop".encodeToByteArray()
        val encrypted = status.mapIndexed { i, byte -> "%02X".format((byte.toInt() xor key[i % 16].toInt()) and 255) }
            .joinToString("").encodeToByteArray()
        val reads = mutableListOf<String>()
        val scanner = LanDiscovery(probe = { it != peers[3] }, read = {
            reads.add(it.value)
            when (it) { peers[0] -> status; peers[1] -> encrypted; else -> "<html>Router</html>".encodeToByteArray() }
        })
        val found = scanner.discover(peers + peers[0])
        assertEquals(peers.take(2).map { it.value }, found.map { it.address.value })
        assertTrue(found.all { it.mac == null })
        assertEquals(peers.take(3).map { it.value }, reads)
    }

    @Test fun cancellationStopsProbesAndNeverStartsHttpReads() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var reads = 0
        val scanner = LanDiscovery(probe = { entered.complete(Unit); awaitCancellation() }, read = { reads++; status })
        val job = launch { scanner.discover(peers) }
        entered.await()
        job.cancelAndJoin()
        assertEquals(0, reads)
    }

    @Test fun scanDeadlinePreservesDevicesAlreadyFound() = runBlocking {
        val scanner = LanDiscovery(probe = { true }, read = {
            if (it == peers[0]) status else awaitCancellation()
        })
        val found = scanner.discover(peers, timeoutMillis = 100)
        assertEquals(listOf(peers[0].value), found.map { it.address.value })
    }
}
