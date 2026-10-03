package ru.bsv.candycmd

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.bsv.candycmd.connection.AndroidDeviceNetwork
import ru.bsv.candycmd.shared.connection.ConnectionException
import ru.bsv.candycmd.shared.connection.ConnectionProblem
import ru.bsv.candycmd.shared.connection.LocalAddress

class LanPermissionTest {
    @Test fun explicitPlatformPermissionGatesReadsBeforeAnyNetworkRequest() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val network = AndroidDeviceNetwork(instrumentation.targetContext)
        val expected = InstrumentationRegistry.getArguments().getString("expectedLanPermission")
        if (Build.VERSION.SDK_INT >= 37 && expected != null) {
            assertEquals(expected == "granted", network.hasPermission())
            if (expected == "denied") {
                try {
                    val syntheticLanAddress = listOf(192, 168, 50, 123).joinToString(".")
                    network.read(requireNotNull(LocalAddress.parse(syntheticLanAddress)))
                    fail("Denied permission must stop the request before network selection")
                } catch (error: ConnectionException) {
                    assertEquals(ConnectionProblem.PERMISSION_DENIED, error.problem)
                }
            }
        } else if (Build.VERSION.SDK_INT < 37) {
            assertTrue(network.hasPermission())
        }
    }
}
