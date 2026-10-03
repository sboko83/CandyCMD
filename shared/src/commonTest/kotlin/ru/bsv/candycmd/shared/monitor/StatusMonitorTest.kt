package ru.bsv.candycmd.shared.monitor

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import ru.bsv.candycmd.shared.connection.ConnectionException
import ru.bsv.candycmd.shared.connection.ConnectionProblem
import ru.bsv.candycmd.shared.protocol.DryerStatus
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StatusMonitorTest {
    private val observed = DryerStatus(mapOf("Pr" to "2", "DoorState" to "1", "RemTime" to "45"))

    @Test fun rapidLifecycleReplacementsWaitForOldTransportCleanup() = runTest {
        val session = SerialSession(backgroundScope)
        val events = mutableListOf<String>()
        session.replace {
            try { events += "first"; awaitCancellation() }
            finally { withContext(NonCancellable) { delay(1_000); events += "closed" } }
        }
        runCurrent()
        session.cancel()
        session.replace { events += "cancelled replacement" }
        runCurrent()
        session.cancel()
        session.replace { events += "resumed" }
        runCurrent()
        assertEquals(listOf("first"), events)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("first", "closed", "resumed"), events)
    }

    @Test fun connectionReadSeedsMonitorWithoutImmediateDuplicateAndForgetClearsIt() = runTest {
        var reads = 0
        val monitor = StatusMonitor({ reads++; observed }, { testScheduler.currentTime })
        monitor.reset(observed)
        assertEquals(observed, monitor.state.value.status)
        assertFalse(monitor.state.value.stale)
        val session = backgroundScope.launch { monitor.run(delayFirstRead = true) }
        runCurrent()
        assertEquals(0, reads)
        advanceTimeBy(4_999); runCurrent()
        assertEquals(0, reads)
        advanceTimeBy(1); runCurrent()
        assertEquals(1, reads)
        monitor.invalidate(ConnectionProblem.NETWORK_LOST)
        assertTrue(monitor.state.value.stale)
        assertEquals(observed, monitor.state.value.status)
        session.cancelAndJoin()
        monitor.reset()
        assertNull(monitor.state.value.status)
        assertNull(monitor.state.value.lastSuccessMillis)
        assertNull(monitor.state.value.problem)
    }

    @Test fun pollsOnlyWhileOwnedAndRetainsStaleDataAcrossRestart() = runTest {
        var reads = 0
        val monitor = StatusMonitor({ reads++; observed }, { testScheduler.currentTime })
        assertFalse(monitor.refresh())
        val first = backgroundScope.launch { monitor.run() }
        runCurrent()
        assertEquals(1, reads)
        assertFalse(monitor.state.value.stale)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(2, reads)
        first.cancelAndJoin()
        assertEquals(5_000L, monitor.state.value.lastSuccessMillis)
        assertTrue(monitor.state.value.stale)
        assertFalse(monitor.state.value.active)
        assertFalse(monitor.refresh())
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, reads)
        backgroundScope.launch { monitor.run() }
        runCurrent()
        assertEquals(3, reads)
        assertFalse(monitor.state.value.stale)
    }

    @Test fun duplicateOwnersAndRepeatedRefreshDoNotDuplicateReads() = runTest {
        var reads = 0
        var inFlight = 0
        var maximum = 0
        val monitor = StatusMonitor({
            reads++; inFlight++; maximum = maxOf(maximum, inFlight)
            try { delay(1_000); observed } finally { inFlight-- }
        }, { testScheduler.currentTime })
        val first = backgroundScope.launch { monitor.run() }
        runCurrent()
        val second = backgroundScope.launch { monitor.run() }
        runCurrent()
        assertTrue(second.isCompleted)
        repeat(10) { assertFalse(monitor.refresh()) }
        advanceTimeBy(1_000); runCurrent()
        repeat(10) { monitor.refresh() }
        runCurrent()
        assertEquals(2, reads)
        assertEquals(1, maximum)
        first.cancelAndJoin()
        assertEquals(0, inFlight)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, reads)
    }

    @Test fun failureBackoffIsCappedAndSuccessRestoresNormalInterval() = runTest {
        val times = mutableListOf<Long>()
        var fail = false
        val monitor = StatusMonitor({
            times += testScheduler.currentTime
            if (fail) throw ConnectionException(ConnectionProblem.TIMEOUT)
            observed
        }, { testScheduler.currentTime })
        backgroundScope.launch { monitor.run() }
        runCurrent()
        fail = true
        advanceTimeBy(5_000); runCurrent()
        assertEquals(observed, monitor.state.value.status)
        assertEquals(0L, monitor.state.value.lastSuccessMillis)
        assertEquals(ConnectionProblem.TIMEOUT, monitor.state.value.problem)
        assertTrue(monitor.state.value.stale)
        advanceTimeBy(10_000); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(0L, 5_000L, 15_000L, 35_000L, 65_000L, 95_000L), times)
        fail = false
        assertTrue(monitor.refresh())
        runCurrent()
        assertNull(monitor.state.value.problem)
        assertFalse(monitor.state.value.stale)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(100_000L, times.last())
    }

    @Test fun initialFailureDoesNotInventStatusOrLastSuccess() = runTest {
        val monitor = StatusMonitor({ throw ConnectionException(ConnectionProblem.NO_PROFILE) }, { 123L })
        backgroundScope.launch { monitor.run() }
        runCurrent()
        assertNull(monitor.state.value.status)
        assertNull(monitor.state.value.lastSuccessMillis)
        assertTrue(monitor.state.value.stale)
        assertEquals(ConnectionProblem.NO_PROFILE, monitor.state.value.problem)
    }

    @Test fun cancellationIsNotReportedAsConnectionFailure() = runTest {
        val monitor = StatusMonitor({ awaitCancellation() }, { 0L })
        val job = backgroundScope.launch { monitor.run() }
        runCurrent()
        job.cancelAndJoin()
        assertNull(monitor.state.value.problem)
        assertFalse(monitor.state.value.refreshing)
        assertFalse(monitor.state.value.active)
    }

    @Test fun knownFieldsAreMappedWithoutInferringCycleOrCountdown() {
        val status = observed.presentation()
        assertEquals(ObservedValue.Known(ObservedProgram.DAILY_45, "2"), status.program)
        assertEquals(ObservedValue.Known(DoorPosition.CLOSED, "1"), status.door)
        assertEquals(ObservedValue.Unknown("45"), status.remainingTime)
        assertEquals(ObservedValue.Missing, status.phase)
        val other = DryerStatus(mapOf("Pr" to "1", "DoorState" to "0")).presentation()
        assertEquals(ObservedValue.Known(ObservedProgram.DAILY_PERFECT_59, "1"), other.program)
        assertEquals(ObservedValue.Known(DoorPosition.OPEN, "0"), other.door)
    }

    @Test fun unknownZeroEmptyAndMissingAreDistinctIncludingMaintenance() {
        val raw = DryerStatus(mapOf("Pr" to "15", "DoorState" to "", "PrPh" to "700", "CodiceErrore" to "0",
            "CleanFilter" to "0", "WaterTankFull" to "1", "RecipeId" to "NULL", "FutureField" to "future"))
        val status = raw.presentation()
        assertEquals(ObservedValue.Unknown("15"), status.program)
        assertEquals(ObservedValue.Unknown(""), status.door)
        assertEquals(ObservedValue.Unknown("700"), status.phase)
        assertEquals(ObservedValue.Unknown("0"), status.error)
        assertEquals(ObservedValue.Unknown("0"), status.filter)
        assertEquals(ObservedValue.Unknown("1"), status.waterTank)
        assertEquals(ObservedValue.Missing, status.remainingTime)
        assertEquals("NULL", raw["RecipeId"])
        assertEquals("future", raw["FutureField"])
    }
}
