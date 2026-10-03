package ru.bsv.candycmd.shared.control

import kotlin.test.*
import ru.bsv.candycmd.shared.protocol.DryerStatus

class ProgramOptionsTest {
    private fun observed(settings: Settings): Pair<PreparedCommand, DryerStatus> {
        val recipe = requireNotNull(settings.recipe())
        val prepared = CommandBuilder.prepare(DryerCommand.Start(settings), TEST_NOW)
        return prepared to DryerStatus(running(recipe, requireNotNull(prepared.recipeId)).fields + mapOf(
            "DryLev" to settings.effectiveDryLevel().toString(), "Time" to settings.effectiveTimeLevel().toString(),
            "Opt3" to if (settings.easyIron) "1" else "0"))
    }

    @Test fun selectedLevelsAndIronMustMatchTheObservedCycle() {
        Recipe.entries.filter { it.hasDryLevels }.forEach { recipe ->
            for (level in 1..4) for (iron in listOf(false, true).filter { !it || recipe.hasEasyIron }) {
                val settings = Settings(recipe.id, dryLevel = level, easyIron = iron)
                val (prepared, status) = observed(settings)
                assertTrue(prepared.parameters.contains("DryLev=$level&OptMsk=${if (iron) 4 else recipe.mask}"))
                assertTrue(prepared.confirmed(status))
                val target = requireNotNull(CycleTarget.from(status))
                assertEquals(level, target.dryLevel)
                assertEquals(iron, target.easyIron)
                assertNull(CommandPolicy.blocked(DryerCommand.Cancel(target), status))
                assertFalse(prepared.confirmed(DryerStatus(status.fields + ("DryLev" to (level % 4 + 1).toString()))))
                assertNotNull(CommandPolicy.blocked(DryerCommand.Cancel(target), DryerStatus(status.fields + ("Opt3" to if (iron) "0" else "1"))))
            }
        }
    }

    @Test fun timerSendsCodesAndRejectsMixedOrUnverifiedSettings() {
        for (recipe in listOf(Recipe.SYNTHETICS, Recipe.WHITES)) for (minutes in 70..220 step 10) {
            val settings = Settings(recipe.id, timeMinutes = minutes)
            val (prepared, status) = observed(settings)
            assertTrue(prepared.parameters.contains("Time=${minutes / 10 - 2}&"))
            assertFalse(prepared.parameters.contains("DryLev="))
            assertTrue(prepared.confirmed(status))
            assertEquals(minutes / 10 - 2, CycleTarget.from(status)?.timeLevel)
            assertFalse(prepared.confirmed(DryerStatus(status.fields + ("Time" to "0"))))
            assertNull(CycleTarget.from(DryerStatus(status.fields + ("DryLev" to "2"))))
            assertNull(settings.copy(dryLevel = 2).recipe())
            assertNull(settings.copy(easyIron = true).recipe())
        }
        for (minutes in listOf(-10, 0, 10, 60, 75, 230, Int.MAX_VALUE))
            assertNull(Settings("synthetics", timeMinutes = minutes).recipe())
        assertNull(Settings("jeans", dryLevel = 1).recipe())
        assertNull(Settings("sport", easyIron = true).recipe())
        assertNull(Settings("baby", dryLevel = 1).recipe())
        assertNull(Settings("synthetics", version = 1, dryLevel = 3).recipe())
    }

    @Test fun presetsKeepAllOptionsAndOldFixedPresetsRemainUsable() {
        val settings = Settings("whites", dryLevel = 3, easyIron = true)
        val encoded = PresetCodec.encode(listOf(Preset("one", "Хлопок", settings)))
        assertTrue(encoded.contains("\"version\":3"), "Older clients must see the new settings version explicitly")
        assertEquals(settings, PresetCodec.decode(encoded).single().settings)
        val old = PresetCodec.decode("""[{"id":"old","name":"Старые","settings":{"recipeId":"synthetics","version":1}}]""").single()
        assertTrue(old.usable)
        assertEquals(2, old.settings.effectiveDryLevel())
        assertEquals(1, Settings("whites", easyIron = true).effectiveDryLevel())
    }

    @Test fun incompleteControlChecksCannotEnablePauseForChangedOptions() {
        val unverified = listOf(Settings("whites", dryLevel = 3), Settings("synthetics", easyIron = true),
            Settings("synthetics", timeMinutes = 90), Settings("darks", easyIron = true))
        for (settings in unverified) {
            assertNotNull(settings.copy(delayMinutes = 15).recipe())
            val (_, status) = observed(settings)
            val target = requireNotNull(CycleTarget.from(status))
            assertFalse(target.supportsPause)
            assertNotNull(CommandPolicy.blocked(DryerCommand.Pause(target), status))
            assertNull(CommandPolicy.blocked(DryerCommand.Cancel(target), status))
        }
        for (settings in listOf(Settings("sport", dryLevel = 1), Settings("panel-shirts", dryLevel = 4, easyIron = true),
            Settings("synthetics", dryLevel = 3))) {
            assertNotNull(settings.copy(delayMinutes = 15).recipe())
            val (_, status) = observed(settings)
            assertTrue(requireNotNull(CycleTarget.from(status)).supportsPause)
        }
    }
}
