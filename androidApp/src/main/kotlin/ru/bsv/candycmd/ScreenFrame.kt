package ru.bsv.candycmd

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset

internal val ScreenGutter = 20.dp
// Matches the horizontal content padding of Material text buttons.
private val TouchBleed = 12.dp

@Composable
internal fun AppGlyph(@DrawableRes resource: Int, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Icon(painterResource(resource), contentDescription = null, modifier = modifier.size(20.dp), tint = tint)
}

/** Grows the touch area by [horizontal] on both sides while keeping the content aligned to the layout edges. */
internal fun Modifier.bleed(horizontal: Dp) = layout { measurable, constraints ->
    val extra = horizontal.roundToPx() * 2
    val placeable = measurable.measure(constraints.offset(horizontal = extra))
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

/** Text button whose label, not its padding, lines up with the screen gutter. */
internal fun Modifier.alignedText() = bleed(TouchBleed)

// Material3 buttons default to a full pill and ignore theme shapes, so every button goes through these.
@Composable
internal fun AppButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                       content: @Composable RowScope.() -> Unit) =
    Button(onClick, modifier.heightIn(min = 52.dp), enabled, shape = MaterialTheme.shapes.medium, content = content)

@Composable
internal fun AppOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                               content: @Composable RowScope.() -> Unit) =
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled, shape = MaterialTheme.shapes.medium, content = content)

@Composable
internal fun AppTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                           colors: ButtonColors = ButtonDefaults.textButtonColors(),
                           content: @Composable RowScope.() -> Unit) =
    TextButton(onClick, modifier, enabled, shape = MaterialTheme.shapes.medium, colors = colors, content = content)

/** Scrollable screen with one gutter; [bottomBar] stays pinned above the navigation bar and keyboard. */
@Composable
internal fun ScreenFrame(
    header: @Composable () -> Unit,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        if (bottomBar != null) Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(horizontal = ScreenGutter, vertical = 12.dp)) { bottomBar() }
        }
    }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding()) {
            val viewport = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(Modifier.heightIn(min = viewport).padding(horizontal = ScreenGutter).padding(top = 16.dp, bottom = 20.dp)) {
                    header()
                    content()
                }
            }
        }
    }
}

internal enum class LinkState { ONLINE, CONNECTING, SLEEP, NO_WIFI }

@Composable
internal fun HomeHeader(link: LinkState, onDryer: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.my_dryer), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.dryer_family), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusChip(link, onDryer)
        IconButton(onClick = onDryer, modifier = Modifier.bleed(TouchBleed)) {
            Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.open_dryer),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun StatusChip(link: LinkState, onClick: (() -> Unit)? = null) {
    val online = link == LinkState.ONLINE
    val container = if (online) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (online) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(when (link) {
        LinkState.ONLINE -> R.string.chip_online
        LinkState.CONNECTING -> R.string.chip_connecting
        LinkState.SLEEP -> R.string.chip_sleep
        LinkState.NO_WIFI -> R.string.chip_no_wifi
    })
    val body: @Composable () -> Unit = {
        Row(Modifier.heightIn(min = 32.dp).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AppGlyph(if (link == LinkState.SLEEP) R.drawable.ic_moon else R.drawable.ic_wifi, Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.bodySmall)
        }
    }
    // Visual chip is 32dp; a clickable Surface still reserves a 48dp touch target.
    if (onClick != null) Surface(onClick = onClick, shape = MaterialTheme.shapes.small, color = container, contentColor = content) { body() }
    else Surface(shape = MaterialTheme.shapes.small, color = container, contentColor = content) { body() }
}

/** Sub-screen header: back on the left, optional close on the right, both aligned to the gutter. */
@Composable
internal fun BackHeader(onBack: () -> Unit, onClose: (() -> Unit)? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        AppTextButton(onClick = onBack, enabled = enabled, modifier = Modifier.alignedText()) {
            AppGlyph(R.drawable.ic_arrow_back)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.back))
        }
        Spacer(Modifier.weight(1f))
        if (onClose != null) AppTextButton(onClick = onClose, enabled = enabled, modifier = Modifier.alignedText()) {
            Text(stringResource(R.string.close_picker))
        }
    }
}

@Composable
internal fun ScreenTitle(text: String, overline: String? = null) {
    Column(Modifier.padding(top = 4.dp, bottom = 16.dp)) {
        overline?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 20.dp, bottom = 8.dp).semantics { heading() }, style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun AppCard(modifier: Modifier = Modifier, tonal: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = if (tonal) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        border = if (tonal) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** Grouped list; rows separate themselves, so callers pass [ListRow]s with `first` set on the top one. */
@Composable
internal fun ListCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(content = content) }
}

@Composable
internal fun ListRow(
    title: String,
    first: Boolean = false,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    if (!first) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val clickable = if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
    Row(Modifier.fillMaxWidth().then(clickable).heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled) color else color.copy(alpha = 0.38f), style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        // Long values wrap inside their half instead of squeezing the title.
        value?.let { Text(it, Modifier.weight(1.5f), textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        when {
            trailing != null -> trailing()
            onClick != null -> AppGlyph(R.drawable.ic_chevron_right, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Calm inline notice; error tone is reserved for faults reported by the machine itself. */
@Composable
internal fun Callout(modifier: Modifier = Modifier, error: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content)
    }
}

/** Full-width expandable row; the pressed highlight extends past the gutter so the label keeps its inset. */
@Composable
internal fun Disclosure(title: String, expanded: Boolean, onClick: () -> Unit, enabled: Boolean = true,
                        @DrawableRes icon: Int? = null, muted: Boolean = false) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        muted -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Row(Modifier.fillMaxWidth().bleed(TouchBleed).clip(MaterialTheme.shapes.medium)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .heightIn(min = 48.dp).padding(horizontal = TouchBleed, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        icon?.let { AppGlyph(it, tint = color) }
        Text(title, Modifier.weight(1f), color = color,
            style = if (muted) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium)
        AppGlyph(if (expanded) R.drawable.ic_chevron_up else R.drawable.ic_chevron_down, tint = color)
    }
}
