package ru.bsv.candycmd.shared.control

import kotlin.test.*
import ru.bsv.candycmd.shared.monitor.*
import ru.bsv.candycmd.shared.protocol.DryerStatus

class PanelCommandsTest {
    @Test fun buildsVerifiedStandardPanelRequests() {
        val expected = mapOf(
            Recipe.DAILY_45 to "Write=1&PrNm=2&DryLev=0&OptMsk=0&RecipeId=17jAex&PrStr=Daily 45%27&StSt=1",
            Recipe.ECO_30 to "Write=1&PrNm=3&DryLev=0&OptMsk=0&RecipeId=17ZxGR&PrStr=Eco 30%27&StSt=1",
            Recipe.REFRESH to "Write=1&PrNm=4&DryLev=0&OptMsk=0&RecipeId=18Fv9b&PrStr=Refresh&StSt=1",
            Recipe.SPORT to "Write=1&PrNm=5&DryLev=3&OptMsk=0&RecipeId=19lsBv&PrStr=Sport&StSt=1",
            Recipe.RELAX_CREASES to "Write=1&PrNm=6&DryLev=0&OptMsk=0&RecipeId=1a1q3P&PrStr=Relax Creases&StSt=1",
            Recipe.SMALL_LOAD to "Write=1&PrNm=7&DryLev=3&OptMsk=0&RecipeId=1aHnw9&PrStr=Small Load&StSt=1",
            Recipe.WOOL to "Write=1&PrNm=8&DryLev=0&OptMsk=0&RecipeId=1bnkYt&PrStr=Wool&StSt=1",
            Recipe.PANEL_SHIRTS to "Write=1&PrNm=9&DryLev=2&OptMsk=1&RecipeId=1c3iqN&PrStr=Shirts&StSt=1",
            Recipe.SYNTHETICS to "Write=1&PrNm=10&DryLev=2&OptMsk=1&RecipeId=1cJfT7&PrStr=Synthetics&StSt=1",
            Recipe.DARKS to "Write=1&PrNm=11&DryLev=2&OptMsk=1&RecipeId=1dpdlr&PrStr=Darks and Coloured&StSt=1",
            Recipe.JEANS to "Write=1&PrNm=12&DryLev=4&OptMsk=0&RecipeId=1e5aNL&PrStr=Jeans&StSt=1",
            Recipe.WHITES to "Write=1&PrNm=13&DryLev=2&OptMsk=1&RecipeId=1eL8g5&PrStr=Whites&StSt=1",
            Recipe.ECO_COTTON to "Write=1&PrNm=14&DryLev=2&OptMsk=0&RecipeId=1fr5Ip&PrStr=Eco Cotton&StSt=1",
        )
        assertEquals((1..14).toList(), Recipe.entries.filter { it.isPanel }.map { it.program })
        expected.forEach { (recipe, wire) ->
            assertEquals(wire, CommandBuilder.prepare(DryerCommand.Start(Settings(recipe.id)), TEST_NOW).parameters)
            assertNotNull(Settings(recipe.id, delayMinutes = 15).recipe())
        }
    }

    @Test fun refreshRequiresObservedPhaseAndCannotCancelAnExtraOrForeignCycle() {
        val start = CommandBuilder.prepare(DryerCommand.Start(Settings("refresh")), TEST_NOW)
        val status = running(Recipe.REFRESH, requireNotNull(start.recipeId))
        assertTrue(start.confirmed(status))
        assertFalse(start.confirmed(DryerStatus(status.fields + ("PrPh" to "2"))))
        val target = requireNotNull(CycleTarget.from(status))
        assertNull(CommandPolicy.blocked(DryerCommand.Cancel(target), status))
        assertNotNull(CommandPolicy.blocked(DryerCommand.Cancel(target), DryerStatus(status.fields + ("RecipeId" to "foreign"))))
        val extra = running(Recipe.SHIRTS)
        assertFalse(CycleTarget(Recipe.PANEL_SHIRTS, requireNotNull(extra["RecipeId"])).matches(extra))
        assertEquals(Recipe.SHIRTS, CycleTarget.from(extra)?.recipe)
    }

    @Test fun allPanelProgramsShowRunningTimeOnlyInTheirObservedPhase() {
        for (recipe in Recipe.entries.filter { it.isPanel }) {
            val status = DryerStatus(running(recipe).fields + ("RemTime" to recipe.initialMinutes.toString()))
            assertEquals(ObservedValue.Known(ObservedCycle.RUNNING, "2"), status.presentation().cycle)
            assertEquals(ObservedValue.Known(recipe.initialMinutes, recipe.initialMinutes.toString()), status.presentation().remainingTime)
            val wrongPhase = DryerStatus(status.fields + ("PrPh" to "99"))
            assertTrue(wrongPhase.presentation().cycle is ObservedValue.Unknown)
            assertNotNull(CommandPolicy.blocked(DryerCommand.Cancel(requireNotNull(CycleTarget.from(status))), wrongPhase))
        }
        assertEquals(150, Recipe.WHITES.initialMinutes)
        assertEquals(180, Recipe.BED_LINEN.initialMinutes)
    }
}
