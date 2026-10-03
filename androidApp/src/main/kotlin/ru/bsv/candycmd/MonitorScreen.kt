package ru.bsv.candycmd

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.bsv.candycmd.shared.Appliance
import ru.bsv.candycmd.shared.monitor.*
import java.text.DateFormat
import java.util.Date

@Composable
internal fun StatusRow(label: String, value: String, @DrawableRes icon: Int? = null, code: Boolean = false) {
    val stacked = LocalDensity.current.fontScale > 1.3f
    Row(Modifier.fillMaxWidth().padding(vertical = if (code) 9.dp else 15.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        icon?.let { AppGlyph(it, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (stacked) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    fontFamily = if (code) FontFamily.Monospace else FontFamily.Default)
                Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                    fontFamily = if (code) FontFamily.Monospace else FontFamily.Default)
            }
        } else {
            Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = if (code) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                fontFamily = if (code) FontFamily.Monospace else FontFamily.Default)
            Text(value, Modifier.weight(1f), textAlign = TextAlign.End,
                style = if (code) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                fontFamily = if (code) FontFamily.Monospace else FontFamily.Default,
                fontWeight = FontWeight.Medium)
        }
    }
    if (!code) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Support view: decoded state first, then raw protocol fields. Never shows keys, MAC or arbitrary response fields. */
@Composable
internal fun DiagnosticsPanel(state: MonitorState) {
    val status = state.status
    val presentation = status?.presentation()
    Column(Modifier.padding(top = 8.dp)) {
        Text(state.lastSuccessMillis?.let {
            stringResource(R.string.last_response, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(it)))
        } ?: stringResource(R.string.no_response), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.problem?.let {
            Text(stringResource(R.string.last_problem, stringResource(it.messageResource())), Modifier.padding(bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (presentation != null) {
            StatusRow(stringResource(R.string.program_label),
                presentation.recipe?.let { stringResource(it.titleResource()) } ?: programText(presentation.program))
            StatusRow(stringResource(R.string.cycle_label), cycleText(presentation.cycle))
            StatusRow(stringResource(R.string.door_label), doorText(presentation.door))
            status["DryLev"]?.toIntOrNull()?.takeIf { it in 1..4 }?.let {
                StatusRow(stringResource(R.string.dry_level), stringResource(it.dryLevelResource()))
            }
            status["StatoWiFi"]?.takeIf { it == "0" || it == "1" }?.let {
                StatusRow(stringResource(R.string.remote_label),
                    stringResource(if (it == "1") R.string.remote_enabled else R.string.remote_disabled))
            }
        }
        Text(stringResource(R.string.diagnostics_explanation), Modifier.padding(top = 12.dp, bottom = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        // Only protocol fields; never expose arbitrary response keys or device identifiers.
        for (field in listOf("Pr", "RecipeId", "DoorState", "StatoWiFi", "StatoTD", "PrPh", "DryLev", "Time", "RemTime",
            "DelVal", "Rapido", "Opt1", "Opt2", "Opt3", "CodiceErrore", "CleanFilter", "WaterTankFull")) {
            val raw = status?.get(field)
            StatusRow(field, raw?.ifEmpty { stringResource(R.string.empty_value) }
                ?: stringResource(R.string.missing_value), code = true)
        }
    }
}

@Composable
internal fun programText(value: ObservedValue<ObservedProgram>?): String = when (value) {
    is ObservedValue.Known -> stringResource(when (value.value) {
        ObservedProgram.DAILY_PERFECT_59 -> R.string.program_daily_59
        ObservedProgram.DAILY_45 -> R.string.program_daily_45
        ObservedProgram.REFRESH -> R.string.program_refresh
        ObservedProgram.SHIRTS -> R.string.recipe_shirts
        ObservedProgram.SYNTHETICS -> R.string.program_synthetics
        ObservedProgram.WHITES -> R.string.program_whites
        ObservedProgram.ECO_30 -> R.string.program_eco_30
        ObservedProgram.WOOL -> R.string.program_wool
        ObservedProgram.JEANS -> R.string.program_jeans
        ObservedProgram.SPORT -> R.string.program_sport
        ObservedProgram.RELAX_CREASES -> R.string.program_crease
        ObservedProgram.SMALL_LOAD -> R.string.program_small_load
        ObservedProgram.DARKS -> R.string.program_dark
        ObservedProgram.ECO_COTTON -> R.string.program_eco_cotton
    })
    else -> unknownText(value)
}

@Composable
internal fun cycleText(value: ObservedValue<ObservedCycle>?): String = when (value) {
    is ObservedValue.Known -> stringResource(when (value.value) {
        ObservedCycle.RUNNING -> R.string.cycle_running
        ObservedCycle.FINISHING -> R.string.cycle_finishing
        ObservedCycle.PAUSED -> R.string.cycle_paused
        ObservedCycle.COMPLETED -> R.string.cycle_completed
        ObservedCycle.REMOTE_CONTROL -> R.string.cycle_remote_control
        ObservedCycle.DELAYED -> R.string.cycle_delayed
    })
    else -> unknownText(value)
}

@Composable
internal fun doorText(value: ObservedValue<DoorPosition>?): String = when (value) {
    is ObservedValue.Known -> stringResource(if (value.value == DoorPosition.OPEN) R.string.door_open else R.string.door_closed)
    else -> unknownText(value)
}

@Composable
internal fun unknownText(value: ObservedValue<*>?) = stringResource(
    if (value == null || value == ObservedValue.Missing) R.string.missing_value else R.string.unknown_value,
)
