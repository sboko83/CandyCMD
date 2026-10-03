package ru.bsv.candycmd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import ru.bsv.candycmd.shared.connection.ConnectionProblem
import ru.bsv.candycmd.shared.control.*
import ru.bsv.candycmd.shared.monitor.MonitorState

class HomeUiTest {
    @get:Rule val compose = createComposeRule()
    private val commands = mutableListOf<DryerCommand>()
    private var chosen = 0
    private val favorites = mutableListOf<Preset>()
    private val renamed = mutableListOf<String>()

    private fun show(monitor: MonitorState, state: CommandState = CommandState(), presets: List<Preset> = emptyList(),
                     large: Boolean = false) {
        val actions = HomeActions(choose = { chosen++ }, favorite = { favorites += it }, rename = { _, name -> renamed += name },
            delete = {}, command = { commands += it }, check = {}, wifiSettings = {}, requestPermission = {})
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large) 1.6f else 1f)) {
                CandyTheme(darkTheme = large) {
                    Box(if (large) Modifier.width(320.dp) else Modifier) {
                        val link = monitor.link(connecting = false)
                        ScreenFrame(header = { HomeHeader(link) {} }) {
                            HomeContent(monitor, state, false, link, presets, "", actions)
                        }
                    }
                }
            }
        }
    }

    @Test fun sleepingDryerIsCalmAndBlocksStart() {
        show(MonitorState(runningStatus(), UI_NOW, ConnectionProblem.UNAVAILABLE, active = true, stale = true))
        compose.onNodeWithText("Сушилка спит или выключена").assertExists()
        compose.onNodeWithText("Спит").assertExists()
        compose.onNodeWithText("При последнем ответе", substring = true).assertExists()
        assertTrue(compose.onAllNodesWithText("IP", substring = true).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("choose-program").assertIsNotEnabled()
        assertTrue(compose.onAllNodesWithText("Отменить").fetchSemanticsNodes().isEmpty())
        captureScreen("home-sleep")
    }

    @Test fun readyOffersProgramWithoutManualRefresh() {
        show(fresh(readyStatus()))
        compose.onNodeWithText("Готова к сушке").assertExists()
        compose.onNodeWithText("На связи").assertExists()
        compose.onNodeWithContentDescription("Обновить").assertDoesNotExist()
        compose.onNodeWithTag("choose-program").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, chosen); assertTrue(commands.isEmpty()) }
        captureScreen("home-ready")
    }

    @Test fun runningShowsTimeAndCancelAsksFirst() {
        val status = runningStatus(remaining = "102")
        show(fresh(status))
        compose.onNodeWithText("1 ч 42 мин").assertExists()
        compose.onNodeWithText("Закончит ≈ в", substring = true).assertExists()
        compose.onNodeWithTag("choose-program").assertDoesNotExist()
        captureScreen("home-running")
        compose.onNodeWithText("Отменить").performClick()
        compose.onNodeWithText("Отменить сушку?").assertExists()
        compose.runOnIdle { assertTrue(commands.isEmpty()) }
        compose.onNodeWithTag("confirm-cancel").performClick()
        compose.runOnIdle { assertEquals(listOf(DryerCommand.Cancel(requireNotNull(CycleTarget.from(status)))), commands) }
    }

    @Test fun unconfirmedCommandIsQuietAndCancelStaysAvailable() {
        val status = delayedStatus(12)
        show(fresh(status), CommandState(CommandPhase.UNKNOWN))
        compose.onNodeWithText("Старт в", substring = true).assertExists()
        compose.onNodeWithTag("command-pending").assertExists()
        compose.onNodeWithText("Результат неизвестен", substring = true).assertDoesNotExist()
        captureScreen("home-delayed-pending")
        compose.onNodeWithText("Отменить запуск").assertIsEnabled().performClick()
        compose.onNodeWithTag("confirm-cancel").performClick()
        compose.runOnIdle { assertEquals(1, commands.size); assertTrue(commands.single() is DryerCommand.Cancel) }
    }

    @Test fun favoritesOpenAndRenameWithoutSending() {
        val preset = Preset("p1", "Постельное", Settings("bed-linen"))
        show(fresh(readyStatus()), presets = listOf(preset))
        compose.onNodeWithText("Избранное").assertExists()
        captureScreen("home-favorites")
        compose.onNodeWithText("Постельное").performClick()
        compose.onNodeWithContentDescription("Действия: Постельное").performClick()
        compose.onNodeWithText("Переименовать").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Бельё")
        compose.onNodeWithText("Готово").performClick()
        compose.runOnIdle {
            assertEquals(listOf(preset), favorites)
            assertEquals(listOf("Бельё"), renamed)
            assertTrue(commands.isEmpty())
        }
    }

    @Test fun narrowLargeTextKeepsCycleControlsReachable() {
        show(fresh(runningStatus(Recipe.SYNTHETICS)), large = true)
        compose.onNodeWithTag("command-Pause").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("command-Cancel").performScrollTo().assertIsDisplayed()
        captureScreen("home-large-dark")
    }
}
