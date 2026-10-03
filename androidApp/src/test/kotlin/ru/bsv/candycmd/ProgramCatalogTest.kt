package ru.bsv.candycmd

import org.junit.Assert.*
import org.junit.Test
import ru.bsv.candycmd.shared.control.Recipe

class ProgramCatalogTest {
    @Test fun allDialCardsUseTheirOwnStandardCommandAndPanelDuration() {
        assertEquals((1..14).toList(), basicPrograms.map { requireNotNull(it.recipe).program })
        assertEquals(listOf(59, 45, 30, 20, 94, 12, 90, 70, 70, 100, 120, 129, 150, 150),
            basicPrograms.map { it.minutes })
        assertTrue(basicPrograms.all { it.recipe!!.isPanel && it.minutes == it.recipe.initialMinutes })
        assertTrue(extraPrograms.all { !it.recipe!!.isPanel })
        assertEquals(Recipe.PANEL_SHIRTS, basicPrograms.first { it.id == "shirts" }.recipe)
        assertTrue(extraPrograms.any { it.recipe == Recipe.SHIRTS })
        assertTrue(extraPrograms.any { it.recipe == Recipe.BED_LINEN && it.minutes == 180 })
    }
}
