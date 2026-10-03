package ru.bsv.candycmd.connection

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.bsv.candycmd.shared.connection.*
import ru.bsv.candycmd.shared.control.CommandTransport
import ru.bsv.candycmd.shared.control.PreparedCommand
import java.io.IOException
import java.net.*

class AndroidDeviceNetwork(private val context: Context) : DeviceNetwork, CommandTransport {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.getSystemService(WifiManager::class.java)
    private val channel = Mutex()
    private var lastRead = -3_000L
    var selectedNetwork: Network? = null
    var lastUsedNetwork: Network? = null
        private set

    fun hasPermission(): Boolean = Build.VERSION.SDK_INT < 37 ||
        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun wifiNetworks(): List<Network> = connectivity.allNetworks.filter { isWifi(it) }

    private fun isWifi(network: Network): Boolean = connectivity.getNetworkCapabilities(network)?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    } == true

    private fun checkPermission() {
        if (!hasPermission()) throw ConnectionException(ConnectionProblem.PERMISSION_DENIED)
    }

    private fun network(): Network {
        checkPermission()
        selectedNetwork?.let {
            if (!isWifi(it)) throw ConnectionException(ConnectionProblem.NETWORK_LOST)
            return it.also { lastUsedNetwork = it }
        }
        val available = wifiNetworks()
        return when (available.size) {
            0 -> throw ConnectionException(ConnectionProblem.NO_WIFI)
            1 -> available.single()
            else -> throw ConnectionException(ConnectionProblem.WIFI_SELECTION_REQUIRED)
        }.also { lastUsedNetwork = it }
    }

    private fun checkPeer(network: Network, address: LocalAddress) {
        checkPermission()
        if (!isWifi(network)) throw ConnectionException(ConnectionProblem.NETWORK_LOST)
        val links = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
        if (links.none { link ->
                LocalAddress.parse(link.address.hostAddress.orEmpty())?.let {
                    address.isPeerOf(it, link.prefixLength)
                } == true
            }) throw ConnectionException(ConnectionProblem.OFF_LINK)
    }

    override suspend fun read(address: LocalAddress): ByteArray = channel.withLock {
        val network = network()
        delay((3_000 - (SystemClock.elapsedRealtime() - lastRead)).coerceAtLeast(0))
        checkPeer(network, address)
        lastRead = SystemClock.elapsedRealtime()
        try {
            val body = BoundedHttpReader(trace = { event ->
                if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    Log.d("CandyHttp", event)
                }
            }).read {
                network.openConnection(
                    URL("http://${address.value}/http-read.json?encrypted=1"), Proxy.NO_PROXY,
                ) as HttpURLConnection
            }
            checkPeer(network, address)
            body
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw ConnectionException(ConnectionProblem.TIMEOUT)
        } catch (error: SecurityException) {
            throw ConnectionException(ConnectionProblem.PERMISSION_DENIED)
        } catch (error: IOException) {
            checkPermission()
            if (!isWifi(network)) throw ConnectionException(ConnectionProblem.NETWORK_LOST)
            throw ConnectionException(if (error is SocketTimeoutException) ConnectionProblem.TIMEOUT else ConnectionProblem.UNAVAILABLE)
        }
    }

    override suspend fun send(profile: DeviceProfile, command: PreparedCommand) = channel.withLock {
        val network = lastUsedNetwork ?: throw ConnectionException(ConnectionProblem.NETWORK_LOST)
        checkPeer(network, profile.address)
        val path = command.encryptedPath(requireNotNull(profile.keyBytes()))
        // No trace contains the address, key, command or encrypted query.
        SingleCommandWriter().send(profile.address.value, path, network.socketFactory.createSocket())
    }

    override suspend fun discover(): List<Candidate> = channel.withLock {
        val network = network()
        val announced = try { listenForHeartbeat(network) } catch (error: ConnectionException) {
            if (error.problem != ConnectionProblem.DISCOVERY_UNAVAILABLE) throw error
            emptyList()
        }
        if (announced.isNotEmpty()) return@withLock announced
        val peers = connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().flatMap { link ->
            LocalAddress.parse(link.address.hostAddress.orEmpty())?.let { discoveryPeers(it, link.prefixLength) }.orEmpty()
        }
        try {
            LanDiscovery(probe = { address ->
                checkPeer(network, address)
                try {
                    network.socketFactory.createSocket().use { socket ->
                        closingIo({ socket.close() }) { socket.connect(InetSocketAddress(address.value, 80), 300) }
                    }
                    true
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    checkPeer(network, address)
                    false
                }
            }, read = { address ->
                checkPeer(network, address)
                // Keep the same spacing before a later connect/recovery request.
                delay((3_000 - (SystemClock.elapsedRealtime() - lastRead)).coerceAtLeast(0))
                lastRead = SystemClock.elapsedRealtime()
                try {
                    BoundedHttpReader(timeoutMillis = 2_000).read {
                        network.openConnection(URL("http://${address.value}/http-read.json?encrypted=1"), Proxy.NO_PROXY) as HttpURLConnection
                    }.also { checkPeer(network, address) }
                } catch (error: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    null
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    checkPeer(network, address)
                    null
                } catch (error: ConnectionException) {
                    if (error.problem !in setOf(ConnectionProblem.HTTP_STATUS, ConnectionProblem.RESPONSE_TOO_LARGE)) throw error
                    null
                }
            }).discover(peers)
        } catch (_: SecurityException) {
            throw ConnectionException(ConnectionProblem.PERMISSION_DENIED)
        }
    }

    private suspend fun listenForHeartbeat(network: Network): List<Candidate> {
        val lock = wifi.createMulticastLock("CandyCMD discovery").apply { setReferenceCounted(false) }
        return try {
            DatagramSocket(null).use { socket ->
                network.bindSocket(socket)
                socket.reuseAddress = false
                socket.soTimeout = 300
                socket.bind(InetSocketAddress(55555))
                lock.acquire()
                closingIo({ socket.close() }) {
                    val deadline = SystemClock.elapsedRealtime() + 8_000
                    val candidates = linkedMapOf<String, Candidate>()
                    while (SystemClock.elapsedRealtime() < deadline && candidates.size < 8) {
                        checkPermission()
                        if (!isWifi(network)) throw ConnectionException(ConnectionProblem.NETWORK_LOST)
                        val packet = DatagramPacket(ByteArray(111), 111)
                        try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                        val candidate = Heartbeat.parse(packet.data.copyOf(packet.length), packet.address.hostAddress.orEmpty())
                            ?: continue
                        try { checkPeer(network, candidate.address) } catch (error: ConnectionException) {
                            if (error.problem == ConnectionProblem.OFF_LINK) continue else throw error
                        }
                        candidates["${candidate.mac}/${candidate.address.value}"] = candidate
                    }
                    candidates.values.toList()
                }
            }
        } catch (error: SecurityException) {
            throw ConnectionException(ConnectionProblem.PERMISSION_DENIED)
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            checkPermission()
            if (!isWifi(network)) throw ConnectionException(ConnectionProblem.NETWORK_LOST)
            throw ConnectionException(ConnectionProblem.DISCOVERY_UNAVAILABLE)
        } finally {
            if (lock.isHeld) lock.release()
        }
    }

}
