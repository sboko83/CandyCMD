package ru.bsv.candycmd.shared.control

import kotlin.test.*
import ru.bsv.candycmd.shared.monitor.*
import ru.bsv.candycmd.shared.protocol.DryerStatus

class ControlPresentationTest {
    @Test fun dailyRunningAndPauseShowObservedTimeButDelayNeverUsesItAsCountdown() {
        for (cycle in listOf("2", "3")) {
            val state = DryerStatus(running(cycle = cycle).fields + ("RemTime" to "57")).presentation()
            assertEquals(ObservedValue.Known(57, "57"), state.remainingTime)
            assertEquals(if (cycle == "2") ObservedCycle.RUNNING else ObservedCycle.PAUSED,
                (state.cycle as ObservedValue.Known).value)
        }
        val delayed = DryerStatus(running().fields + mapOf("StatoTD" to "5", "PrPh" to "0", "DelVal" to "30", "RemTime" to "59")).presentation()
        assertEquals(ObservedValue.Known(ObservedCycle.DELAYED, "5"), delayed.cycle)
        assertEquals(ObservedValue.Unknown("59"), delayed.remainingTime)
    }

    @Test fun observedBasesShowMinutesWithoutInventingPause() {
        for (recipe in listOf(Recipe.SHIRTS, Recipe.BED_LINEN)) {
            val state = running(recipe).presentation()
            assertTrue(state.program is ObservedValue.Known)
            assertEquals(ObservedValue.Known(ObservedCycle.RUNNING, "2"), state.cycle)
            assertEquals(ObservedValue.Unknown("3"), running(recipe, cycle = "3").presentation().cycle)
            assertEquals(ObservedValue.Known(70, "70"), DryerStatus(running(recipe).fields + ("RemTime" to "70")).presentation().remainingTime)
        }
    }

    @Test fun newObservedBasesShowMinutesOnlyWhileRunningWithoutDelay() {
        val recipes = listOf(Recipe.BABY, Recipe.GYM_FIT, Recipe.TECHNICAL_FABRICS, Recipe.BACKPACKS,
            Recipe.DUVET, Recipe.CUDDLY_TOYS, Recipe.AIR_REFRESH)
        for (recipe in recipes) {
            val status = DryerStatus(running(recipe).fields + ("RemTime" to "30"))
            assertTrue(status.presentation().program is ObservedValue.Known)
            assertEquals(ObservedValue.Known(ObservedCycle.RUNNING, "2"), status.presentation().cycle)
            assertEquals(ObservedValue.Known(30, "30"), status.presentation().remainingTime)
            for (change in listOf(mapOf("StatoTD" to "3"), mapOf("DelVal" to "30"), mapOf("PrPh" to "0"))) {
                val changed = DryerStatus(status.fields + change).presentation()
                assertTrue(changed.cycle is ObservedValue.Unknown)
                assertEquals(ObservedValue.Unknown("30"), changed.remainingTime)
            }
        }
    }
}
