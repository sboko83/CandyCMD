package ru.bsv.candycmd

import android.widget.NumberPicker
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import ru.bsv.candycmd.shared.control.Recipe
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun Recipe.iconResource() = when (this) {
    Recipe.DAILY_59 -> R.drawable.ic_dryer_hatch
    Recipe.DAILY_45, Recipe.ECO_30, Recipe.REFRESH, Recipe.SPORT, Recipe.RELAX_CREASES, Recipe.SMALL_LOAD, Recipe.WOOL, Recipe.PANEL_SHIRTS, Recipe.SYNTHETICS, Recipe.DARKS, Recipe.JEANS, Recipe.WHITES, Recipe.ECO_COTTON -> basicPrograms.first { it.recipe == this }.icon
    Recipe.BED_LINEN, Recipe.DUVET -> R.drawable.ic_program_bed
    Recipe.BABY, Recipe.CUDDLY_TOYS -> R.drawable.ic_program_toy
    Recipe.SHIRTS, Recipe.TECHNICAL_FABRICS -> R.drawable.ic_program_shirt
    Recipe.GYM_FIT -> R.drawable.ic_program_sport
    Recipe.BACKPACKS -> R.drawable.ic_program_bag
    Recipe.AIR_REFRESH -> R.drawable.ic_airflow
}

@Composable
internal fun DelayPicker(minutes: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        DelayWheel(minutes / 60, 24, 1, stringResource(R.string.delay_hours), "delay-hours", enabled,
            Modifier.weight(1f)) { hours -> onChange(if (hours == 24) 1440 else hours * 60 + minutes % 60) }
        DelayWheel(minutes % 60 / 15, if (minutes / 60 == 24) 0 else 3, 15,
            stringResource(R.string.delay_minutes), "delay-minutes", enabled, Modifier.weight(1f)) {
            onChange(minutes / 60 * 60 + it * 15)
        }
    }
}

@Composable
private fun DelayWheel(value: Int, maximum: Int, step: Int, label: String, tag: String,
                       enabled: Boolean, modifier: Modifier, onChange: (Int) -> Unit) {
    val latestChange by rememberUpdatedState(onChange)
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        AndroidView(modifier = Modifier.fillMaxWidth().height(132.dp).testTag(tag).semantics {
            contentDescription = label
            stateDescription = (value * step).toString()
            progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..maximum.toFloat(), (maximum - 1).coerceAtLeast(0))
            if (!enabled) disabled()
            setProgress { target ->
                if (enabled) latestChange(target.toInt().coerceIn(0, maximum))
                enabled
            }
        }, factory = { context ->
            NumberPicker(context).apply {
                minValue = 0
                descendantFocusability = NumberPicker.FOCUS_BLOCK_DESCENDANTS
                setOnValueChangedListener { _, _, next -> latestChange(next) }
            }
        }, update = { picker ->
            if (picker.maxValue != maximum || picker.displayedValues == null) {
                picker.displayedValues = null
                picker.maxValue = maximum
                picker.displayedValues = Array(maximum + 1) { (it * step).toString().padStart(2, '0') }
            }
            picker.wrapSelectorWheel = false
            if (picker.value != value) picker.value = value
            picker.isEnabled = enabled
            if (android.os.Build.VERSION.SDK_INT >= 29) picker.textColor = color
            for (index in 0 until picker.childCount) {
                (picker.getChildAt(index) as? android.widget.TextView)?.setTextColor(color)
            }
        })
    }
}

/** Wall clock that ticks often enough for minute-precision start and finish times. */
@Composable
internal fun rememberNowMillis(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
internal fun clockText(nowMillis: Long, plusMinutes: Int): String {
    val time = Instant.ofEpochMilli(nowMillis).plusSeconds(plusMinutes * 60L).atZone(ZoneId.systemDefault())
    val today = Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    val pattern = if (time.toLocalDate() == today) "HH:mm" else "d MMM, HH:mm"
    return time.format(DateTimeFormatter.ofPattern(pattern, LocalConfiguration.current.locales[0]))
}

@Composable
internal fun durationText(minutes: Int): String =
    if (minutes >= 60) stringResource(R.string.duration_hm, minutes / 60, minutes % 60)
    else stringResource(R.string.duration_m, minutes)
