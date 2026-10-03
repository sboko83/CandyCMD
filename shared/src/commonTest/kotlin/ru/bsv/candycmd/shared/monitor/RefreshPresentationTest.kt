package ru.bsv.candycmd.shared.monitor

import ru.bsv.candycmd.shared.protocol.DryerStatus
import kotlin.test.*

class RefreshPresentationTest {
    @Test fun mapsRemoteControlOnlyForObservedCombinationWithoutInventingProgramOrTime() {
        val ready = status(program = "15", cycle = "1", phase = "0", time = "150")
        assertEquals(ObservedValue.Known(ObservedCycle.REMOTE_CONTROL, "1"), ready.cycle)
        assertEquals(ObservedValue.Unknown("15"), ready.program)
        assertEquals(ObservedValue.Unknown("150"), ready.remainingTime)
        assertIs<ObservedValue.Unknown>(status(program = "4", cycle = "1", phase = "0").cycle)
        assertIs<ObservedValue.Unknown>(status(program = "15", cycle = "2", phase = "0").cycle)
        assertIs<ObservedValue.Unknown>(status(program = "15", cycle = "1", phase = "3").cycle)
        assertEquals(ObservedValue.Unknown("1"), DryerStatus(mapOf("Pr" to "15", "StatoTD" to "1")).presentation().cycle)
    }

    private fun status(program: String = "4", cycle: String = "2", phase: String = "3", time: String? = "20") =
        DryerStatus(buildMap {
            put("Pr", program); put("StatoTD", cycle); put("PrPh", phase)
            time?.let { put("RemTime", it) }
        }).presentation()

    @Test fun mapsObservedRefreshRunningAndPausedValues() {
        val running = status()
        assertEquals(ObservedValue.Known(ObservedProgram.REFRESH, "4"), running.program)
        assertEquals(ObservedValue.Known(ObservedCycle.RUNNING, "2"), running.cycle)
        assertEquals(ObservedValue.Known(20, "20"), running.remainingTime)
        assertEquals(ObservedValue.Unknown("3"), running.phase)
        val paused = status(cycle = "3", time = "17")
        assertEquals(ObservedValue.Known(ObservedCycle.PAUSED, "3"), paused.cycle)
        assertEquals(ObservedValue.Known(17, "17"), paused.remainingTime)
    }

    @Test fun mapsObservedCompletionWithoutExtrapolatingToOtherCombinations() {
        val completed = status(cycle = "8", phase = "0", time = "0")
        assertEquals(ObservedValue.Known(ObservedCycle.COMPLETED, "8"), completed.cycle)
        assertEquals(ObservedValue.Known(0, "0"), completed.remainingTime)
        for (value in listOf(status(program = "1", cycle = "8", phase = "0", time = "0"),
            status(cycle = "8", time = "0"), status(cycle = "8", phase = "0", time = "1"),
            status(cycle = "8", phase = "0", time = null))) {
            assertTrue(value.cycle is ObservedValue.Unknown)
            assertFalse(value.remainingTime is ObservedValue.Known)
        }
    }

    @Test fun mapsFilterIndicatorWithoutInferringUnobservedValues() {
        assertEquals(ObservedValue.Known(FilterNotice.CLEAN_FILTER, "1"),
            DryerStatus(mapOf("CleanFilter" to "1")).presentation().filter)
        for (raw in listOf("0", "2", "", "NULL")) {
            assertEquals(ObservedValue.Unknown(raw), DryerStatus(mapOf("CleanFilter" to raw)).presentation().filter)
        }
        assertEquals(ObservedValue.Missing, DryerStatus(emptyMap()).presentation().filter)
    }

    @Test fun doesNotExtrapolateToOtherProgramsOrPhases() {
        for (value in listOf(status(program = "1"), status(program = "15"), status(phase = "0"),
            status(cycle = "1"), status(cycle = "4"))) {
            assertTrue(value.cycle is ObservedValue.Unknown)
            assertEquals(ObservedValue.Unknown("20"), value.remainingTime)
        }
        assertEquals(ObservedValue.Known(ObservedCycle.RUNNING, "2"), status(time = "0").cycle)
    }

    @Test fun preservesMissingEmptyInvalidAndOverflowingTime() {
        assertEquals(ObservedValue.Missing, status(time = null).remainingTime)
        for (raw in listOf("", "NULL", "-1", "+17", "17.5", " 17", "999999999999999999")) {
            assertEquals(ObservedValue.Unknown(raw), status(time = raw).remainingTime)
        }
    }
}
