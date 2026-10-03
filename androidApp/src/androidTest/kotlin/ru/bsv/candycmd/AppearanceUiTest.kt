package ru.bsv.candycmd

import ru.bsv.candycmd.shared.control.CommandState

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import ru.bsv.candycmd.shared.monitor.MonitorState
import ru.bsv.candycmd.shared.protocol.DryerStatus
import java.io.File

class AppearanceUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun appearancePersistsAndUnknownPreferenceUsesSystem() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            assertEquals(Appearance.SYSTEM, AppearanceStore(context).read())
            Appearance.entries.forEach {
                AppearanceStore(context).write(it)
                assertEquals(it, AppearanceStore(context).read())
            }
            preferences.edit().putString("theme", "unknown").commit()
            assertEquals(Appearance.SYSTEM, AppearanceStore(context).read())
        } finally { preferences.edit().clear().commit() }
    }

    @Test fun systemChangesAndManualOverridesKeepTheStartDraft() {
        var night by mutableStateOf(false)
        var appearance by mutableStateOf(Appearance.SYSTEM)
        var dialog by mutableStateOf(false)
        var background = Color.Unspecified
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            CompositionLocalProvider(LocalConfiguration provides configuration) {
                CandyTheme(darkTheme = appearance.isDark(isSystemInDarkTheme())) {
                    val current = MaterialTheme.colorScheme.background
                    SideEffect { background = current }
                    var draft by rememberSaveable(stateSaver = StartDraft.Saver) { mutableStateOf(StartDraft()) }
                    StartWizard(draft, { draft = it }, fresh(readyStatus()), CommandState(), false, true, {},
                        { _, _ -> error("Appearance changes must never send a command") })
                    if (dialog) AppearanceDialog(appearance, { appearance = it }, { dialog = false })
                }
            }
        }
        var light = Color.Unspecified
        compose.runOnIdle { light = background }
        compose.onNodeWithTag("program-daily59").performScrollTo().performClick()
        compose.onNodeWithTag("wizard-next").performClick()
        compose.onNodeWithTag("start-later").performClick()
        compose.onNodeWithTag("delay-hours").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(3f) }
        compose.runOnIdle { night = true }
        compose.runOnIdle { assertNotEquals(light, background) }
        compose.onNodeWithTag("delay-hours").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "3"))
        capture("wizard-delay-dark")
        compose.runOnIdle { dialog = true }
        compose.onNodeWithText("Светлая").performClick()
        compose.runOnIdle { assertEquals(light, background) }
        compose.onNodeWithText("Тёмная").performClick()
        compose.runOnIdle { night = false }
        compose.runOnIdle { assertNotEquals(light, background) }
        compose.onNodeWithText("Как в системе").performClick()
        compose.onNodeWithText("Готово").performClick()
        compose.runOnIdle { assertEquals(light, background) }
        compose.onNodeWithTag("delay-hours").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "3"))
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        // Dialog window transitions run outside the Compose test clock.
        android.os.SystemClock.sleep(500)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ui-review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
