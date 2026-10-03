package ru.bsv.candycmd.shared.control

import kotlinx.serialization.Serializable
import ru.bsv.candycmd.shared.protocol.DryerStatus

/** Fixed recipes observed on RO4; the extended delay range is a product setting. */
enum class Recipe(val id: String, val program: Int, val dryLevel: Int, val mask: Int,
                  val recipePrefix: Int, val wireName: String, val timeLevel: Int? = null,
                  val rapidLevel: Int? = null, val observedOption2: Int = 1, val runningPhase: Int = 2) {
    DAILY_59("daily-59", 1, 2, 0, 101, "Daily Perfect 59%27"),
    DAILY_45("daily-45", 2, 0, 0, 102, "Daily 45%27", observedOption2 = 0),
    ECO_30("eco-30", 3, 0, 0, 103, "Eco 30%27", observedOption2 = 0),
    REFRESH("refresh", 4, 0, 0, 104, "Refresh", runningPhase = 3, observedOption2 = 0),
    SPORT("sport", 5, 3, 0, 105, "Sport"),
    RELAX_CREASES("relax-creases", 6, 0, 0, 106, "Relax Creases", observedOption2 = 0),
    SMALL_LOAD("small-load", 7, 3, 0, 107, "Small Load"),
    WOOL("wool", 8, 0, 0, 108, "Wool", observedOption2 = 0),
    PANEL_SHIRTS("panel-shirts", 9, 2, 1, 109, "Shirts"),
    SYNTHETICS("synthetics", 10, 2, 1, 110, "Synthetics"),
    DARKS("darks", 11, 2, 1, 111, "Darks and Coloured"),
    JEANS("jeans", 12, 4, 0, 112, "Jeans"),
    WHITES("whites", 13, 2, 1, 113, "Whites"),
    ECO_COTTON("eco-cotton", 14, 2, 0, 114, "Eco Cotton", observedOption2 = 0),
    BED_LINEN("bed-linen", 13, 4, 1, 344, "Bed Linen"),
    BABY("baby", 10, 3, 1, 343, "Baby"),
    SHIRTS("shirts", 9, 2, 1, 352, "Shirts"),
    GYM_FIT("gym-fit", 10, 2, 1, 333, "Gym fit - Fitness"),
    TECHNICAL_FABRICS("technical-fabrics", 10, 2, 1, 346, "Technical Fabrics"),
    BACKPACKS("backpacks", 10, 0, 1, 331, "Backpacks", timeLevel = 7),
    DUVET("duvet", 12, 4, 0, 338, "Duvet"),
    CUDDLY_TOYS("cuddly-toys", 8, 0, 0, 353, "Cuddly Toys", observedOption2 = 0),
    AIR_REFRESH("air-refresh", 3, 0, 0, 336, "Air Refresh", rapidLevel = 1, observedOption2 = 0);

    val isPanel: Boolean get() = recipePrefix in 101..114
    val supportsPause: Boolean get() = isPanel
    val hasDryLevels: Boolean get() = this in listOf(SPORT, PANEL_SHIRTS, SYNTHETICS, DARKS, WHITES)
    val hasTimer: Boolean get() = this == SYNTHETICS || this == WHITES
    val hasEasyIron: Boolean get() = this in listOf(PANEL_SHIRTS, SYNTHETICS, DARKS, WHITES)
    fun controlOptionsVerified(level: Int, time: Int, iron: Boolean): Boolean {
        if (level == dryLevel && time == (timeLevel ?: 0) && !iron) return true
        // Gates pause and resume only: completed start/pause/resume/cancel checks enter this set.
        return time == 0 && level in 1..4 && when (this) {
            SPORT -> !iron
            PANEL_SHIRTS -> true
            SYNTHETICS -> !iron
            else -> false
        }
    }

    val initialMinutes: Int get() = when (this) {
        DAILY_59 -> 59
        DAILY_45 -> 45
        ECO_30 -> 30
        REFRESH -> 20
        SPORT -> 94
        RELAX_CREASES -> 12
        SMALL_LOAD -> 90
        WOOL -> 70
        PANEL_SHIRTS -> 70
        SYNTHETICS -> 100
        DARKS -> 120
        JEANS -> 129
        WHITES -> 150
        ECO_COTTON -> 150
        BED_LINEN -> 180
        BABY -> 110
        SHIRTS, CUDDLY_TOYS -> 70
        GYM_FIT, TECHNICAL_FABRICS -> 100
        BACKPACKS -> 90
        DUVET -> 129
        AIR_REFRESH -> 30
    }

    companion object { fun find(id: String): Recipe? = entries.find { it.id == id } }
}

@Serializable
data class Settings(val recipeId: String, val version: Int = 3, val delayMinutes: Int = 0,
                    val dryLevel: Int? = null, val timeMinutes: Int? = null, val easyIron: Boolean = false, val antiCrease: Boolean = true) {
    fun recipe(): Recipe? = Recipe.find(recipeId)?.takeIf {
        version in 1..3 && (version >= 2 || dryLevel == null && timeMinutes == null && !easyIron) &&
            delayMinutes in 0..MAX_DELAY_MINUTES && delayMinutes % 15 == 0 &&
            (dryLevel == null || it.hasDryLevels && dryLevel in 1..4) &&
            (timeMinutes == null || it.hasTimer && timeMinutes in 70..220 && timeMinutes % 10 == 0 && dryLevel == null && !easyIron) &&
            (!easyIron || it.hasEasyIron)
    }

    fun effectiveDryLevel(): Int = if (timeMinutes != null) 0 else dryLevel ?: if (easyIron) 1 else requireNotNull(recipe()).dryLevel
    fun effectiveTimeLevel(): Int = timeMinutes?.let { it / 10 - 2 } ?: requireNotNull(recipe()).timeLevel ?: 0
    fun usesAntiCrease(): Boolean = delayMinutes > 0 && antiCrease
    fun effectiveMask(): Int = when {
        usesAntiCrease() -> 16 or (if (easyIron) 4 else 0)
        easyIron -> 4
        else -> requireNotNull(recipe()).mask
    }
    fun initialMinutes(): Int = timeMinutes ?: requireNotNull(recipe()).let { recipe ->
        val estimates = when (recipe) {
            Recipe.SPORT -> listOf(60, 86, 94, 103)
            Recipe.PANEL_SHIRTS -> listOf(49, 70, 77, 84)
            Recipe.SYNTHETICS -> listOf(70, 100, 110, 120)
            Recipe.DARKS -> listOf(88, 120, 132, 144)
            Recipe.WHITES -> listOf(110, 150, 165, 180)
            else -> emptyList()
        }
        estimates.getOrNull(effectiveDryLevel() - 1) ?: recipe.initialMinutes
    }

    companion object { const val MAX_DELAY_MINUTES = 24 * 60 }
}

/** A target binds a confirmation dialog to the cycle that the user actually saw. */
data class CycleTarget(val recipe: Recipe, val recipeId: String, val dryLevel: Int = recipe.dryLevel,
                       val timeLevel: Int = recipe.timeLevel ?: 0, val easyIron: Boolean = false, val antiCrease: Boolean = false) {
    val supportsPause: Boolean get() = !antiCrease && recipe.supportsPause && recipe.controlOptionsVerified(dryLevel, timeLevel, easyIron)
    fun matches(status: DryerStatus): Boolean = status["Pr"] == recipe.program.toString() &&
        status["DryLev"] == dryLevel.toString() && status["RecipeId"] == recipeId &&
        encodedRecipePrefix(recipeId) == recipe.recipePrefix &&
        // ECO 30 accepts Rapido=1 but reports Rapido=0; do not infer response flags from write fields.
        status["Opt1"] == (if (antiCrease) "1" else "0") &&
        (recipe == Recipe.DAILY_59 || status["Time"] == timeLevel.toString() &&
            status["Rapido"] == "0" && status["Opt2"] == recipe.observedOption2.toString() &&
            (!recipe.hasDryLevels || status["Opt3"] == if (easyIron) "1" else "0"))

    companion object {
        fun from(status: DryerStatus): CycleTarget? {
            val id = status["RecipeId"]?.takeIf { it.isNotEmpty() && it != "NULL" } ?: return null
            val recipe = Recipe.entries.find { it.recipePrefix == encodedRecipePrefix(id) } ?: return null
            val antiCrease = when (status["Opt1"]) { "1" -> true; "0" -> false; else -> return null }
            if (!recipe.hasDryLevels) return CycleTarget(recipe, id, antiCrease = antiCrease).takeIf { it.matches(status) }
            val level = status["DryLev"]?.toIntOrNull() ?: return null
            val time = status["Time"]?.toIntOrNull() ?: return null
            val iron = when (status["Opt3"]) { "1" -> true; "0" -> false; else -> return null }
            val settings = Settings(recipe.id, dryLevel = level.takeIf { time == 0 },
                timeMinutes = ((time + 2) * 10).takeIf { time != 0 }, easyIron = iron)
            if (settings.recipe() == null || settings.effectiveDryLevel() != level || settings.effectiveTimeLevel() != time) return null
            return CycleTarget(recipe, id, level, time, iron, antiCrease).takeIf { it.matches(status) }
        }
    }
}

private fun encodedRecipePrefix(id: String): Int? {
    if (id.length !in 1..6) return null
    val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    var value = 0L
    for (char in id) {
        val digit = alphabet.indexOf(char).takeIf { it >= 0 } ?: return null
        value = value * 62 + digit
    }
    return (value / 10_000_000).toInt()
}

sealed interface DryerCommand {
    data class Start(val settings: Settings) : DryerCommand
    data class Pause(val target: CycleTarget) : DryerCommand
    data class Resume(val target: CycleTarget) : DryerCommand
    data class Cancel(val target: CycleTarget) : DryerCommand
}

enum class CommandBlock { SETTINGS, NOT_READY, CYCLE_CHANGED, UNSUPPORTED_STATE, NO_KEY, STORAGE, CONNECTION }

object CommandPolicy {
    fun safe(status: DryerStatus) = status["DoorState"] == "1" && status["StatoWiFi"] == "1" &&
        status["CodiceErrore"] == "0"

    fun idle(status: DryerStatus) = status["StatoTD"] == "1" && status["Pr"] == "15" &&
        status["PrPh"] == "0" && status["DelVal"] == "0"

    fun blocked(command: DryerCommand, status: DryerStatus): CommandBlock? {
        if (command is DryerCommand.Start && command.settings.recipe() == null) return CommandBlock.SETTINGS
        if (!safe(status)) return CommandBlock.NOT_READY
        if (command is DryerCommand.Start) return if (idle(status)) null else CommandBlock.UNSUPPORTED_STATE
        val target = when (command) {
            is DryerCommand.Pause -> command.target
            is DryerCommand.Resume -> command.target
            is DryerCommand.Cancel -> command.target
            is DryerCommand.Start -> error("Handled above")
        }
        if (!target.matches(status)) return CommandBlock.CYCLE_CHANGED
        val running = status["StatoTD"] == "2" && status["PrPh"] == target.recipe.runningPhase.toString() && status["DelVal"] == "0"
        val paused = status["StatoTD"] == "3" && status["PrPh"] == target.recipe.runningPhase.toString() && status["DelVal"] == "0"
        val delayed = status["StatoTD"] == "5" && status["PrPh"] == "0" &&
            status["DelVal"]?.toIntOrNull() in 1..Settings.MAX_DELAY_MINUTES
        val allowed = when (command) {
            is DryerCommand.Pause -> target.supportsPause && running
            is DryerCommand.Resume -> target.supportsPause && paused
            // Cancel only returns the machine to idle, so it stays available for every observed cycle.
            is DryerCommand.Cancel -> running || delayed || paused
        }
        return if (allowed) null else CommandBlock.UNSUPPORTED_STATE
    }
}

/** Constructed at the send boundary; never persisted or included in diagnostics. */
class PreparedCommand internal constructor(val parameters: String, val command: DryerCommand, val recipeId: String?) {
    fun encryptedPath(key: ByteArray): String {
        require(key.size == 16)
        val digits = "0123456789ABCDEF"
        val hex = buildString {
            parameters.encodeToByteArray().forEachIndexed { index, byte ->
                val value = (byte.toInt() xor key[index % key.size].toInt()) and 255
                append(digits[value ushr 4]); append(digits[value and 15])
            }
        }
        return "/http-write.json?encrypted=1&data=$hex"
    }

    fun confirmed(status: DryerStatus): Boolean {
        if (!CommandPolicy.safe(status)) return false
        return when (val action = command) {
            is DryerCommand.Start -> {
                val recipe = requireNotNull(action.settings.recipe())
                val target = CycleTarget(recipe, requireNotNull(recipeId), action.settings.effectiveDryLevel(),
                    action.settings.effectiveTimeLevel(), action.settings.easyIron, action.settings.usesAntiCrease())
                target.matches(status) && if (action.settings.delayMinutes == 0) {
                    status["StatoTD"] == "2" && status["PrPh"] == recipe.runningPhase.toString() && status["DelVal"] == "0"
                } else {
                    status["StatoTD"] == "5" && status["PrPh"] == "0" &&
                        status["DelVal"] == action.settings.delayMinutes.toString()
                }
            }
            is DryerCommand.Pause -> action.target.matches(status) && status["StatoTD"] == "3" &&
                status["PrPh"] == action.target.recipe.runningPhase.toString() && status["DelVal"] == "0"
            is DryerCommand.Resume -> action.target.matches(status) && status["StatoTD"] == "2" &&
                status["PrPh"] == action.target.recipe.runningPhase.toString() && status["DelVal"] == "0"
            is DryerCommand.Cancel -> CommandPolicy.idle(status)
        }
    }

    override fun toString() = "PreparedCommand(<redacted>)"
}

object CommandBuilder {
    fun prepare(command: DryerCommand, nowMillis: Long): PreparedCommand {
        var id: String? = null
        val parameters = when (command) {
            is DryerCommand.Start -> {
                val recipe = requireNotNull(command.settings.recipe())
                val minutes = nowMillis / 60_000 - 25_612_719 + command.settings.delayMinutes
                require(minutes in 0..9_999_999)
                var number = recipe.recipePrefix * 10_000_000L + minutes
                val alphabet = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
                id = buildString { while (number > 0) { append(alphabet[(number % 62).toInt()]); number /= 62 } }.reversed()
                val fields = when {
                    recipe.isPanel && command.settings.timeMinutes != null -> "PrNm=${recipe.program}&Time=${command.settings.effectiveTimeLevel()}"
                    recipe.isPanel -> "PrNm=${recipe.program}&DryLev=${command.settings.effectiveDryLevel()}"
                    recipe.timeLevel != null -> "Time=${recipe.timeLevel}&PrNm=${recipe.program}"
                    recipe.rapidLevel != null -> "Rapido=${recipe.rapidLevel}&PrNm=${recipe.program}"
                    recipe.dryLevel == 0 -> "PrNm=${recipe.program}"
                    else -> "DryLev=${recipe.dryLevel}&PrNm=${recipe.program}"
                }
                "Write=1&$fields&OptMsk=${command.settings.effectiveMask()}&RecipeId=$id&PrStr=${recipe.wireName}&StSt=1" +
                    if (command.settings.delayMinutes > 0) "&DelMd=1&DelVl=${command.settings.delayMinutes}" else ""
            }
            is DryerCommand.Pause -> "Write=1&SetPauseDryMgr=1"
            is DryerCommand.Resume -> "Write=1&SetRestartDryMgr=1"
            is DryerCommand.Cancel -> "Write=1&StSt=0&PrNm=${command.target.recipe.program}"
        }
        return PreparedCommand(parameters, command, id)
    }
}
