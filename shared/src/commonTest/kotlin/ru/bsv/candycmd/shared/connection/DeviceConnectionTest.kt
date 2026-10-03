package ru.bsv.candycmd.shared.connection

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class DeviceConnectionTest {
    private fun ip(last: Int) = listOf(192, 168, 50, last).joinToString(".")
    private fun address(last: Int) = requireNotNull(LocalAddress.parse(ip(last)))
    private val payload = """{"statusTD":{"StatoWiFi":"700","DoorState":"722"}}""".encodeToByteArray()
    private val key = "fixture-key-1234".encodeToByteArray()
    private val mac = "020000000001"
    private fun encrypted(body: ByteArray = payload) = body.mapIndexed { i, b ->
        (b.toInt() xor key[i % key.size].toInt()).and(255).toString(16).padStart(2, '0')
    }.joinToString("").encodeToByteArray()

    private class Store : ProfileStore {
        var profile: DeviceProfile? = null
        var saves = 0
        override suspend fun load() = profile
        override suspend fun save(profile: DeviceProfile) { this.profile = profile; saves++ }
        override suspend fun delete() { profile = null }
    }

    private class Network(var bodies: MutableList<ByteArray>) : DeviceNetwork {
        var reads = 0
        var inFlight = 0
        var maximum = 0
        var hints = emptyList<Candidate>()
        var problem: ConnectionProblem? = null
        var beforeRead: suspend () -> Unit = {}
        var beforeDiscover: suspend () -> Unit = {}
        override suspend fun read(address: LocalAddress): ByteArray {
            reads++
            inFlight++
            maximum = maxOf(maximum, inFlight)
            try {
                beforeRead()
                problem?.let { throw ConnectionException(it) }
                return bodies.removeAt(0)
            } finally { inFlight-- }
        }
        override suspend fun discover(): List<Candidate> { beforeDiscover(); return hints }
    }

    @Test fun rejectsUrlsDnsNonCanonicalAndNonPrivateAddresses() {
        for (invalid in listOf("localhost", "127.0.0.1", "0.0.0.0", "8.8.8.8", "::1",
            "http://${ip(2)}", "${ip(2)}:80", "${ip(2)}/", " ${ip(2)}", "${ip(2)}\n",
            listOf("192", "168", "050", "2").joinToString("."), "2130706433", "0x7f000001")) {
            assertNull(LocalAddress.parse(invalid), invalid)
        }
        for (bytes in listOf(listOf(10, 2, 3, 4), listOf(172, 16, 1, 2), listOf(172, 31, 1, 2), listOf(192, 168, 1, 2))) {
            assertNotNull(LocalAddress.parse(bytes.joinToString(".")))
        }
    }

    @Test fun requiresOnLinkUnicastPeer() {
        assertTrue(address(20).isPeerOf(address(10), 24))
        for (last in listOf(0, 10, 255)) assertFalse(address(last).isPeerOf(address(10), 24))
        assertFalse(address(20).isPeerOf(address(10), 31))
        assertFalse(address(20).isPeerOf(address(10), 0))
        val differentSubnet = requireNotNull(LocalAddress.parse(listOf(192, 168, 51, 20).joinToString(".")))
        assertFalse(differentSubnet.isPeerOf(address(10), 24))
        assertTrue(differentSubnet.isPeerOf(address(10), 16))
    }

    @Test fun heartbeatRequiresObservedFormatAndMatchingSender() {
        val packet = ByteArray(110)
        mac.encodeToByteArray().copyInto(packet, 61)
        ip(20).encodeToByteArray().copyInto(packet, 75)
        val candidate = assertNotNull(Heartbeat.parse(packet, ip(20)))
        assertEquals(mac, candidate.mac)
        assertEquals(ip(20), candidate.address.value)
        assertNull(Heartbeat.parse(packet, ip(21)))
        assertNull(Heartbeat.parse(packet.copyOf(109), ip(20)))
        assertNull(Heartbeat.parse(packet.copyOf(111), ip(20)))
        assertNull(Heartbeat.parse(packet.copyOf().also { it[61] = 'Z'.code.toByte() }, ip(20)))
        assertNull(Heartbeat.parse(packet.copyOf().also { it[62] = '3'.code.toByte() }, ip(20)))
    }

    @Test fun recoveredKeyRequiresSecondReadBeforeSaving() = runTest {
        val store = Store()
        val network = Network(mutableListOf(encrypted(), encrypted()))
        val connection = DeviceConnection(network, store)
        val connected = connection.connect(ip(20), Candidate(address(20), mac))
        assertEquals(2, network.reads)
        assertEquals(1, store.saves)
        assertContentEquals(key, connected.profile.keyBytes())
        assertEquals("722", connected.status["DoorState"])
        network.bodies.add(encrypted())
        assertEquals(ip(20), connection.restore().profile.address.value)
        assertEquals(3, network.reads)
        assertEquals(1, store.saves)
    }

    @Test fun failedConfirmationDoesNotReplaceExistingProfile() = runTest {
        val store = Store().apply { profile = DeviceProfile(address(2), null, null) }
        val original = store.profile
        val network = Network(mutableListOf(encrypted(), """{"statusWM":{}}""".encodeToByteArray()))
        val error = assertFailsWith<ConnectionException> { DeviceConnection(network, store).connect(ip(20)) }
        assertEquals(ConnectionProblem.UNEXPECTED_DEVICE, error.problem)
        assertSame(original, store.profile)
        assertEquals(0, store.saves)
    }

    @Test fun formattedDeviceResponseIsConfirmedAndRestored() = runTest {
        val formatted = "{\r\n\t\"statusTD\":\r\n{\"StatoWiFi\":\"700\",\"DoorState\":\"722\"}\r\n}".encodeToByteArray()
        val store = Store()
        val network = Network(mutableListOf(encrypted(formatted), encrypted(formatted), encrypted(formatted)))
        val connection = DeviceConnection(network, store)
        assertEquals("722", connection.connect(ip(20)).status["DoorState"])
        assertEquals(2, network.reads)
        assertContentEquals(key, store.profile?.keyBytes())
        assertEquals("722", connection.restore().status["DoorState"])
        assertEquals(3, network.reads)
        assertEquals(1, store.saves)
    }

    @Test fun changedIpUsesSavedKeyForExplicitlySelectedMac() = runTest {
        val store = Store().apply { profile = DeviceProfile(address(2), mac, key) }
        val network = Network(mutableListOf(encrypted()))
        val connection = DeviceConnection(network, store)
        connection.connect(ip(20), Candidate(address(20), mac))
        assertEquals(1, network.reads)
        assertEquals(ip(20), store.profile?.address?.value)
        assertContentEquals(key, store.profile?.keyBytes())
    }

    @Test fun restoreDoesNotRecoverAnotherKeyOrReplaceProfile() = runTest {
        val store = Store().apply { profile = DeviceProfile(address(2), mac, ByteArray(16)) }
        val original = store.profile
        val network = Network(mutableListOf(encrypted()))
        assertEquals(ConnectionProblem.INVALID_RESPONSE,
            assertFailsWith<ConnectionException> { DeviceConnection(network, store).restore() }.problem)
        assertEquals(1, network.reads)
        assertSame(original, store.profile)
    }

    @Test fun emptyMultipleAndCancelledDiscoveryNeverSaveProfile() = runTest {
        val store = Store()
        val network = Network(mutableListOf())
        val connection = DeviceConnection(network, store)
        assertEquals(ConnectionProblem.NO_CANDIDATES,
            assertFailsWith<ConnectionException> { connection.discover() }.problem)
        network.hints = listOf(Candidate(address(2), mac), Candidate(address(3), "020000000002"))
        assertEquals(2, connection.discover().size)
        val started = CompletableDeferred<Unit>()
        network.beforeDiscover = { started.complete(Unit); awaitCancellation() }
        val search = launch { connection.discover() }
        started.await()
        search.cancelAndJoin()
        network.beforeDiscover = {}
        assertEquals(2, connection.discover().size)
        assertEquals(0, store.saves)
        assertEquals(0, network.reads)
    }

    @Test fun concurrentOperationsSerializeAndForgetCannotBeOverwritten() = runTest {
        val store = Store()
        val network = Network(mutableListOf(payload, payload)).apply { beforeRead = { delay(100) } }
        val connection = DeviceConnection(network, store)
        val first = launch { connection.connect(ip(2)) }
        val second = launch { connection.connect(ip(3)) }
        val deletion = launch { connection.forget() }
        joinAll(first, second, deletion)
        assertEquals(1, network.maximum)
        assertEquals(2, store.saves)
        assertNull(store.profile)
        assertEquals(ConnectionProblem.NO_PROFILE, assertFailsWith<ConnectionException> { connection.restore() }.problem)
    }

    @Test fun cancelledReadReleasesChannelWithoutSaving() = runTest {
        val store = Store()
        val network = Network(mutableListOf(payload))
        val started = CompletableDeferred<Unit>()
        network.beforeRead = { started.complete(Unit); awaitCancellation() }
        val connection = DeviceConnection(network, store)
        val request = launch { connection.connect(ip(2)) }
        started.await()
        request.cancelAndJoin()
        assertEquals(0, store.saves)
        network.beforeRead = {}
        connection.connect(ip(2))
        assertEquals(1, store.saves)
    }

    @Test fun transportFailuresRemainSpecificAndDoNotSave() = runTest {
        for (problem in listOf(ConnectionProblem.PERMISSION_DENIED, ConnectionProblem.NO_WIFI,
            ConnectionProblem.TIMEOUT, ConnectionProblem.HTTP_STATUS, ConnectionProblem.RESPONSE_TOO_LARGE,
            ConnectionProblem.NETWORK_LOST, ConnectionProblem.UNAVAILABLE)) {
            val store = Store()
            val network = Network(mutableListOf()).apply { this.problem = problem }
            assertEquals(problem, assertFailsWith<ConnectionException> {
                DeviceConnection(network, store).connect(ip(2))
            }.problem)
            assertEquals(0, store.saves)
        }
    }

    @Test fun profileDefensivelyCopiesKeyAndRedactsDiagnostics() {
        val bytes = key.copyOf()
        val profile = DeviceProfile(address(2), mac, bytes)
        bytes.fill(0)
        profile.keyBytes()?.fill(0)
        assertContentEquals(key, profile.keyBytes())
        for (text in listOf(profile.toString(), profile.address.toString(), Candidate(address(2), mac).toString())) {
            assertFalse(text.contains(ip(2)))
            assertFalse(text.contains(mac))
            assertFalse(text.contains(key.decodeToString()))
        }
    }
}
