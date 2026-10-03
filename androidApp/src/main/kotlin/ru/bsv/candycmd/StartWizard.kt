package ru.bsv.candycmd

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import ru.bsv.candycmd.shared.control.*
import ru.bsv.candycmd.shared.monitor.MonitorState

internal enum class StartStep { PROGRAM, OPTIONS, START, CONFIRM }

/** Everything the wizard collects; nothing here is sent until the final explicit start. */
internal data class StartDraft(
    val programId: String = "",
    val dryLevel: Int = 0,
    val timeMinutes: Int = 0,
    val easyIron: Boolean = false,
    val delayed: Boolean = false,
    val delayMinutes: Int = 60,
    val step: StartStep = StartStep.PROGRAM,
    val favorite: Boolean = false,
    val favoriteName: String = "",
    val antiCrease: Boolean = true,
) {
    val choice: ProgramChoice? get() = programChoices.firstOrNull { it.id == programId }

    /** Settings without the delay drive the options step and duration estimates. */
    val baseSettings: Settings? get() = choice?.recipe?.let { recipe ->
        Settings(recipe.id, dryLevel = dryLevel.takeIf { it > 0 && recipe.hasDryLevels },
            timeMinutes = timeMinutes.takeIf { it > 0 && recipe.hasTimer }, easyIron = easyIron && recipe.hasEasyIron, antiCrease = antiCrease)
    }
    val settings: Settings? get() = baseSettings?.let {
        if (delayed) it.copy(delayMinutes = delayMinutes) else it
    }
    /** Constant: a step tied to the selected program made the counter jump between 3 and 4. */
    val steps: List<StartStep> get() = StartStep.entries

    fun select(value: ProgramChoice) = copy(programId = value.id, dryLevel = 0, timeMinutes = 0, easyIron = false)
        .let { it.copy(step = it.steps[1]) }
    fun withOptions(value: Settings) = copy(dryLevel = value.dryLevel ?: 0, timeMinutes = value.timeMinutes ?: 0, easyIron = value.easyIron)
    fun next() = copy(step = steps.getOrElse(steps.indexOf(step) + 1) { step })
    fun previous() = steps.indexOf(step).let { if (it > 0) copy(step = steps[it - 1]) else null }

    companion object {
        fun from(preset: Preset): StartDraft? {
            val stored = preset.settings
            val recipe = stored.recipe() ?: return null
            val choice = programChoices.firstOrNull { it.recipe == recipe } ?: return null
            return StartDraft(choice.id, stored.dryLevel ?: 0, stored.timeMinutes ?: 0, stored.easyIron,
                delayed = stored.delayMinutes > 0, delayMinutes = stored.delayMinutes.takeIf { it > 0 } ?: 60,
                step = StartStep.CONFIRM, antiCrease = stored.antiCrease)
        }

        val Saver = listSaver<StartDraft, Any>(
            save = { listOf(it.programId, it.dryLevel, it.timeMinutes, it.easyIron, it.delayed, it.delayMinutes,
                it.step.name, it.favorite, it.favoriteName, it.antiCrease) },
            restore = {
                StartDraft(it[0] as String, it[1] as Int, it[2] as Int, it[3] as Boolean, it[4] as Boolean, it[5] as Int,
                    StartStep.valueOf(it[6] as String), it[7] as Boolean, it[8] as String, it.getOrNull(9) as? Boolean ?: true)
            },
        )
    }
}

@Composable
internal fun StartWizard(
    draft: StartDraft,
    onDraft: (StartDraft) -> Unit,
    monitor: MonitorState,
    commandState: CommandState,
    busy: Boolean,
    canSaveFavorite: Boolean,
    onClose: () -> Unit,
    onStart: (Settings, String?) -> Unit,
    favoritePrograms: Set<String>? = null,
    onFavoriteProgram: (String) -> Unit = {},
) {
    val back = { draft.previous()?.let(onDraft) ?: onClose() }
    BackHandler(onBack = back)
    val now = rememberNowMillis()
    val settings = draft.settings
    val status = monitor.status
    val start = settings?.let { DryerCommand.Start(it) }
    val allowed = start != null && status != null && !monitor.stale && !busy && !commandState.locked &&
        CommandPolicy.blocked(start, status) == null
    val position = draft.steps.indexOf(draft.step) + 1
    val stepText = stringResource(R.string.step_of, position, draft.steps.size)
    val programTitle = draft.choice?.let { stringResource(it.title) }
    val nameValid = draft.favoriteName.isNotBlank() && draft.favoriteName.none(Char::isISOControl)

    ScreenFrame(header = {
        BackHeader(onBack = back, onClose = onClose)
        StepIndicator(position, draft.steps.size)
    }, bottomBar = {
        if (draft.step == StartStep.CONFIRM) {
            AppButton(onClick = { if (settings != null) onStart(settings, draft.favoriteName.trim().takeIf { draft.favorite }) },
                enabled = allowed && (!draft.favorite || nameValid), modifier = Modifier.fillMaxWidth().testTag("start-program")) {
                val delay = settings?.delayMinutes ?: 0
                Text(if (delay > 0) stringResource(R.string.start_at_button, clockText(now, delay))
                    else stringResource(R.string.start_drying))
            }
        } else {
            AppButton(onClick = { onDraft(draft.next()) }, enabled = draft.choice != null,
                modifier = Modifier.fillMaxWidth().testTag("wizard-next")) { Text(stringResource(R.string.next)) }
        }
    }) {
        when (draft.step) {
            StartStep.PROGRAM -> {
                ScreenTitle(stringResource(R.string.step_program), stepText)
                val select = { program: ProgramChoice -> onDraft(draft.select(program)) }
                val favorite = { program: ProgramChoice -> onFavoriteProgram(program.id) }
                val favorites = programChoices.filter { favoritePrograms?.contains(it.id) == true }
                if (favorites.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.favorites), Modifier.padding(top = 0.dp))
                    ProgramGrid(favorites, true, draft.programId, favoritePrograms, favorite, "favorite-program", select)
                }
                SectionLabel(stringResource(R.string.group_panel), Modifier.padding(top = 0.dp))
                ProgramGrid(basicPrograms, true, draft.programId, favoritePrograms, favorite, onSelect = select)
                SectionLabel(stringResource(R.string.group_recipes))
                ProgramGrid(extraPrograms, true, draft.programId, favoritePrograms, favorite, onSelect = select)
                Text(stringResource(R.string.recipes_note), Modifier.padding(top = 12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.duration_note), Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StartStep.OPTIONS -> {
                ScreenTitle(stringResource(R.string.step_options), "$stepText · $programTitle")
                val base = draft.baseSettings
                if (base?.recipe()?.hasDryLevels == true) ProgramOptions(base, enabled = true) { onDraft(draft.withOptions(it)) }
                else if (base != null) AppCard(Modifier.testTag("fixed-options")) {
                    Text(base.summaryText(), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.options_fixed), Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                PanelExtras(base?.recipe())
            }
            StartStep.START -> {
                ScreenTitle(stringResource(R.string.step_start), "$stepText · $programTitle")
                val minutes = draft.baseSettings?.initialMinutes() ?: 0
                RadioCard(selected = !draft.delayed, tag = "start-now",
                    title = stringResource(R.string.start_now),
                    subtitle = stringResource(R.string.finish_at, clockText(now, minutes)),
                    onSelect = { onDraft(draft.copy(delayed = false)) })
                Spacer(Modifier.height(8.dp))
                RadioCard(selected = draft.delayed, tag = "start-later",
                    title = stringResource(R.string.start_later),
                    subtitle = if (draft.delayed) stringResource(R.string.start_later_summary, clockText(now, draft.delayMinutes),
                        clockText(now, draft.delayMinutes + minutes)) else null,
                    onSelect = { onDraft(draft.copy(delayed = true)) }) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    DelayPicker(draft.delayMinutes, enabled = true) { onDraft(draft.copy(delayMinutes = it.coerceAtLeast(15))) }
                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Row(Modifier.fillMaxWidth().toggleable(draft.antiCrease, role = Role.Checkbox,
                        onValueChange = { onDraft(draft.copy(antiCrease = it)) })
                        .testTag("anti-crease").heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Checkbox(checked = draft.antiCrease, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.anti_crease), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(R.string.anti_crease_help), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            StartStep.CONFIRM -> {
                ScreenTitle(stringResource(R.string.step_confirm), stepText)
                if (settings == null) {
                    Text(stringResource(R.string.panel_program_help), style = MaterialTheme.typography.bodyMedium)
                    return@ScreenFrame
                }
                val recipe = requireNotNull(settings.recipe())
                val minutes = settings.initialMinutes()
                ListCard {
                    ListRow(stringResource(R.string.row_program), first = true, value = programTitle)
                    ListRow(stringResource(R.string.row_settings), value = settings.summaryText())
                    ListRow(stringResource(R.string.row_start), value = if (settings.delayMinutes == 0) stringResource(R.string.start_now)
                        else stringResource(R.string.at_time, clockText(now, settings.delayMinutes)))
                    ListRow(stringResource(R.string.row_finish),
                        value = stringResource(R.string.approx_time, clockText(now, settings.delayMinutes + minutes)))
                }
                Spacer(Modifier.height(16.dp))
                Callout {
                    recipe.loadResource()?.let { Text(stringResource(it), fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium) }
                    Text(stringResource(recipe.limitsResource()), style = MaterialTheme.typography.bodySmall)
                    recipe.recommendationResource()?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth().toggleable(draft.favorite, enabled = canSaveFavorite, role = Role.Checkbox) {
                    onDraft(draft.copy(favorite = it, favoriteName = draft.favoriteName.ifBlank { programTitle.orEmpty() }))
                }.heightIn(min = 48.dp).testTag("save-favorite"), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Checkbox(checked = draft.favorite, onCheckedChange = null, enabled = canSaveFavorite)
                    Text(stringResource(R.string.save_favorite), style = MaterialTheme.typography.bodyLarge)
                }
                if (draft.favorite) {
                    OutlinedTextField(value = draft.favoriteName, onValueChange = { if (it.length <= 60) onDraft(draft.copy(favoriteName = it)) },
                        singleLine = true, label = { Text(stringResource(R.string.favorite_name)) }, shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().testTag("favorite-name"))
                }
                if (!allowed && !busy && !commandState.busy) {
                    Text(stringResource(R.string.start_blocked), Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(position: Int, total: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { index ->
            Surface(Modifier.weight(1f).height(4.dp), shape = MaterialTheme.shapes.extraSmall,
                color = if (index < position) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant) {}
        }
    }
}

@Composable
private fun RadioCard(
    selected: Boolean,
    tag: String,
    title: String,
    subtitle: String?,
    onSelect: () -> Unit,
    expanded: @Composable ColumnScope.() -> Unit = {},
) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect)
                .heightIn(min = 48.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RadioButton(selected = selected, onClick = null)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (selected) expanded()
        }
    }
}

/** Panel-only functions are explained, never offered as app options: no LAN command exists for them. */
@Composable
private fun PanelExtras(recipe: Recipe?) {
    var open by rememberSaveable { mutableStateOf(false) }
    Disclosure(stringResource(R.string.panel_extras), open, { open = !open })
    if (open) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.panel_memory_help), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.panel_dryness_help), style = MaterialTheme.typography.bodySmall)
        if (recipe?.hasTimer == true) Text(stringResource(R.string.panel_time_help), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.panel_functions_help), style = MaterialTheme.typography.bodySmall)
    }
}
