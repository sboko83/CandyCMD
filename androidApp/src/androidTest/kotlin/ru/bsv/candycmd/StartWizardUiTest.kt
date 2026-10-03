package ru.bsv.candycmd

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.bsv.candycmd.shared.control.*
import ru.bsv.candycmd.shared.monitor.MonitorState

class StartWizardUiTest {
    @get:Rule val compose = createComposeRule()
    private val restoration by lazy { StateRestorationTester(compose) }
    private val starts = mutableListOf<Pair<Settings, String?>>()
    private var closed = 0

    private fun show(monitor: MonitorState = fresh(readyStatus()), state: CommandState = CommandState(),
                     fontScale: Float = 1f, width: Dp = Dp.Unspecified) {
        restoration.setContent {
            var draft by rememberSaveable(stateSaver = StartDraft.Saver) { mutableStateOf(StartDraft()) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                CandyTheme(darkTheme = false) {
                    Box(Modifier.width(width)) {
                        StartWizard(draft, { draft = it }, monitor, state, false, canSaveFavorite = true,
                            onClose = { closed++ }, onStart = { settings, name -> starts += settings to name })
                    }
                }
            }
        }
    }

    private fun program(id: String) = compose.onNodeWithTag("program-$id").performScrollTo().performClick()
    private fun next() = compose.onNodeWithTag("wizard-next").performClick()
    private fun wheel(tag: String, value: Float) =
        compose.onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(value) }

    @Test fun antiCreaseCheckboxDefaultsOnAndSurvivesRestoration() {
        show()
        program("white")
        compose.onNodeWithTag("dry-level-4").performClick()
        next()
        compose.onNodeWithTag("anti-crease").assertDoesNotExist()
        compose.onNodeWithTag("start-later").performClick()
        compose.onNodeWithTag("anti-crease").performScrollTo().assertIsOn()
        wheel("delay-hours", 3f)
        compose.onNodeWithTag("anti-crease").performScrollTo()
        captureScreen("anti-crease-delay")
        compose.onNodeWithTag("anti-crease").performClick().assertIsOff()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("anti-crease").performScrollTo().assertIsOff()
        next()
        compose.onNodeWithTag("start-program").performClick()
        compose.runOnIdle {
            assertEquals(false, starts.single().first.antiCrease)
            assertEquals(4, starts.single().first.effectiveDryLevel())
        }
    }

    @Test fun dailyFlowShowsFixedOptionsAndSendsOnlyOnFinalStart() {
        show()
        compose.onNodeWithTag("wizard-next").assertIsNotEnabled()
        compose.onNodeWithText("Шаг 1 из 4", substring = true).assertExists()
        basicPrograms.forEach { compose.onNodeWithTag("program-${it.id}").assertExists() }
        extraPrograms.forEach { compose.onNodeWithTag("program-${it.id}").assertExists() }
        captureScreen("wizard-program")
        program("daily59")
        compose.onNodeWithText("Шаг 2 из 4", substring = true).assertExists()
        compose.onNodeWithTag("fixed-options").assertExists()
        compose.onNodeWithTag("dry-level-1").assertDoesNotExist()
        next()
        compose.onNodeWithText("Когда начать").assertExists()
        compose.onNodeWithText("Шаг 3 из 4", substring = true).assertExists()
        captureScreen("wizard-start")
        next()
        compose.onNodeWithText("Проверьте и запустите").assertExists()
        compose.runOnIdle { assertTrue(starts.isEmpty()) }
        captureScreen("wizard-confirm")
        compose.onNodeWithTag("start-program").assertTextEquals("Начать сушку").performClick()
        compose.runOnIdle { assertEquals(listOf(Settings("daily-59") to null), starts) }
    }

    @Test fun optionsSurviveRestorationAndReachTheCommand() {
        show()
        program("white")
        compose.onNodeWithText("Опции").assertExists()
        compose.onNodeWithTag("dry-level-4").performClick()
        compose.onNodeWithText("≈180 мин на пустом барабане").assertExists()
        compose.onNodeWithTag("easy-iron").performClick()
        compose.onNodeWithTag("dry-level-1").assertIsSelected()
        captureScreen("wizard-options")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("easy-iron").assertIsOn()
        next()
        compose.onNodeWithTag("start-later").assertIsEnabled()
        next()
        compose.onNodeWithText("Лёгкая глажка", substring = true).assertExists()
        compose.onNodeWithTag("start-program").performClick()
        compose.runOnIdle { assertEquals(Settings("whites", dryLevel = 1, easyIron = true), starts.single().first) }
    }

    @Test fun largeTextWrapsDrynessLabelsOnlyBetweenWordParts() {
        show(fontScale = 1.6f, width = 360.dp)
        program("white")
        for (level in 1..4) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasAnyAncestor(hasTestTag("dry-level-$level")) and hasText("", substring = true), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            val text = layout.layoutInput.text.text
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                assertTrue("dry-level-$level breaks inside a word: '${text.substring(0, end)}|${text.substring(end)}'",
                    text[end - 1] in " \u00AD")
            }
        }
    }

    @Test fun timerStaysWithinBounds() {
        show()
        program("synthetic")
        compose.onNodeWithTag("timed-mode").performClick()
        compose.onNodeWithText("90 мин").assertExists()
        repeat(2) { compose.onNodeWithTag("duration-less").performClick() }
        compose.onNodeWithTag("duration-less").assertIsNotEnabled()
        repeat(15) { compose.onNodeWithTag("duration-more").performScrollTo().performClick() }
        compose.onNodeWithTag("duration-more").assertIsNotEnabled()
        next(); next()
        compose.onNodeWithTag("start-program").performClick()
        compose.runOnIdle { assertEquals(Settings("synthetics", timeMinutes = 220), starts.single().first) }
    }

    @Test fun delayIsHiddenUntilChosenAndStopsAt24Hours() {
        show()
        program("daily59")
        next()
        compose.onNodeWithTag("delay-hours").assertDoesNotExist()
        compose.onNodeWithTag("start-later").performClick()
        wheel("delay-hours", 4f)
        wheel("delay-minutes", 1f)
        compose.onNodeWithText("Старт в", substring = true).assertExists()
        captureScreen("wizard-delay")
        restoration.emulateSavedInstanceStateRestore()
        next()
        compose.onNodeWithTag("start-program").assertTextContains("Запустить в", substring = true).performClick()
        compose.runOnIdle { assertEquals(255, starts.last().first.delayMinutes) }
        compose.onNodeWithText("Назад").performClick()
        wheel("delay-hours", 24f)
        compose.onNodeWithTag("delay-minutes").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "0"))
        next()
        compose.onNodeWithTag("start-program").performClick()
        compose.runOnIdle { assertEquals(1440, starts.last().first.delayMinutes) }
    }

    @Test fun recipesCanBeDelayedAndBecomeFavorites() {
        show()
        program("extra-bed-linen")
        next()
        compose.onNodeWithTag("start-later").performClick()
        next()
        compose.onNodeWithTag("save-favorite").performClick()
        compose.onNodeWithTag("favorite-name").assertTextContains("Постельное бельё")
        captureScreen("wizard-favorite")
        compose.onNodeWithTag("start-program").performClick()
        compose.runOnIdle { assertEquals(Settings("bed-linen", delayMinutes = 60) to "Постельное бельё", starts.single()) }
    }

    @Test fun starTogglesFavoriteSectionWithoutSelectingProgram() {
        var favorites by mutableStateOf(emptySet<String>())
        restoration.setContent {
            var draft by rememberSaveable(stateSaver = StartDraft.Saver) { mutableStateOf(StartDraft()) }
            CandyTheme(darkTheme = false) {
                StartWizard(draft, { draft = it }, fresh(readyStatus()), CommandState(), false, true, {}, { _, _ -> },
                    favorites) { id -> favorites = if (id in favorites) favorites - id else favorites + id }
            }
        }
        compose.onNodeWithText("Избранное").assertDoesNotExist()
        compose.onNodeWithTag("program-wool-favorite").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("program-wool-favorite").assertIsOn()
        compose.onNodeWithText("Избранное").assertExists()
        compose.onNodeWithTag("favorite-program-wool").assertExists()
        compose.onNodeWithTag("favorite-program-wool").performScrollTo()
        captureScreen("wizard-favorites")
        compose.onNodeWithTag("wizard-next").assertIsNotEnabled()
        compose.onNodeWithTag("favorite-program-wool-favorite").performScrollTo().performClick()
        compose.onNodeWithTag("favorite-program-wool").assertDoesNotExist()
        compose.onNodeWithText("Избранное").assertDoesNotExist()
        compose.runOnIdle { assertTrue(favorites.isEmpty()) }
    }

    @Test fun staleOrUnconfirmedStateBlocksStart() {
        var monitor by mutableStateOf(MonitorState(readyStatus(), UI_NOW, stale = true))
        var state by mutableStateOf(CommandState())
        restoration.setContent {
            var draft by rememberSaveable(stateSaver = StartDraft.Saver) { mutableStateOf(StartDraft()) }
            CandyTheme { StartWizard(draft, { draft = it }, monitor, state, false, true, {}, { s, n -> starts += s to n }) }
        }
        program("daily59")
        next()
        next()
        compose.onNodeWithTag("start-program").assertIsNotEnabled()
        compose.onNodeWithText("Запуск доступен", substring = true).assertExists()
        monitor = fresh(readyStatus())
        compose.onNodeWithTag("start-program").assertIsEnabled()
        state = CommandState(CommandPhase.UNKNOWN)
        compose.onNodeWithTag("start-program").assertIsNotEnabled()
        monitor = fresh(runningStatus())
        state = CommandState()
        compose.onNodeWithTag("start-program").assertIsNotEnabled()
        compose.runOnIdle { assertTrue(starts.isEmpty()) }
    }

    @Test fun everyBaseSubmitsItsOwnDefaults() {
        var draft by mutableStateOf(StartDraft())
        compose.setContent {
            CandyTheme { StartWizard(draft, { draft = it }, fresh(readyStatus()), CommandState(), false, true, {}, { s, n -> starts += s to n }) }
        }
        basicPrograms.forEach { choice ->
            compose.runOnIdle { draft = StartDraft().select(choice).copy(step = StartStep.CONFIRM) }
            compose.onNodeWithTag("start-program").performClick()
            compose.runOnIdle { assertEquals(Settings(requireNotNull(choice.recipe).id), starts.last().first) }
        }
        assertEquals(basicPrograms.size, starts.size)
    }

    @Test fun backWalksStepsThenCloses() {
        show()
        program("white")
        next()
        compose.onNodeWithText("Назад").performClick()
        compose.onNodeWithText("Опции").assertExists()
        compose.onNodeWithText("Назад").performClick()
        compose.onNodeWithText("Программы панели").assertExists()
        compose.onNodeWithText("Назад").performClick()
        compose.runOnIdle { assertEquals(1, closed); assertTrue(starts.isEmpty()) }
    }
}
