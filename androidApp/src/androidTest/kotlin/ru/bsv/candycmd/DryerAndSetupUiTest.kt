package ru.bsv.candycmd

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import ru.bsv.candycmd.shared.protocol.DryerStatus

class DryerAndSetupUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun forgetNeedsConfirmationAndDiagnosticsStayRestricted() {
        var forgets = 0
        val status = DryerStatus(runningStatus().fields + ("MacAddress" to "fixture-mac"))
        compose.setContent {
            CandyTheme {
                DryerScreen("192.0.2.42", LinkState.ONLINE, fresh(status), Appearance.SYSTEM, false,
                    DryerActions({}, {}, {}, {}, {}, { forgets++ }))
            }
        }
        compose.onNodeWithText("192.0.2.42").assertExists()
        compose.onNodeWithText("Как в системе").assertExists()
        captureScreen("dryer")
        compose.onNodeWithText("Диагностика").performClick()
        compose.onNodeWithText("RecipeId").performScrollTo().assertExists()
        compose.onNodeWithText("MacAddress").assertDoesNotExist()
        compose.onNodeWithText("fixture-mac").assertDoesNotExist()
        compose.onNodeWithTag("forget-dryer").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, forgets) }
        compose.onNodeWithTag("confirm-forget").performClick()
        compose.runOnIdle { assertEquals(1, forgets) }
    }

    @Test fun setupKeepsAddressAndCandidateSelection() {
        var address by mutableStateOf("")
        var connections = 0
        var selected = -1
        compose.setContent {
            CandyTheme(darkTheme = false) {
                ConnectionScreen(ConnectionUiState(address, "", false, true, 1, null, listOf("192.0.2.42")),
                    actions().copy(editAddress = { address = it }, connect = { connections++ }, selectCandidate = { selected = it }))
            }
        }
        captureScreen("setup")
        compose.onNodeWithText("IPv4 сушилки").assertDoesNotExist()
        compose.onNodeWithText("Сушилка · 192.0.2.42").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, selected) }
        compose.onNodeWithTag("manual-address").performScrollTo().performClick()
        compose.onNodeWithText("IPv4 сушилки").performScrollTo().performTextInput("192.0.2.42")
        compose.onNodeWithText("Подключиться").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("192.0.2.42", address); assertEquals(1, connections) }
    }

    @Test fun busySearchCanBeCancelledAndSavedDryerCanGoBack() {
        var cancelled = false
        var back = 0
        compose.setContent {
            CandyTheme {
                ConnectionScreen(ConnectionUiState("", "Поиск…", true, true, 1, null, emptyList()),
                    actions().copy(cancel = { cancelled = true }), onBack = { back++ })
            }
        }
        compose.onNodeWithTag("discover").assertIsNotEnabled()
        compose.onNodeWithText("Отменить").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, cancelled) }
        compose.onNodeWithText("Назад").assertExists()
    }

    private fun actions() = ConnectionActions({}, {}, {}, {}, {}, {}, {}, {}, {})
}
