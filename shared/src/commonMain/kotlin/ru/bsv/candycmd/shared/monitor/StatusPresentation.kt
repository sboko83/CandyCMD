package ru.bsv.candycmd.shared.monitor

import ru.bsv.candycmd.shared.protocol.DryerStatus
import ru.bsv.candycmd.shared.control.CycleTarget
import ru.bsv.candycmd.shared.control.Recipe
import ru.bsv.candycmd.shared.control.Settings

/** Unknown and missing values must remain distinct in every UI. */
sealed interface ObservedValue<out T> {
    data class Known<T>(val value: T, val raw: String) : ObservedValue<T>
    data class Unknown(val raw: String) : ObservedValue<Nothing>
    data object Missing : ObservedValue<Nothing>
}

enum class ObservedProgram { DAILY_PERFECT_59, DAILY_45, REFRESH, SHIRTS, SYNTHETICS, WHITES, ECO_30, WOOL, JEANS, SPORT, RELAX_CREASES, SMALL_LOAD, DARKS, ECO_COTTON }
enum class DoorPosition { OPEN, CLOSED }
enum class ObservedCycle { RUNNING, FINISHING, PAUSED, COMPLETED, REMOTE_CONTROL, DELAYED }
enum class FilterNotice { CLEAN_FILTER }

data class StatusPresentation(
    val program: ObservedValue<ObservedProgram>,
    val door: ObservedValue<DoorPosition>,
    val phase: ObservedValue<Nothing>,
    val cycle: ObservedValue<ObservedCycle>,
    val remainingTime: ObservedValue<Int>,
    val error: ObservedValue<Nothing>,
    val filter: ObservedValue<FilterNotice>,
    val waterTank: ObservedValue<Nothing>,
    val recipe: Recipe? = null,
)

/** Physical phases and unobserved combinations remain unknown. */
fun DryerStatus.presentation() = StatusPresentation(
    program = observed("Pr", mapOf("1" to ObservedProgram.DAILY_PERFECT_59, "2" to ObservedProgram.DAILY_45,
        "4" to ObservedProgram.REFRESH, "9" to ObservedProgram.SHIRTS,
        "10" to ObservedProgram.SYNTHETICS, "13" to ObservedProgram.WHITES,
        "3" to ObservedProgram.ECO_30, "8" to ObservedProgram.WOOL, "12" to ObservedProgram.JEANS,
        "5" to ObservedProgram.SPORT, "6" to ObservedProgram.RELAX_CREASES,
        "7" to ObservedProgram.SMALL_LOAD, "11" to ObservedProgram.DARKS, "14" to ObservedProgram.ECO_COTTON)),
    door = observed("DoorState", mapOf("0" to DoorPosition.OPEN, "1" to DoorPosition.CLOSED)),
    phase = unknown("PrPh"),
    cycle = if (isObservedCompletion()) ObservedValue.Known(ObservedCycle.COMPLETED, "8")
        else if (this["Pr"] == "15" && this["StatoTD"] == "1" && this["PrPh"] == "0")
            ObservedValue.Known(ObservedCycle.REMOTE_CONTROL, "1")
        else if ((this["Pr"] == "1" || CycleTarget.from(this) != null) && this["PrPh"] == "0" && this["StatoTD"] == "5" &&
            this["DelVal"]?.toIntOrNull() in 1..Settings.MAX_DELAY_MINUTES) ObservedValue.Known(ObservedCycle.DELAYED, "5")
        else if (this["Pr"] in listOf("2", "3", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14") && this["PrPh"] == "2" && this["DelVal"] == "0")
            observed("StatoTD", if (CycleTarget.from(this)?.supportsPause == true)
                mapOf("2" to ObservedCycle.RUNNING, "3" to ObservedCycle.PAUSED) else mapOf("2" to ObservedCycle.RUNNING))
        // Final phase observed on Bed Linen: RemTime stays at 1 for minutes, so it is not a countdown.
        else if (this["Pr"] in DRYING_PROGRAMS && this["PrPh"] == "3" && this["StatoTD"] == "2" && this["DelVal"] == "0")
            ObservedValue.Known(ObservedCycle.FINISHING, "2")
        else if (this["Pr"] == "4" && this["PrPh"] == "3" ||
            this["Pr"] == "1" && this["PrPh"] == "2" && this["DelVal"] == "0")
        observed("StatoTD", mapOf("2" to ObservedCycle.RUNNING, "3" to ObservedCycle.PAUSED))
        else unknown("StatoTD"),
    remainingTime = remainingMinutes(),
    error = unknown("CodiceErrore"),
    filter = observed("CleanFilter", mapOf("1" to FilterNotice.CLEAN_FILTER)),
    waterTank = unknown("WaterTankFull"),
    recipe = CycleTarget.from(this)?.recipe,
)

/** Refresh keeps its program after completion; drying programs report the panel program 15 instead. */
private fun DryerStatus.isObservedCompletion() =
    (this["Pr"] == "4" || this["Pr"] == "15") && this["PrPh"] == "0" && this["StatoTD"] == "8" && this["RemTime"] == "0"

/** Programs whose working phase is 2; Refresh works in phase 3 and is handled separately. */
private val DRYING_PROGRAMS = listOf("1", "2", "3", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14")

private fun DryerStatus.remainingMinutes(): ObservedValue<Int> {
    val raw = this["RemTime"] ?: return ObservedValue.Missing
    if (isObservedCompletion()) return ObservedValue.Known(0, raw)
    val supported = (this["Pr"] == "4" && this["PrPh"] == "3" ||
        this["Pr"] == "1" && this["PrPh"] == "2" && this["DelVal"] == "0") && this["StatoTD"] in listOf("2", "3") ||
        this["Pr"] in listOf("2", "3", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14") && this["PrPh"] == "2" &&
        this["DelVal"] == "0" && (this["StatoTD"] == "2" ||
            this["StatoTD"] == "3" && CycleTarget.from(this)?.supportsPause == true)
    val minutes = raw.takeIf { it.isNotEmpty() && it.all { char -> char in '0'..'9' } }?.toIntOrNull()
    return if (supported && minutes != null) ObservedValue.Known(minutes, raw) else ObservedValue.Unknown(raw)
}

private fun <T> DryerStatus.observed(field: String, meanings: Map<String, T>): ObservedValue<T> {
    val raw = this[field] ?: return ObservedValue.Missing
    return meanings[raw]?.let { ObservedValue.Known(it, raw) } ?: ObservedValue.Unknown(raw)
}

private fun DryerStatus.unknown(field: String): ObservedValue<Nothing> =
    this[field]?.let { ObservedValue.Unknown(it) } ?: ObservedValue.Missing
