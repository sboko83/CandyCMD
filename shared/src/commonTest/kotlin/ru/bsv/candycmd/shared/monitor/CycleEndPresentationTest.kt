package ru.bsv.candycmd.shared.monitor

import ru.bsv.candycmd.shared.protocol.DryerStatus
import kotlin.test.*

/** Raw values captured on RO4 at the end of a Bed Linen cycle. */
class CycleEndPresentationTest {
    private val finishing = mapOf("Pr" to "13", "RecipeId" to "3L5FaH", "DoorState" to "1", "StatoWiFi" to "1",
        "StatoTD" to "2", "PrPh" to "3", "DryLev" to "4", "Time" to "0", "RemTime" to "1", "DelVal" to "0",
        "Rapido" to "0", "Opt1" to "0", "Opt2" to "1", "Opt3" to "0")
    private val completed = mapOf("Pr" to "15", "RecipeId" to "NULL", "DoorState" to "1", "StatoWiFi" to "1",
        "StatoTD" to "8", "PrPh" to "0", "DryLev" to "4", "Time" to "0", "RemTime" to "0", "DelVal" to "0")

    private fun present(base: Map<String, String>, vararg changes: Pair<String, String>) =
        DryerStatus(base + changes).presentation()

    @Test fun mapsFinalPhaseWithoutTreatingStuckRemainingTimeAsCountdown() {
        val state = present(finishing)
        assertEquals(ObservedValue.Known(ObservedCycle.FINISHING, "2"), state.cycle)
        assertEquals(ObservedValue.Unknown("1"), state.remainingTime)
        assertIs<ObservedValue.Unknown>(present(finishing, "StatoTD" to "3").cycle)
        assertIs<ObservedValue.Unknown>(present(finishing, "DelVal" to "30").cycle)
        assertEquals(ObservedValue.Known(ObservedCycle.RUNNING, "2"), present(finishing, "Pr" to "4").cycle)
    }

    @Test fun mapsDryingProgramCompletionReportedAsPanelProgram() {
        val state = present(completed)
        assertEquals(ObservedValue.Known(ObservedCycle.COMPLETED, "8"), state.cycle)
        assertEquals(ObservedValue.Known(0, "0"), state.remainingTime)
        assertIs<ObservedValue.Unknown>(present(completed, "RemTime" to "1").cycle)
        assertIs<ObservedValue.Unknown>(present(completed, "PrPh" to "3").cycle)
    }
}
