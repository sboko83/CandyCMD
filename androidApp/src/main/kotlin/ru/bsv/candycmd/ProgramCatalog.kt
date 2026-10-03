package ru.bsv.candycmd

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ru.bsv.candycmd.shared.control.CommandBlock
import ru.bsv.candycmd.shared.control.CommandPhase
import ru.bsv.candycmd.shared.control.CommandState
import ru.bsv.candycmd.shared.control.Recipe

/** Presentation catalog only. A dial position never implies a verified write command. */
internal data class ProgramChoice(
    val id: String,
    val title: Int,
    val icon: Int,
    val minutes: Int?,
    val exact: Boolean = false,
    val recipe: Recipe? = null,
)

internal val basicPrograms = listOf(
    ProgramChoice("daily59", R.string.recipe_daily, R.drawable.ic_dryer_hatch, 59, true, Recipe.DAILY_59),
    ProgramChoice("daily45", R.string.program_daily_45, R.drawable.ic_activity, 45, true, recipe = Recipe.DAILY_45),
    ProgramChoice("eco30", R.string.program_eco_30, R.drawable.ic_airflow, 30, true, recipe = Recipe.ECO_30),
    ProgramChoice("refresh", R.string.program_refresh, R.drawable.ic_airflow, 20, true, recipe = Recipe.REFRESH),
    ProgramChoice("sport", R.string.program_sport, R.drawable.ic_program_sport, 94, recipe = Recipe.SPORT),
    ProgramChoice("crease", R.string.program_crease, R.drawable.ic_program_shirt, 12, true, recipe = Recipe.RELAX_CREASES),
    ProgramChoice("small", R.string.program_small_load, R.drawable.ic_program_shirt, 90, recipe = Recipe.SMALL_LOAD),
    ProgramChoice("wool", R.string.program_wool, R.drawable.ic_program_toy, 70, true, recipe = Recipe.WOOL),
    ProgramChoice("shirts", R.string.recipe_shirts, R.drawable.ic_program_shirt, 70, recipe = Recipe.PANEL_SHIRTS),
    ProgramChoice("synthetic", R.string.program_synthetics, R.drawable.ic_program_shirt, 100, recipe = Recipe.SYNTHETICS),
    ProgramChoice("dark", R.string.program_dark, R.drawable.ic_program_shirt, 120, recipe = Recipe.DARKS),
    ProgramChoice("jeans", R.string.program_jeans, R.drawable.ic_program_shirt, 129, recipe = Recipe.JEANS),
    ProgramChoice("white", R.string.program_whites, R.drawable.ic_program_bed, 150, recipe = Recipe.WHITES),
    ProgramChoice("ecocotton", R.string.program_eco_cotton, R.drawable.ic_airflow, 150, recipe = Recipe.ECO_COTTON),
)
internal val extraPrograms = Recipe.entries.filter { !it.isPanel }.map {
    ProgramChoice("extra-${it.id}", it.titleResource(), it.iconResource(), it.initialMinutes, recipe = it)
}
internal val programChoices = basicPrograms + extraPrograms

@Composable
internal fun ProgramChoice.durationText() = minutes?.let {
    stringResource(if (exact) R.string.minutes_value else R.string.recipe_duration, it)
} ?: stringResource(R.string.duration_pending)

@Composable
internal fun ProgramGrid(
    programs: List<ProgramChoice>,
    enabled: Boolean,
    selectedId: String? = null,
    favorites: Set<String>? = null,
    onFavorite: (ProgramChoice) -> Unit = {},
    tagPrefix: String = "program",
    onSelect: (ProgramChoice) -> Unit,
) {
    val columns = if (LocalDensity.current.fontScale > 1.3f) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        programs.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { program ->
                    val selected = program.id == selectedId
                    Surface(onClick = { onSelect(program) }, enabled = enabled, selected = selected,
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag("$tagPrefix-${program.id}"), shape = MaterialTheme.shapes.medium,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                        Box {
                            Row(Modifier.heightIn(min = 76.dp).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AppGlyph(program.icon, Modifier.padding(top = 2.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).padding(end = if (favorites != null) 28.dp else 0.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(stringResource(program.title), style = MaterialTheme.typography.labelLarge)
                                    Text(program.durationText(), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    program.recipe?.loadResource()?.let {
                                        Text(stringResource(it), style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    if (program.recipe == null) Text(stringResource(R.string.panel_only),
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (favorites != null) FavoriteStar(program.id in favorites, stringResource(program.title),
                                Modifier.align(Alignment.TopEnd).padding(2.dp).testTag("$tagPrefix-${program.id}-favorite")) {
                                onFavorite(program)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 40dp box keeps the star 12dp from the card edges; Compose still widens the touch area to 48dp. */
@Composable
private fun FavoriteStar(favorite: Boolean, title: String, modifier: Modifier, onToggle: () -> Unit) {
    Box(modifier.size(40.dp).clip(CircleShape).toggleable(favorite, role = Role.Checkbox, onValueChange = { onToggle() }),
        contentAlignment = Alignment.Center) {
        Icon(painterResource(if (favorite) R.drawable.ic_star_filled else R.drawable.ic_star),
            contentDescription = stringResource(R.string.program_favorite, title), modifier = Modifier.size(20.dp),
            tint = if (favorite) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun Recipe.titleResource() = when (this) {
    Recipe.DAILY_59 -> R.string.recipe_daily
    Recipe.DAILY_45 -> R.string.program_daily_45
    Recipe.ECO_30 -> R.string.program_eco_30
    Recipe.REFRESH -> R.string.program_refresh
    Recipe.SPORT -> R.string.program_sport
    Recipe.RELAX_CREASES -> R.string.program_crease
    Recipe.SMALL_LOAD -> R.string.program_small_load
    Recipe.WOOL -> R.string.program_wool
    Recipe.PANEL_SHIRTS -> R.string.recipe_shirts
    Recipe.SYNTHETICS -> R.string.program_synthetics
    Recipe.DARKS -> R.string.program_dark
    Recipe.JEANS -> R.string.program_jeans
    Recipe.WHITES -> R.string.program_whites
    Recipe.ECO_COTTON -> R.string.program_eco_cotton
    Recipe.BED_LINEN -> R.string.recipe_bed
    Recipe.BABY -> R.string.recipe_baby
    Recipe.SHIRTS -> R.string.recipe_shirts
    Recipe.GYM_FIT -> R.string.recipe_gym
    Recipe.TECHNICAL_FABRICS -> R.string.recipe_technical
    Recipe.BACKPACKS -> R.string.recipe_backpacks
    Recipe.DUVET -> R.string.recipe_duvet
    Recipe.CUDDLY_TOYS -> R.string.recipe_toys
    Recipe.AIR_REFRESH -> R.string.recipe_air_refresh
}
internal fun Recipe.settingsResource() = when (this) {
    Recipe.DAILY_59 -> R.string.settings_daily
    Recipe.DAILY_45 -> R.string.settings_panel_level_0
    Recipe.ECO_30 -> R.string.settings_panel_level_0
    Recipe.REFRESH -> R.string.settings_panel_level_0
    Recipe.SPORT -> R.string.settings_panel_level_3
    Recipe.RELAX_CREASES -> R.string.settings_panel_level_0
    Recipe.SMALL_LOAD -> R.string.settings_panel_level_3
    Recipe.WOOL -> R.string.settings_panel_level_0
    Recipe.PANEL_SHIRTS -> R.string.settings_panel_level_2
    Recipe.SYNTHETICS -> R.string.settings_panel_level_2
    Recipe.DARKS -> R.string.settings_panel_level_2
    Recipe.JEANS -> R.string.settings_panel_level_4
    Recipe.WHITES -> R.string.settings_panel_level_2
    Recipe.ECO_COTTON -> R.string.settings_panel_level_2
    Recipe.BED_LINEN -> R.string.settings_bed
    Recipe.BABY -> R.string.settings_baby
    Recipe.SHIRTS -> R.string.settings_shirts
    Recipe.GYM_FIT, Recipe.TECHNICAL_FABRICS -> R.string.settings_sports
    Recipe.BACKPACKS -> R.string.settings_backpacks
    Recipe.DUVET -> R.string.settings_duvet
    Recipe.CUDDLY_TOYS -> R.string.settings_toys
    Recipe.AIR_REFRESH -> R.string.settings_air_refresh
}
internal fun Recipe.limitsResource() = when (this) {
    Recipe.DAILY_59 -> R.string.limits_daily
    Recipe.DAILY_45, Recipe.ECO_30, Recipe.REFRESH, Recipe.SPORT, Recipe.RELAX_CREASES, Recipe.SMALL_LOAD, Recipe.WOOL, Recipe.PANEL_SHIRTS, Recipe.SYNTHETICS, Recipe.DARKS, Recipe.JEANS, Recipe.WHITES, Recipe.ECO_COTTON -> R.string.limits_panel
    Recipe.BED_LINEN -> R.string.limits_bed
    Recipe.BABY -> R.string.limits_baby
    Recipe.SHIRTS -> R.string.limits_shirts
    Recipe.GYM_FIT, Recipe.TECHNICAL_FABRICS -> R.string.limits_sports
    Recipe.BACKPACKS -> R.string.limits_backpacks
    Recipe.DUVET -> R.string.limits_duvet
    Recipe.CUDDLY_TOYS -> R.string.limits_toys
    Recipe.AIR_REFRESH -> R.string.limits_air_refresh
}
internal fun CommandState.messageResource() = when (phase) {
    CommandPhase.IDLE -> R.string.control_heading
    CommandPhase.CHECKING -> R.string.command_checking
    CommandPhase.SENDING -> R.string.command_sending
    CommandPhase.WAITING -> R.string.command_waiting
    CommandPhase.CONFIRMED -> R.string.command_confirmed
    CommandPhase.UNKNOWN -> R.string.command_unknown
    CommandPhase.REJECTED -> when (block) {
        CommandBlock.SETTINGS -> R.string.preset_obsolete
        CommandBlock.CYCLE_CHANGED -> R.string.command_cycle_changed
        CommandBlock.NO_KEY -> R.string.command_no_key
        CommandBlock.NOT_READY, CommandBlock.UNSUPPORTED_STATE -> R.string.start_conditions
        else -> R.string.command_rejected
    }
}
