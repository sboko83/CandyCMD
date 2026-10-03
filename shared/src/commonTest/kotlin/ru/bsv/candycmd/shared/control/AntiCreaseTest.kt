package ru.bsv.candycmd.shared.control

import kotlin.test.*
import ru.bsv.candycmd.shared.protocol.DryerStatus
import ru.bsv.candycmd.shared.monitor.ObservedCycle
import ru.bsv.candycmd.shared.monitor.ObservedValue
import ru.bsv.candycmd.shared.monitor.presentation

class AntiCreaseTest {
    private fun delayed(settings: Settings): Pair<PreparedCommand, DryerStatus> {
        val prepared = CommandBuilder.prepare(DryerCommand.Start(settings), TEST_NOW)
        return prepared to DryerStatus(running(Recipe.WHITES, requireNotNull(prepared.recipeId)).fields + mapOf(
            "StatoTD" to "5", "PrPh" to "0", "DelVal" to settings.delayMinutes.toString(),
            "DryLev" to settings.effectiveDryLevel().toString(), "Opt1" to "1", "Opt3" to "0"))
    }

    @Test fun nightCottonPreservesDrynessAndRecognizesProtection() {
        for (level in listOf(2, 4)) for (delay in listOf(30, 180)) {
            val settings = Settings("whites", dryLevel = level, delayMinutes = delay)
            val (prepared, status) = delayed(settings)
            assertContains(prepared.parameters, "DryLev=$level&OptMsk=16&")
            assertContains(prepared.parameters, "&DelMd=1&DelVl=$delay")
            assertTrue(prepared.confirmed(status))
            assertEquals(ObservedCycle.DELAYED, (status.presentation().cycle as ObservedValue.Known).value)
            val target = requireNotNull(CycleTarget.from(status))
            assertTrue(target.antiCrease)
            assertFalse(target.easyIron)
            assertNull(CommandPolicy.blocked(DryerCommand.Cancel(target), status))
            assertFalse(target.supportsPause)
            assertTrue(CommandBuilder.prepare(DryerCommand.Cancel(target), TEST_NOW).confirmed(idle()))
        }
    }

    @Test fun optionMismatchOrChangedCycleCannotConfirmOrCancel() {
        val (prepared, status) = delayed(Settings("whites", dryLevel = 4, delayMinutes = 30))
        val target = requireNotNull(CycleTarget.from(status))
        for ((field, value) in mapOf("Opt1" to "0", "Opt3" to "1", "DryLev" to "2", "RecipeId" to "other")) {
            val changed = DryerStatus(status.fields + (field to value))
            assertFalse(prepared.confirmed(changed))
            assertNotNull(CommandPolicy.blocked(DryerCommand.Cancel(target), changed))
        }
    }

    @Test fun cycleRemainsRecognizableWhenDelayEndsWithoutEnablingPause() {
        val (_, delayed) = delayed(Settings("whites", dryLevel = 4, delayMinutes = 30))
        val status = DryerStatus(delayed.fields + mapOf("StatoTD" to "2", "PrPh" to "2", "DelVal" to "0"))
        val target = requireNotNull(CycleTarget.from(status))
        assertTrue(target.antiCrease)
        assertNull(CommandPolicy.blocked(DryerCommand.Cancel(target), status))
        assertNotNull(CommandPolicy.blocked(DryerCommand.Pause(target), status))
    }

    @Test fun everyRecipeCanOptOutWithoutChangingItsBaseOptions() {
        for (recipe in Recipe.entries) {
            val on = Settings(recipe.id, delayMinutes = 180)
            assertTrue(on.usesAntiCrease())
            assertEquals(16, on.effectiveMask())
            val off = on.copy(antiCrease = false)
            assertFalse(off.usesAntiCrease())
            assertEquals(recipe.mask, off.effectiveMask())
            assertEquals(on.effectiveDryLevel(), off.effectiveDryLevel())
            assertFalse(on.copy(delayMinutes = 0).usesAntiCrease())
        }
        val iron = Settings("whites", dryLevel = 4, easyIron = true, delayMinutes = 30)
        assertEquals(20, iron.effectiveMask())
        assertEquals(4, iron.copy(antiCrease = false).effectiveMask())
        assertEquals(16, Settings("synthetics", timeMinutes = 90, delayMinutes = 30).effectiveMask())
    }

    @Test fun storedNightSettingsRetainAutomaticProtection() {
        val settings = Settings("whites", dryLevel = 4, delayMinutes = 180)
        val stored = PresetCodec.decode(PresetCodec.encode(listOf(Preset("night", "Ночь", settings)))).single().settings
        assertTrue(stored.usesAntiCrease())
        assertEquals(4, stored.effectiveDryLevel())
        assertFalse(stored.copy(delayMinutes = 0).usesAntiCrease())
    }
}
