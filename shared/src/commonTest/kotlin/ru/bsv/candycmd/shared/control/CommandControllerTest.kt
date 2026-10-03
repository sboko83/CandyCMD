package ru.bsv.candycmd.shared.control

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import ru.bsv.candycmd.shared.connection.*

@OptIn(ExperimentalCoroutinesApi::class)
class CommandControllerTest {
    private class Marker(var pending: Boolean = false) : PendingCommandStore {
        override suspend fun isPending() = pending
        override suspend fun setPending(pending: Boolean) { this.pending = pending }
    }
    private val start = DryerCommand.Start(Settings("daily-59"))
    // Synthetic test address and key, never a real appliance.
    private val profile = DeviceProfile(requireNotNull(LocalAddress.parse(listOf(192, 168, 50, 123).joinToString("."))),
        null, "fixture-key-1234".encodeToByteArray())

    @Test fun lostResponseCanBeConfirmedWithoutRetry() = runTest {
        var sends = 0
        var status = idle()
        val marker = Marker()
        val controller = CommandController({ ConnectedDevice(profile, status) }, object : CommandTransport {
            override suspend fun send(profile: DeviceProfile, command: PreparedCommand) {
                assertTrue(marker.pending)
                sends++
                status = running(id = requireNotNull(command.recipeId))
                throw ConnectionException(ConnectionProblem.TIMEOUT)
            }
        }, marker, { TEST_NOW })
        controller.execute(start)
        assertEquals(CommandPhase.CONFIRMED, controller.state.value.phase)
        assertEquals(1, sends)
        assertFalse(marker.pending)
    }

    @Test fun httpSuccessAloneStaysUnknownAndRestartNeverSends() = runTest {
        var sends = 0
        var reads = 0
        val marker = Marker()
        val transport = object : CommandTransport {
            override suspend fun send(profile: DeviceProfile, command: PreparedCommand) { sends++ }
        }
        fun controller() = CommandController({ reads++; ConnectedDevice(profile, idle()) }, transport, marker, { TEST_NOW })
        val first = controller()
        first.execute(start)
        assertEquals(CommandPhase.UNKNOWN, first.state.value.phase)
        assertTrue(marker.pending)
        assertEquals(5, reads)
        first.execute(start)
        val restored = controller()
        restored.restore()
        restored.execute(start)
        assertEquals(CommandPhase.UNKNOWN, restored.state.value.phase)
        assertEquals(1, sends)
        assertEquals(5, reads)
        restored.checkIdle()
        assertEquals(CommandPhase.IDLE, restored.state.value.phase)
        assertFalse(marker.pending)
        assertEquals(1, sends)
    }

    @Test fun doubleTapAndCancellationNeverSendTwice() = runTest {
        val marker = Marker()
        var sends = 0
        val controller = CommandController({ ConnectedDevice(profile, idle()) }, object : CommandTransport {
            override suspend fun send(profile: DeviceProfile, command: PreparedCommand) { sends++; awaitCancellation() }
        }, marker, { TEST_NOW })
        val first = launch { controller.execute(start) }
        runCurrent()
        assertEquals(CommandPhase.SENDING, controller.state.value.phase)
        controller.execute(start)
        assertEquals(1, sends)
        first.cancelAndJoin()
        assertTrue(marker.pending)
        assertEquals(CommandPhase.UNKNOWN, controller.state.value.phase)
        controller.execute(start)
        assertEquals(1, sends)
    }

    @Test fun freshPreflightRejectsChangesSinceDialog() = runTest {
        var sends = 0
        val target = requireNotNull(CycleTarget.from(running(id = "16DCMd")))
        val controller = CommandController({ ConnectedDevice(profile, running(id = "16DCNb")) }, object : CommandTransport {
            override suspend fun send(profile: DeviceProfile, command: PreparedCommand) { sends++ }
        }, Marker(), { TEST_NOW })
        controller.execute(DryerCommand.Cancel(target))
        assertEquals(CommandBlock.CYCLE_CHANGED, controller.state.value.block)
        assertEquals(0, sends)
    }

    @Test fun markerWriteFailureAndMissingKeyPreventSend() = runTest {
        var sends = 0
        val transport = object : CommandTransport {
            override suspend fun send(profile: DeviceProfile, command: PreparedCommand) { sends++ }
        }
        val broken = object : PendingCommandStore {
            override suspend fun isPending() = false
            override suspend fun setPending(pending: Boolean) { error("Unavailable") }
        }
        val controller = CommandController({ ConnectedDevice(profile, idle()) }, transport, broken, { TEST_NOW })
        controller.execute(start)
        assertEquals(CommandPhase.UNKNOWN, controller.state.value.phase)
        val noKey = CommandController({ ConnectedDevice(DeviceProfile(profile.address, null, null), idle()) }, transport, Marker(), { TEST_NOW })
        noKey.execute(start)
        assertEquals(CommandBlock.NO_KEY, noKey.state.value.block)
        assertEquals(0, sends)
    }

    @Test fun recoveryDuringActiveCycleOrLostNetworkKeepsLock() = runTest {
        val marker = Marker(true)
        var failed = false
        val controller = CommandController({
            if (failed) throw ConnectionException(ConnectionProblem.NETWORK_LOST)
            ConnectedDevice(profile, running())
        }, object : CommandTransport {
            override suspend fun send(profile: DeviceProfile, command: PreparedCommand) = error("No writes")
        }, marker, { TEST_NOW })
        controller.restore()
        controller.checkIdle()
        assertEquals(CommandPhase.UNKNOWN, controller.state.value.phase)
        failed = true
        controller.checkIdle()
        assertEquals(CommandPhase.UNKNOWN, controller.state.value.phase)
        assertTrue(marker.pending)
    }

    @Test fun transportTimeoutStillObservesButExternalCancellationDoesNotRetry() = runTest {
        var status = idle()
        val controller = CommandController({ ConnectedDevice(profile, status) }, object : CommandTransport {
            override suspend fun send(profile: DeviceProfile, command: PreparedCommand) {
                status = running(id = requireNotNull(command.recipeId))
                withTimeout(1) { delay(2) }
            }
        }, Marker(), { TEST_NOW })
        controller.execute(start)
        assertEquals(CommandPhase.CONFIRMED, controller.state.value.phase)
    }

    @Test fun changedProfilesCannotConfirmOrReplaceObservedDevice() = runTest {
        val alternatives = listOf(
            DeviceProfile(requireNotNull(LocalAddress.parse(listOf(192, 168, 50, 124).joinToString("."))), null, profile.keyBytes()),
            DeviceProfile(profile.address, "AABBCCDDEEFF", profile.keyBytes()),
            DeviceProfile(profile.address, null, "other-key-1234567".encodeToByteArray()),
            DeviceProfile(profile.address, null, null),
        )
        for (replacement in alternatives) {
            var reads = 0
            var sends = 0
            var result = idle()
            val seen = mutableListOf<ru.bsv.candycmd.shared.protocol.DryerStatus>()
            val marker = Marker()
            val controller = CommandController({
                ConnectedDevice(if (reads++ == 0) profile else replacement, result)
            }, object : CommandTransport {
                override suspend fun send(profile: DeviceProfile, command: PreparedCommand) {
                    sends++
                    result = running(id = requireNotNull(command.recipeId))
                }
            }, marker, { TEST_NOW }, seen::add)
            controller.execute(start)
            assertEquals(CommandPhase.UNKNOWN, controller.state.value.phase)
            assertEquals(1, sends)
            assertEquals(listOf(idle()), seen)
            assertTrue(marker.pending)
        }
    }

    @Test fun openedDoorAndLostRemoteModeAtPreflightPreventEveryWrite() = runTest {
        var sends = 0
        val transport = object : CommandTransport {
            override suspend fun send(profile: DeviceProfile, command: PreparedCommand) { sends++ }
        }
        for (field in listOf("DoorState", "StatoWiFi", "CodiceErrore")) {
            for (value in listOf(null, "99", if (field == "CodiceErrore") "1" else "0")) {
                val changed = ru.bsv.candycmd.shared.protocol.DryerStatus(
                    if (value == null) idle().fields - field else idle().fields + (field to value))
                val marker = Marker()
                val controller = CommandController({ ConnectedDevice(profile, changed) }, transport, marker, { TEST_NOW })
                controller.execute(start)
                assertEquals(CommandPhase.REJECTED, controller.state.value.phase)
                assertEquals(CommandBlock.NOT_READY, controller.state.value.block)
                assertFalse(marker.pending)
            }
        }
        assertEquals(0, sends)
    }

    @Test fun malformedObservationAndUnsafeRecoveryKeepDurableLock() = runTest {
        var written = false
        var sends = 0
        var recovery: ru.bsv.candycmd.shared.protocol.DryerStatus? = null
        val marker = Marker()
        val controller = CommandController({
            if (written && recovery == null) throw ConnectionException(ConnectionProblem.INVALID_RESPONSE)
            ConnectedDevice(profile, recovery ?: idle())
        }, object : CommandTransport {
            override suspend fun send(profile: DeviceProfile, command: PreparedCommand) { sends++; written = true }
        }, marker, { TEST_NOW })
        controller.execute(start)
        assertEquals(CommandPhase.UNKNOWN, controller.state.value.phase)
        for (field in listOf("DoorState", "StatoWiFi")) {
            recovery = ru.bsv.candycmd.shared.protocol.DryerStatus(idle().fields + (field to "0"))
            controller.checkIdle()
            assertEquals(CommandPhase.UNKNOWN, controller.state.value.phase)
            assertTrue(marker.pending)
        }
        recovery = idle()
        controller.checkIdle()
        assertEquals(CommandPhase.IDLE, controller.state.value.phase)
        assertFalse(marker.pending)
        assertEquals(1, sends)
    }

    @Test fun cancellingDelayRequiresIdleAndNeverResends() = runTest {
        val delayedStart = CommandBuilder.prepare(DryerCommand.Start(Settings("daily-59", delayMinutes = 30)), TEST_NOW)
        var status = ru.bsv.candycmd.shared.protocol.DryerStatus(
            running(id = requireNotNull(delayedStart.recipeId)).fields +
                mapOf("StatoTD" to "5", "PrPh" to "0", "DelVal" to "30"))
        val cancel = DryerCommand.Cancel(requireNotNull(CycleTarget.from(status)))
        var sends = 0
        val marker = Marker()
        val controller = CommandController({ ConnectedDevice(profile, status) }, object : CommandTransport {
            override suspend fun send(profile: DeviceProfile, command: PreparedCommand) { sends++; status = idle() }
        }, marker, { TEST_NOW })
        controller.execute(cancel)
        assertEquals(CommandPhase.CONFIRMED, controller.state.value.phase)
        assertFalse(marker.pending)
        assertEquals(1, sends)
    }

    @Test fun cancelStaysAvailableAfterUnknownStart() = runTest {
        var status = idle()
        var sends = 0
        var sentId: String? = null
        val marker = Marker()
        val controller = CommandController({ ConnectedDevice(profile, status) }, object : CommandTransport {
            override suspend fun send(profile: DeviceProfile, command: PreparedCommand) {
                sends++
                if (command.command is DryerCommand.Cancel) status = idle() else sentId = command.recipeId
            }
        }, marker, { TEST_NOW })
        controller.execute(start)
        assertEquals(CommandPhase.UNKNOWN, controller.state.value.phase)
        status = running(id = requireNotNull(sentId))
        controller.execute(start)
        assertEquals(1, sends)
        controller.execute(DryerCommand.Cancel(requireNotNull(CycleTarget.from(status))))
        assertEquals(CommandPhase.CONFIRMED, controller.state.value.phase)
        assertFalse(marker.pending)
        assertEquals(2, sends)
    }

    @Test fun lateObservedTransitionConfirmsWithoutSending() = runTest {
        var status = idle()
        var sends = 0
        var sentId: String? = null
        val marker = Marker()
        val controller = CommandController({ ConnectedDevice(profile, status) }, object : CommandTransport {
            override suspend fun send(profile: DeviceProfile, command: PreparedCommand) { sends++; sentId = command.recipeId }
        }, marker, { TEST_NOW })
        controller.execute(start)
        assertEquals(CommandPhase.UNKNOWN, controller.state.value.phase)
        val other = DeviceProfile(requireNotNull(LocalAddress.parse(listOf(192, 168, 50, 124).joinToString("."))),
            null, "fixture-key-1234".encodeToByteArray())
        controller.reconcile(ConnectedDevice(other, running(id = requireNotNull(sentId))))
        assertEquals(CommandPhase.UNKNOWN, controller.state.value.phase)
        controller.reconcile(ConnectedDevice(profile, running(id = requireNotNull(sentId))))
        assertEquals(CommandPhase.CONFIRMED, controller.state.value.phase)
        assertFalse(marker.pending)
        assertEquals(1, sends)
    }

    @Test fun observedIdleUnlocksOnlyAfterSettling() = runTest {
        var now = TEST_NOW
        val marker = Marker(true)
        val controller = CommandController({ ConnectedDevice(profile, idle()) }, object : CommandTransport {
            override suspend fun send(profile: DeviceProfile, command: PreparedCommand) = error("No writes")
        }, marker, { now })
        controller.restore()
        controller.reconcile(ConnectedDevice(profile, idle()))
        assertEquals(CommandPhase.UNKNOWN, controller.state.value.phase)
        now += CommandController.SETTLE_MILLIS
        val doorOpen = ru.bsv.candycmd.shared.protocol.DryerStatus(idle().fields + ("DoorState" to "0"))
        controller.reconcile(ConnectedDevice(profile, doorOpen))
        assertEquals(CommandPhase.UNKNOWN, controller.state.value.phase)
        controller.reconcile(ConnectedDevice(profile, idle()))
        assertEquals(CommandPhase.IDLE, controller.state.value.phase)
        assertFalse(marker.pending)
    }

    @Test fun recognisedCycleAfterRestartReleasesLockButNotStart() = runTest {
        var now = TEST_NOW
        val marker = Marker(true)
        val controller = CommandController({ ConnectedDevice(profile, running()) }, object : CommandTransport {
            override suspend fun send(profile: DeviceProfile, command: PreparedCommand) = error("No writes")
        }, marker, { now })
        controller.restore()
        controller.reconcile(ConnectedDevice(profile, running()))
        assertEquals(CommandPhase.UNKNOWN, controller.state.value.phase)
        now += CommandController.SETTLE_MILLIS
        controller.reconcile(ConnectedDevice(profile, running()))
        assertEquals(CommandPhase.IDLE, controller.state.value.phase)
        assertFalse(marker.pending)
        controller.execute(start)
        assertEquals(CommandPhase.REJECTED, controller.state.value.phase)
    }
}
