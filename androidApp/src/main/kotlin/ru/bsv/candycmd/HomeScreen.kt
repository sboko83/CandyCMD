package ru.bsv.candycmd

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import ru.bsv.candycmd.shared.connection.ConnectionProblem
import ru.bsv.candycmd.shared.control.*
import ru.bsv.candycmd.shared.monitor.*

internal data class HomeActions(
    val choose: () -> Unit,
    val favorite: (Preset) -> Unit,
    val rename: (Preset, String) -> Unit,
    val delete: (Preset) -> Unit,
    val command: (DryerCommand) -> Unit,
    val check: () -> Unit,
    val wifiSettings: () -> Unit,
    val requestPermission: () -> Unit,
)

internal fun MonitorState.link(connecting: Boolean): LinkState = when {
    status != null && !stale -> LinkState.ONLINE
    problem in setOf(ConnectionProblem.NO_WIFI, ConnectionProblem.OFF_LINK, ConnectionProblem.WIFI_SELECTION_REQUIRED) ->
        LinkState.NO_WIFI
    connecting || problem == null && status == null -> LinkState.CONNECTING
    else -> LinkState.SLEEP
}

@Composable
internal fun ColumnScope.HomeContent(
    monitor: MonitorState,
    commandState: CommandState,
    busy: Boolean,
    link: LinkState,
    presets: List<Preset>,
    presetMessage: String,
    actions: HomeActions,
) {
    val status = monitor.status?.takeIf { link == LinkState.ONLINE }
    var cancelling by remember { mutableStateOf<DryerCommand.Cancel?>(null) }
    fun allowed(command: DryerCommand): Boolean {
        val fresh = status ?: return false
        // An unconfirmed command blocks new writes, but cancel always stays available.
        val locked = if (command is DryerCommand.Cancel) commandState.busy else commandState.locked
        return !busy && !locked && CommandPolicy.blocked(command, fresh) == null
    }

    if (status == null) OfflineCard(monitor, link, actions)
    else StatusCard(status, ::allowed, onCommand = actions.command, onCancel = { cancelling = it })
    CommandFeedback(commandState, busy, actions.check)
    MachineNotices(status)

    val canChoose = status != null && CommandPolicy.idle(status) && CommandPolicy.safe(status) && !busy && !commandState.locked
    if (status == null || CommandPolicy.idle(status)) {
        Spacer(Modifier.height(16.dp))
        AppButton(onClick = actions.choose, enabled = canChoose, modifier = Modifier.fillMaxWidth().testTag("choose-program")) {
            Text(stringResource(R.string.choose_program))
        }
    }
    Favorites(presets, presetMessage, actions)

    cancelling?.let { command ->
        val delayed = monitor.status?.get("StatoTD") == "5"
        AlertDialog(onDismissRequest = { cancelling = null },
            title = { Text(stringResource(if (delayed) R.string.cancel_start_title else R.string.cancel_drying_title)) },
            text = { Text(stringResource(R.string.cancel_body)) },
            confirmButton = {
                AppTextButton(onClick = { cancelling = null; actions.command(command) }, enabled = allowed(command),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm-cancel")) {
                    Text(stringResource(if (delayed) R.string.cancel_start else R.string.cancel_drying))
                }
            },
            dismissButton = { AppTextButton(onClick = { cancelling = null }) { Text(stringResource(R.string.keep_running)) } })
    }
}

@Composable
private fun OfflineCard(monitor: MonitorState, link: LinkState, actions: HomeActions) {
    AppCard(tonal = true) {
        when {
            link == LinkState.CONNECTING -> {
                Text(stringResource(R.string.connecting_title), style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
            }
            monitor.problem == ConnectionProblem.PERMISSION_DENIED -> {
                Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.permission_body), Modifier.padding(top = 4.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppOutlinedButton(onClick = actions.requestPermission) { Text(stringResource(R.string.grant_permission)) }
            }
            link == LinkState.NO_WIFI -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppGlyph(R.drawable.ic_wifi, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.wifi_off_title), style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.wifi_off_body), Modifier.padding(top = 4.dp, bottom = 12.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppOutlinedButton(onClick = actions.wifiSettings) { Text(stringResource(R.string.wifi_settings)) }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppGlyph(R.drawable.ic_moon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.sleep_title), style = MaterialTheme.typography.titleMedium)
                }
                Text(stringResource(R.string.sleep_body), Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.waiting_dryer), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        monitor.status?.let { previous ->
            val active = previous.presentation().cycle.let { it is ObservedValue.Known && it.value != ObservedCycle.REMOTE_CONTROL }
            if (active) Text(stringResource(R.string.last_seen, cycleSummary(previous)), Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun cycleSummary(status: ru.bsv.candycmd.shared.protocol.DryerStatus): String {
    val presentation = status.presentation()
    val program = presentation.recipe?.let { stringResource(it.titleResource()) } ?: programText(presentation.program)
    return "${cycleText(presentation.cycle)} · $program"
}

@Composable
private fun StatusCard(
    status: ru.bsv.candycmd.shared.protocol.DryerStatus,
    allowed: (DryerCommand) -> Boolean,
    onCommand: (DryerCommand) -> Unit,
    onCancel: (DryerCommand.Cancel) -> Unit,
) {
    val presentation = status.presentation()
    val target = CycleTarget.from(status)
    val now = rememberNowMillis()
    val program = presentation.recipe?.let { stringResource(it.titleResource()) } ?: programText(presentation.program)
    val remaining = (presentation.remainingTime as? ObservedValue.Known)?.value
    val cycle = (presentation.cycle as? ObservedValue.Known)?.value
    AppCard(Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("status-card")) {
        when {
            CommandPolicy.idle(status) || cycle == ObservedCycle.REMOTE_CONTROL -> {
                val doorOpen = status["DoorState"] == "0"
                Overline(if (doorOpen) doorText(presentation.door) else stringResource(R.string.door_closed_state))
                Headline(stringResource(R.string.ready_to_dry))
                when {
                    doorOpen -> Hint(stringResource(R.string.door_open_hint))
                    status["StatoWiFi"] != "1" -> Hint(stringResource(R.string.remote_off_hint))
                }
            }
            cycle == ObservedCycle.DELAYED -> {
                val delay = status["DelVal"]?.toIntOrNull() ?: 0
                Overline(stringResource(R.string.state_delayed, program))
                BigValue(stringResource(R.string.start_at, clockText(now, delay)))
                Hint(stringResource(R.string.starts_in, durationText(delay)))
            }
            cycle == ObservedCycle.COMPLETED -> {
                Headline(stringResource(R.string.state_completed))
                Hint(stringResource(R.string.completed_hint))
            }
            cycle == ObservedCycle.FINISHING -> {
                Overline(stringResource(R.string.state_running, program))
                Headline(stringResource(R.string.state_finishing))
                Hint(stringResource(R.string.finishing_hint))
            }
            cycle == ObservedCycle.RUNNING || cycle == ObservedCycle.PAUSED -> {
                Overline(stringResource(if (cycle == ObservedCycle.PAUSED) R.string.state_paused else R.string.state_running, program))
                if (remaining != null) {
                    BigValue(durationText(remaining))
                    if (cycle == ObservedCycle.RUNNING) Hint(stringResource(R.string.finish_at, clockText(now, remaining)))
                } else Headline(program)
            }
            else -> {
                Overline(stringResource(R.string.state_busy))
                Headline(program)
                if (target == null) Hint(stringResource(R.string.state_panel_help))
            }
        }
        if (target != null) {
            val pause = DryerCommand.Pause(target)
            val resume = DryerCommand.Resume(target)
            val cancel = DryerCommand.Cancel(target)
            val visible = listOf(pause, resume, cancel).filter { CommandPolicy.blocked(it, status) == null }
            // Large text stacks the buttons so labels never break mid-word.
            val stacked = LocalDensity.current.fontScale > 1.3f
            if (visible.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                maxItemsInEachRow = if (stacked) 1 else visible.size) {
                visible.forEach { command ->
                    AppOutlinedButton(onClick = { if (command is DryerCommand.Cancel) onCancel(command) else onCommand(command) },
                        enabled = allowed(command), modifier = Modifier.weight(1f).testTag("command-${command::class.simpleName}")) {
                        Text(stringResource(when (command) {
                            is DryerCommand.Pause -> R.string.pause_cycle
                            is DryerCommand.Resume -> R.string.resume_cycle
                            else -> if (cycle == ObservedCycle.DELAYED) R.string.cancel_start else R.string.cancel_drying
                        }))
                    }
                }
            }
        }
    }
}

@Composable private fun Overline(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable private fun Headline(text: String) =
    Text(text, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.titleLarge)
@Composable private fun BigValue(text: String) =
    Text(text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.headlineMedium)
@Composable private fun Hint(text: String) =
    Text(text, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun CommandFeedback(state: CommandState, busy: Boolean, onCheck: () -> Unit) {
    var showDone by remember(state) { mutableStateOf(true) }
    if (state.phase == CommandPhase.CONFIRMED) LaunchedEffect(state) { delay(8_000); showDone = false }
    val modifier = Modifier.padding(top = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }
    when {
        state.busy -> Callout(modifier) {
            Text(stringResource(if (state.phase == CommandPhase.WAITING) R.string.command_waiting_short else state.messageResource()),
                style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        state.phase == CommandPhase.CONFIRMED && showDone -> Callout(modifier) {
            Text(stringResource(R.string.command_done), style = MaterialTheme.typography.bodyMedium)
        }
        state.phase == CommandPhase.UNKNOWN -> Callout(modifier.testTag("command-pending")) {
            Text(stringResource(R.string.command_pending_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.command_pending_body), style = MaterialTheme.typography.bodySmall)
            AppOutlinedButton(onClick = onCheck, enabled = !busy) { Text(stringResource(R.string.check_again)) }
        }
        state.phase == CommandPhase.REJECTED -> Callout(modifier) {
            Text(stringResource(state.messageResource()), style = MaterialTheme.typography.bodyMedium)
        }
        else -> {}
    }
}

@Composable
private fun MachineNotices(status: ru.bsv.candycmd.shared.protocol.DryerStatus?) {
    status ?: return
    if (status.presentation().filter is ObservedValue.Known) Callout(Modifier.padding(top = 12.dp)) {
        Text(stringResource(R.string.filter_reminder), style = MaterialTheme.typography.bodyMedium)
    }
    status["CodiceErrore"]?.takeIf { it != "0" }?.let {
        Callout(Modifier.padding(top = 12.dp), error = true) { Text(stringResource(R.string.machine_error_code, it)) }
    }
    status["WaterTankFull"]?.takeIf { it != "0" }?.let {
        Callout(Modifier.padding(top = 12.dp), error = true) { Text(stringResource(R.string.machine_tank_code, it)) }
    }
}

@Composable
private fun Favorites(presets: List<Preset>, message: String, actions: HomeActions) {
    var renaming by remember { mutableStateOf<Preset?>(null) }
    if (presets.isEmpty() && message.isEmpty()) return
    SectionLabel(stringResource(R.string.favorites))
    if (message.isNotEmpty()) Text(message, Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (presets.isNotEmpty()) ListCard {
        presets.forEachIndexed { index, preset ->
            val recipe = preset.settings.recipe()
            var menu by remember { mutableStateOf(false) }
            ListRow(preset.name, first = index == 0,
                subtitle = if (recipe == null) stringResource(R.string.preset_obsolete)
                    else "${stringResource(recipe.titleResource())} · ${preset.settings.summaryText()}",
                enabled = preset.usable, onClick = { actions.favorite(preset) }) {
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(painterResource(R.drawable.ic_more), stringResource(R.string.favorite_menu, preset.name),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        if (preset.usable) DropdownMenuItem(text = { Text(stringResource(R.string.rename_preset)) },
                            onClick = { menu = false; renaming = preset })
                        DropdownMenuItem(text = { Text(stringResource(R.string.delete_preset)) },
                            onClick = { menu = false; actions.delete(preset) })
                    }
                }
            }
        }
    }
    renaming?.let { preset ->
        var name by remember(preset) { mutableStateOf(preset.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text(stringResource(R.string.rename_preset)) },
            text = { OutlinedTextField(value = name, onValueChange = { if (it.length <= 60) name = it }, singleLine = true,
                shape = MaterialTheme.shapes.medium, label = { Text(stringResource(R.string.favorite_name)) }) },
            confirmButton = {
                AppTextButton(onClick = { actions.rename(preset, name); renaming = null },
                    enabled = name.isNotBlank() && name.none(Char::isISOControl)) { Text(stringResource(R.string.done)) }
            },
            dismissButton = { AppTextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } })
    }
}
