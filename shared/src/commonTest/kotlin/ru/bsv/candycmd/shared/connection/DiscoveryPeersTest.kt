package ru.bsv.candycmd.shared.connection

import kotlin.test.*

class DiscoveryPeersTest {
    private fun ip(last: Int) = listOf(192, 168, 1, last).joinToString(".")
    private val phone = requireNotNull(LocalAddress.parse(ip(100)))

    @Test fun respectsSubnetAndExcludesPhoneNetworkAndBroadcast() {
        val peers = discoveryPeers(phone, 24)
        assertEquals(253, peers.size)
        assertTrue(peers.all { it.isPeerOf(phone, 24) })
        assertEquals(peers.size, peers.map { it.value }.distinct().size)
        assertEquals(setOf(ip(102)), discoveryPeers(requireNotNull(LocalAddress.parse(ip(101))), 30).map { it.value }.toSet())
        assertTrue(discoveryPeers(phone, 31).isEmpty())
        assertTrue(discoveryPeers(phone, 0).isEmpty())
    }

    @Test fun largeNetworksAreBoundedAndNearbyPeersComeFirst() {
        val peers = discoveryPeers(phone, 16)
        assertTrue(peers.size <= 1023)
        assertTrue(peers.take(255).all { it.octets.take(3) == phone.octets.take(3) })
        assertTrue(peers.all { it.isPeerOf(phone, 16) })
    }
}
