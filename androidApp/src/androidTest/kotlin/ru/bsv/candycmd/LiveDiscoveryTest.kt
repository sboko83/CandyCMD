package ru.bsv.candycmd

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.bsv.candycmd.connection.AndroidDeviceNetwork
import ru.bsv.candycmd.shared.protocol.ReadResult
import ru.bsv.candycmd.shared.protocol.StatusCodec

/** Opt-in, read-only acceptance test on a phone sharing the dryer's Wi-Fi. */
class LiveDiscoveryTest {
    @Test fun findsDryerWithoutSavedAddress() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveDiscovery") == "true")
        val network = AndroidDeviceNetwork(InstrumentationRegistry.getInstrumentation().targetContext)
        // Match the ViewModel's caller dispatcher; transport must move blocking IO off main.
        val candidates = withContext(Dispatchers.Main) { network.discover() }
        assertTrue("No dryer discovered", candidates.isNotEmpty())
        val body = withContext(Dispatchers.Main) { network.read(candidates.first().address) }
        val codec = StatusCodec()
        val status = when (val result = codec.read(body)) {
            is ReadResult.Success -> result.value
            is ReadResult.Failure -> (codec.recover(body) as? ReadResult.Success)?.value?.status
        }
        assertTrue("Discovered dryer must return a valid status", status != null)
    }
}
