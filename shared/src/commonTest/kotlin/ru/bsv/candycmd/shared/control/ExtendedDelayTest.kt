package ru.bsv.candycmd.shared.control

import kotlin.test.*
import ru.bsv.candycmd.shared.monitor.*
import ru.bsv.candycmd.shared.protocol.DryerStatus

class ExtendedDelayTest {
    @Test fun everyRecipeKeepsItsIdentityWhenScheduledAndCanCancel() {
        for (recipe in Recipe.entries) for (minutes in listOf(15, 75, 1425, 1440)) {
            val command = DryerCommand.Start(Settings(recipe.id, delayMinutes = minutes))
            assertNull(CommandPolicy.blocked(command, idle()))
            val prepared = CommandBuilder.prepare(command, TEST_NOW)
            assertTrue(prepared.parameters.endsWith("&StSt=1&DelMd=1&DelVl=$minutes"))
            val status = DryerStatus(running(recipe, requireNotNull(prepared.recipeId)).fields +
                mapOf("StatoTD" to "5", "PrPh" to "0", "DelVal" to minutes.toString(),
                    "Opt1" to "1"))
            assertTrue(prepared.confirmed(status))
            assertEquals(recipe, status.presentation().recipe)
            assertEquals(ObservedValue.Known(ObservedCycle.DELAYED, "5"), status.presentation().cycle)
            val target = requireNotNull(CycleTarget.from(status))
            assertNull(CommandPolicy.blocked(DryerCommand.Cancel(target), status))
            assertNotNull(CommandPolicy.blocked(DryerCommand.Pause(target), status))
            val countdown = DryerStatus(status.fields + ("DelVal" to (minutes - 1).toString()))
            assertNull(CommandPolicy.blocked(DryerCommand.Cancel(target), countdown))
            assertEquals(recipe, running(recipe, requireNotNull(prepared.recipeId)).presentation().recipe)
        }
    }

    @Test fun unknownOrMismatchedRecipeDoesNotRenameTheObservedBase() {
        val status = running(Recipe.BABY)
        assertEquals(Recipe.BABY, status.presentation().recipe)
        for (change in listOf("RecipeId" to "NULL", "RecipeId" to "invalid", "DryLev" to "4", "Pr" to "13")) {
            assertNull(DryerStatus(status.fields + change).presentation().recipe)
        }
    }
}
