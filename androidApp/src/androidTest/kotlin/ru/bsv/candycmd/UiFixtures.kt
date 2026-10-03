package ru.bsv.candycmd

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import ru.bsv.candycmd.shared.control.CommandBuilder
import ru.bsv.candycmd.shared.control.DryerCommand
import ru.bsv.candycmd.shared.control.Recipe
import ru.bsv.candycmd.shared.control.Settings
import ru.bsv.candycmd.shared.monitor.MonitorState
import ru.bsv.candycmd.shared.protocol.DryerStatus
import java.io.File

internal const val UI_NOW = 1_790_845_200_000L

internal fun readyStatus() = DryerStatus(mapOf("Pr" to "15", "StatoTD" to "1", "PrPh" to "0", "DelVal" to "0",
    "DoorState" to "1", "StatoWiFi" to "1", "CodiceErrore" to "0"))

internal fun runningStatus(recipe: Recipe = Recipe.DAILY_59, cycle: String = "2", remaining: String = "42") =
    DryerStatus(readyStatus().fields + mapOf("StatoTD" to cycle, "Pr" to recipe.program.toString(),
        "PrPh" to recipe.runningPhase.toString(), "DryLev" to recipe.dryLevel.toString(), "Time" to (recipe.timeLevel ?: 0).toString(),
        "Rapido" to "0", "Opt1" to "0", "Opt2" to recipe.observedOption2.toString(), "Opt3" to "0", "RemTime" to remaining,
        "RecipeId" to requireNotNull(CommandBuilder.prepare(DryerCommand.Start(Settings(recipe.id)), UI_NOW).recipeId)))

internal fun delayedStatus(minutes: Int, recipe: Recipe = Recipe.DAILY_59) =
    DryerStatus(runningStatus(recipe).fields + mapOf("StatoTD" to "5", "PrPh" to "0", "DelVal" to minutes.toString(), "RemTime" to "0"))

internal fun fresh(status: DryerStatus) = MonitorState(status, UI_NOW, stale = false, active = true)

/** Saves the device frame for design review; files land in the app cache under ui-review. */
internal fun captureScreen(name: String) {
    // Dialog window transitions run on wall time, outside the Compose test clock.
    android.os.SystemClock.sleep(500)
    val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ui-review").apply { mkdirs() }
    File(directory, "$name.png").outputStream().use {
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
}
