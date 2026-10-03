package ru.bsv.candycmd

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ru.bsv.candycmd.shared.control.Recipe
import ru.bsv.candycmd.shared.control.Settings

internal fun Int.dryLevelResource() = when (this) {
    1 -> R.string.dry_iron
    2 -> R.string.dry_hang
    3 -> R.string.dry_store
    else -> R.string.dry_extra
}

@Composable
internal fun ProgramOptions(settings: Settings, enabled: Boolean, onChange: (Settings) -> Unit) {
    val recipe = requireNotNull(settings.recipe())
    val duration = settings.timeMinutes
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (recipe.hasTimer) {
            OptionLabel(stringResource(R.string.drying_mode))
            SegmentedChoice(listOf(stringResource(R.string.mode_dryness), stringResource(R.string.drying_timed)),
                if (duration == null) 0 else 1, enabled, listOf("dryness-mode", "timed-mode")) {
                onChange(if (it == 0) settings.copy(timeMinutes = null) else settings.copy(timeMinutes = 90, dryLevel = null, easyIron = false))
            }
            Spacer(Modifier.height(6.dp))
        }
        if (duration != null) {
            OptionLabel(stringResource(R.string.drying_duration))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppOutlinedButton(onClick = { onChange(settings.copy(timeMinutes = duration - 10)) },
                    enabled = enabled && duration > 70, modifier = Modifier.testTag("duration-less")) {
                    Text(stringResource(R.string.duration_less))
                }
                Text(stringResource(R.string.minutes_value, duration), Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium)
                AppOutlinedButton(onClick = { onChange(settings.copy(timeMinutes = duration + 10)) },
                    enabled = enabled && duration < 220, modifier = Modifier.testTag("duration-more")) {
                    Text(stringResource(R.string.duration_more))
                }
            }
            Text(stringResource(R.string.timer_range_help), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (recipe.hasDryLevels) {
            OptionLabel(stringResource(R.string.dryness))
            SegmentedChoice((1..4).map { stringResource(it.dryLevelResource()) }, settings.effectiveDryLevel() - 1, enabled,
                (1..4).map { "dry-level-$it" }) { onChange(settings.copy(dryLevel = it + 1)) }
            Text(stringResource(R.string.estimate_empty, settings.initialMinutes()), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (recipe.hasEasyIron) {
                val ironLabel = stringResource(R.string.easy_iron)
                Spacer(Modifier.height(6.dp))
                AppCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(ironLabel, style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.easy_iron_help), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = settings.easyIron, enabled = enabled,
                            modifier = Modifier.testTag("easy-iron").semantics { contentDescription = ironLabel },
                            onCheckedChange = { onChange(settings.copy(easyIron = it, dryLevel = if (it) 1 else recipe.dryLevel)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

/**
 * Equal-width single choice. Labels wrap between words; when the longest word no longer fits a segment
 * (large text, narrow screen), segments move to two columns, then to one, instead of splitting words.
 */
@Composable
internal fun SegmentedChoice(labels: List<String>, selected: Int, enabled: Boolean, tags: List<String>, onSelect: (Int) -> Unit) {
    val divider = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val style = MaterialTheme.typography.bodySmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, divider)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val word = remember(labels, style, density) {
                labels.flatMap { it.split(' ') }.maxOf { measurer.measure(it, style.copy(fontWeight = FontWeight.SemiBold)).size.width }
            }
            val columns = (labels.size downTo 1).filter { labels.size % it == 0 }.first { count ->
                count == 1 || with(density) { ((maxWidth - 1.dp * (count - 1)) / count - 8.dp).roundToPx() } >= word
            }
            Column(Modifier.selectableGroup()) {
                labels.indices.chunked(columns).forEachIndexed { row, indices ->
                    if (row > 0) HorizontalDivider(color = divider)
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        indices.forEachIndexed { column, index ->
                            if (column > 0) VerticalDivider(color = divider)
                            Segment(labels[index], index == selected, enabled, tags[index], Modifier.weight(1f)) { onSelect(index) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Segment(label: String, on: Boolean, enabled: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.fillMaxHeight()
        .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .selectable(on, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        .testTag(tag).heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center) {
        Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                on -> MaterialTheme.colorScheme.onPrimaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            })
    }
}

@Composable
internal fun Settings.summaryText(): String {
    val recipe = requireNotNull(recipe())
    if (!recipe.hasDryLevels) return stringResource(recipe.settingsResource()) +
        (if (usesAntiCrease()) " · ${stringResource(R.string.anti_crease)}" else "")
    return (if (timeMinutes != null) stringResource(R.string.timed_summary, requireNotNull(timeMinutes))
    else stringResource(effectiveDryLevel().dryLevelResource()) +
        (if (easyIron) " · ${stringResource(R.string.easy_iron)}" else "")) +
        (if (usesAntiCrease()) " · ${stringResource(R.string.anti_crease)}" else "")
}

internal fun Recipe.loadResource() = when (this) {
    Recipe.DAILY_59, Recipe.REFRESH, Recipe.RELAX_CREASES, Recipe.PANEL_SHIRTS -> R.string.load_two
    Recipe.DAILY_45, Recipe.SMALL_LOAD -> R.string.load_one_half
    Recipe.ECO_30, Recipe.WOOL -> R.string.load_one
    Recipe.SPORT, Recipe.SYNTHETICS, Recipe.DARKS, Recipe.JEANS -> R.string.load_three
    Recipe.WHITES, Recipe.ECO_COTTON -> R.string.load_seven
    else -> null
}

internal fun Recipe.recommendationResource() = when (this) {
    Recipe.DAILY_59, Recipe.DAILY_45, Recipe.ECO_30 -> R.string.care_fast
    Recipe.REFRESH -> R.string.care_refresh
    Recipe.SPORT -> R.string.care_sport
    Recipe.RELAX_CREASES -> R.string.care_crease
    Recipe.SMALL_LOAD -> R.string.care_small
    Recipe.WOOL -> R.string.care_wool
    Recipe.PANEL_SHIRTS -> R.string.care_shirts
    Recipe.SYNTHETICS -> R.string.care_synthetics
    Recipe.DARKS -> R.string.care_darks
    Recipe.JEANS -> R.string.care_jeans
    Recipe.WHITES -> R.string.care_whites
    Recipe.ECO_COTTON -> R.string.care_cotton
    else -> null
}
