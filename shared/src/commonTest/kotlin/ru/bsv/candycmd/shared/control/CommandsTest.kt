package ru.bsv.candycmd.shared.control

import kotlin.test.*
import ru.bsv.candycmd.shared.protocol.DryerStatus

internal const val TEST_NOW = 1_790_845_200_000L // 2026-10-01T09:00Z
internal fun idle() = DryerStatus(mapOf("StatoWiFi" to "1", "DoorState" to "1", "CodiceErrore" to "0",
    "StatoTD" to "1", "Pr" to "15", "PrPh" to "0", "DelVal" to "0"))
internal fun running(recipe: Recipe = Recipe.DAILY_59,
    id: String = requireNotNull(CommandBuilder.prepare(DryerCommand.Start(Settings(recipe.id)), TEST_NOW).recipeId), cycle: String = "2") =
    DryerStatus(idle().fields + mapOf("StatoTD" to cycle, "Pr" to recipe.program.toString(), "PrPh" to recipe.runningPhase.toString(),
        "DryLev" to recipe.dryLevel.toString(), "Time" to (recipe.timeLevel ?: 0).toString(),
        "Rapido" to "0", "RecipeId" to id, "Opt1" to "0", "Opt2" to recipe.observedOption2.toString(), "Opt3" to "0"))

class CommandsTest {
    @Test fun buildsExactObservedRequests() {
        val expected = mapOf(
            Recipe.DAILY_59 to "Write=1&PrNm=1&DryLev=2&OptMsk=0&RecipeId=16DCMd&PrStr=Daily Perfect 59%27&StSt=1",
            Recipe.BED_LINEN to "Write=1&DryLev=4&PrNm=13&OptMsk=1&RecipeId=3L5EMB&PrStr=Bed Linen&StSt=1",
            Recipe.BABY to "Write=1&DryLev=3&PrNm=10&OptMsk=1&RecipeId=3KpHkh&PrStr=Baby&StSt=1",
            Recipe.SHIRTS to "Write=1&DryLev=2&PrNm=9&OptMsk=1&RecipeId=3Qvkrb&PrStr=Shirts&StSt=1",
            Recipe.GYM_FIT to "Write=1&DryLev=2&PrNm=10&OptMsk=1&RecipeId=3DE6L3&PrStr=Gym fit - Fitness&StSt=1",
            Recipe.TECHNICAL_FABRICS to "Write=1&DryLev=2&PrNm=10&OptMsk=1&RecipeId=3MrzHf&PrStr=Technical Fabrics&StSt=1",
            Recipe.BACKPACKS to "Write=1&Time=7&PrNm=10&OptMsk=1&RecipeId=3CibQp&PrStr=Backpacks&StSt=1",
            Recipe.DUVET to "Write=1&DryLev=4&PrNm=12&OptMsk=0&RecipeId=3H1U2F&PrStr=Duvet&StSt=1",
            Recipe.CUDDLY_TOYS to "Write=1&PrNm=8&OptMsk=0&RecipeId=3RbhTv&PrStr=Cuddly Toys&StSt=1",
            Recipe.AIR_REFRESH to "Write=1&Rapido=1&PrNm=3&OptMsk=0&RecipeId=3FFZ81&PrStr=Air Refresh&StSt=1",
        )
        expected.forEach { (recipe, parameters) ->
            assertEquals(parameters, CommandBuilder.prepare(DryerCommand.Start(Settings(recipe.id)), TEST_NOW).parameters)
        }
        val delayed = CommandBuilder.prepare(DryerCommand.Start(Settings("daily-59", delayMinutes = 60)), TEST_NOW)
        assertTrue(delayed.parameters.contains("RecipeId=16DCNb"))
        assertTrue(delayed.parameters.endsWith("&StSt=1&DelMd=1&DelVl=60"))
        assertEquals("16DCMH", CommandBuilder.prepare(DryerCommand.Start(Settings("daily-59", delayMinutes = 30)), TEST_NOW).recipeId)
    }

    @Test fun settingsAreClosedAndVersionsAreRevalidated() {
        for (recipe in Recipe.entries) for (delay in listOf(-1, 0, 1, 15, 29, 30, 31, 45, 60, 61, 75, 1425, 1440, 1455)) {
            val settings = Settings(recipe.id, delayMinutes = delay)
            val valid = delay in 0..1440 && delay % 15 == 0
            assertEquals(valid, settings.recipe() != null)
            if (!valid) assertFailsWith<IllegalArgumentException> { CommandBuilder.prepare(DryerCommand.Start(settings), TEST_NOW) }
        }
        assertNull(Settings("unknown").recipe())
        assertNull(Settings("daily-59", version = 4).recipe())
        assertFailsWith<IllegalArgumentException> { CommandBuilder.prepare(DryerCommand.Start(Settings("daily-59")), 0) }
    }

    @Test fun startupRequiresEveryObservedReadinessField() {
        val command = DryerCommand.Start(Settings("daily-59"))
        assertNull(CommandPolicy.blocked(command, idle()))
        idle().fields.keys.forEach { field ->
            assertNotNull(CommandPolicy.blocked(command, DryerStatus(idle().fields - field)), field)
            assertNotNull(CommandPolicy.blocked(command, DryerStatus(idle().fields + (field to "99"))), field)
        }
        assertNotNull(CommandPolicy.blocked(command, running()))
    }

    @Test fun activeCommandsAreBoundToRecipeAndCycle() {
        Recipe.entries.forEach { recipe ->
            val status = running(recipe)
            val target = requireNotNull(CycleTarget.from(status))
            assertNull(CommandPolicy.blocked(DryerCommand.Cancel(target), status))
            assertEquals(recipe.supportsPause, CommandPolicy.blocked(DryerCommand.Pause(target), status) == null)
            assertNotNull(CommandPolicy.blocked(DryerCommand.Resume(target), status))
            assertNotNull(CommandPolicy.blocked(DryerCommand.Cancel(target), DryerStatus(status.fields + ("RecipeId" to "other"))))
            val paused = running(recipe, cycle = "3")
            assertEquals(recipe.supportsPause, CommandPolicy.blocked(DryerCommand.Resume(target), paused) == null)
            assertNull(CommandPolicy.blocked(DryerCommand.Cancel(target), paused))
        }
        val target = requireNotNull(CycleTarget.from(running()))
        val delay = DryerStatus(running().fields + mapOf("StatoTD" to "5", "PrPh" to "0", "DelVal" to "30"))
        assertNull(CommandPolicy.blocked(DryerCommand.Cancel(target), delay))
        assertNotNull(CommandPolicy.blocked(DryerCommand.Pause(target), delay))
        assertEquals("Write=1&SetPauseDryMgr=1", CommandBuilder.prepare(DryerCommand.Pause(target), TEST_NOW).parameters)
        assertEquals("Write=1&SetRestartDryMgr=1", CommandBuilder.prepare(DryerCommand.Resume(target), TEST_NOW).parameters)
        assertEquals("Write=1&StSt=0&PrNm=1", CommandBuilder.prepare(DryerCommand.Cancel(target), TEST_NOW).parameters)
    }

    @Test fun confirmationRequiresMatchingSettingsAndMachineDelay() {
        for (recipe in Recipe.entries) {
            val prepared = CommandBuilder.prepare(DryerCommand.Start(Settings(recipe.id)), TEST_NOW)
            val status = running(recipe, requireNotNull(prepared.recipeId))
            assertTrue(prepared.confirmed(status))
            for (field in listOf("Pr", "RecipeId", "DryLev", "StatoTD", "PrPh", "DelVal", "CodiceErrore")) {
                assertFalse(prepared.confirmed(DryerStatus(status.fields + (field to "99"))), field)
            }
            if (recipe != Recipe.DAILY_59) {
                for (field in listOf("Time", "Rapido", "Opt1", "Opt2")) {
                    assertFalse(prepared.confirmed(DryerStatus(status.fields - field)), field)
                    assertFalse(prepared.confirmed(DryerStatus(status.fields + (field to "99"))), field)
                }
            }
        }
        val prepared = CommandBuilder.prepare(DryerCommand.Start(Settings("daily-59", delayMinutes = 30)), TEST_NOW)
        val status = running(id = requireNotNull(prepared.recipeId))
        assertFalse(prepared.confirmed(status))
        val delay = DryerStatus(status.fields + mapOf("StatoTD" to "5", "PrPh" to "0", "DelVal" to "30", "Opt1" to "1"))
        assertTrue(prepared.confirmed(delay))
        assertFalse(prepared.confirmed(DryerStatus(delay.fields + ("DelVal" to "60"))))
    }

    @Test fun recipesSharingABaseAreIdentifiedByTheirOwnCatalogPrefix() {
        for (recipe in listOf(Recipe.BABY, Recipe.GYM_FIT, Recipe.TECHNICAL_FABRICS, Recipe.BACKPACKS)) {
            val status = running(recipe)
            assertEquals(recipe, CycleTarget.from(status)?.recipe)
            for (other in Recipe.entries.filter { it != recipe }) {
                assertFalse(CycleTarget(other, requireNotNull(status["RecipeId"])).matches(status))
            }
            for (id in listOf("NULL", "!", "1234567", "3K0", "000000")) {
                assertNull(CycleTarget.from(DryerStatus(status.fields + ("RecipeId" to id))))
            }
        }
        assertFalse(CommandBuilder.prepare(DryerCommand.Start(Settings("backpacks")), TEST_NOW)
            .parameters.contains("DryLev="))
    }

    @Test fun encryptedRequestContainsOnlyHexAndRedactsToString() {
        val prepared = CommandBuilder.prepare(DryerCommand.Start(Settings("daily-59")), TEST_NOW)
        // Synthetic fixture key, never a device key.
        val key = "SYNTHETICKEY0001".encodeToByteArray()
        val hex = prepared.encryptedPath(key).substringAfter("data=")
        val plain = hex.chunked(2).mapIndexed { index, pair -> (pair.toInt(16) xor key[index % 16].toInt()).toByte() }.toByteArray().decodeToString()
        assertEquals(prepared.parameters, plain)
        assertEquals("PreparedCommand(<redacted>)", prepared.toString())
        assertFailsWith<IllegalArgumentException> { prepared.encryptedPath(byteArrayOf()) }
    }

    @Test fun presetsRoundTripOnlySettingsAndKeepUnsupportedEntriesDisabled() {
        val presets = listOf(Preset("one", "Мой хлопок", Settings("bed-linen")),
            Preset("two", "Позже", Settings("daily-59", delayMinutes = 30)),
            Preset("old", "Старая версия", Settings("daily-59", version = 99)))
        val encoded = PresetCodec.encode(presets)
        assertEquals(presets, PresetCodec.decode(encoded))
        assertFalse(PresetCodec.decode(encoded).last().usable)
        for (field in listOf("key", "url", "RecipeId", "timestamp")) assertFalse(encoded.contains(field))
        assertFailsWith<IllegalArgumentException> { PresetCodec.encode(presets + presets.first()) }
        assertFailsWith<IllegalArgumentException> { PresetCodec.encode(listOf(Preset("id", " ", Settings("daily-59")))) }
        assertFails { PresetCodec.decode("broken") }
    }
}
