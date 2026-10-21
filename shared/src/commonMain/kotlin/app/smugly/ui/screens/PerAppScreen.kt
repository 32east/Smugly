package app.smugly.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.ui.unit.IntOffset
import app.smugly.ui.components.CompactButton
import app.smugly.ui.components.SettingsCard
import app.smugly.ui.components.SettingsGroupHeader
import app.smugly.ui.components.SettingsRowPadH
import app.smugly.ui.components.SettingsSwitchRow
import app.smugly.ui.theme.SmuglyCardSoft
import app.smugly.ui.theme.SmuglyTextSecondary
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smugly.PerAppMode
import app.smugly.S
import app.smugly.t
import app.smugly.ui.InstalledApp
import app.smugly.ui.components.PillSelector
import app.smugly.ui.components.SmuglyTextField
import app.smugly.ui.components.TopBar
import app.smugly.ui.components.handClickable
import app.smugly.ui.theme.SmuglyAccent
import app.smugly.ui.theme.SmuglyBg
import app.smugly.ui.theme.SmuglyButtonTextPrimary
import app.smugly.ui.theme.SmuglyCard
import app.smugly.ui.theme.SmuglyTextMuted
import app.smugly.ui.theme.SmuglyTextPrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Icon box side; the row's height follows from it, the way the profile card's does. */
private val PerAppIconSize = 38.dp

private val PerAppRowGap = 6.dp
private const val PerAppRearrangeDelayMs = 400L

private val PerAppModes = listOf(PerAppMode.OFF, PerAppMode.ALLOWED, PerAppMode.DISALLOWED)

/**
 * Split tunnelling: pick the apps, pick what "picked" means.
 *
 * The mode and the selection are one global setting applied to the TUN, so this screen is the
 * same for every protocol — nothing here knows or cares whether the traffic will leave over
 * DNS, S3, a CDN or Xray.
 */
@Composable
fun PerAppScreen(
    apps: List<InstalledApp>,
    loading: Boolean,
    mode: PerAppMode,
    selected: Set<String>,
    onMenu: () -> Unit,
    onModeChange: (PerAppMode) -> Unit,
    onSelectedChange: (Set<String>) -> Unit,
    /** Decodes one launcher icon. Called off the UI thread, once per package. */
    loadIcon: (String) -> ImageBitmap?
) {
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    /**
     * Packages that must stay on screen even once they stop matching the filters.
     *
     * Only unticking a *system* app while system apps are hidden can do that, and a row that
     * disappears out from under the finger reads as a crash, not as a filter. Anything the
     * user has touched stays put until the screen is left.
     */
    var stickyVisible by remember { mutableStateOf(emptySet<String>()) }

    val icons = remember { mutableStateMapOf<String, ImageBitmap?>() }

    var arranged by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected != arranged) {
            delay(PerAppRearrangeDelayMs)
            arranged = selected
        }
    }

    // Selected apps first, always.
    val visible = remember(apps, query, showSystem, selected, arranged, stickyVisible) {
        val needle = query.trim().lowercase()
        apps.asSequence()
            .filter {
                showSystem || !it.isSystem || it.packageName in selected ||
                    it.packageName in arranged || it.packageName in stickyVisible
            }
            .filter {
                needle.isEmpty() ||
                    it.label.lowercase().contains(needle) ||
                    it.packageName.lowercase().contains(needle)
            }
            .sortedWith(
                compareBy<InstalledApp> { if (it.packageName in arranged) 0 else 1 }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.packageName }
            )
            .toList()
    }

    val selectedRows = visible.filter { it.packageName in arranged }
    val otherRows = visible.filter { it.packageName !in arranged }
    val placement = tween<IntOffset>(240, easing = FastOutSlowInEasing)
    val appear = tween<Float>(180, delayMillis = 60)
    val vanish = tween<Float>(100)

    fun LazyItemScope.motion(): Modifier =
        Modifier.animateItem(fadeInSpec = appear, placementSpec = placement, fadeOutSpec = vanish)

    fun LazyListScope.appRows(section: String, rows: List<InstalledApp>) {
        items(rows, key = { "$section:${it.packageName}" }) { app ->
            // The map is a snapshot state map, so writing the decoded icon into it
            // recomposes just this row — and a package already in it (even as a null
            // "has no icon") never gets decoded twice while the screen is up.
            LaunchedEffect(app.packageName) {
                if (!icons.containsKey(app.packageName)) {
                    val bitmap = withContext(Dispatchers.Default) { loadIcon(app.packageName) }
                    icons[app.packageName] = bitmap
                }
            }
            AppRow(
                app = app,
                icon = icons[app.packageName],
                iconResolved = icons.containsKey(app.packageName),
                checked = app.packageName in selected,
                onToggle = { on ->
                    stickyVisible = stickyVisible + app.packageName
                    onSelectedChange(
                        if (on) selected + app.packageName else selected - app.packageName
                    )
                },
                modifier = motion()
            )
        }
    }

    Column(Modifier.fillMaxSize().background(SmuglyBg)) {
        TopBar(title = t(S.PER_APP), onMenu = onMenu)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 24.dp)
        ) {
            item(key = "mode") {
                SettingsCard {
                    Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 12.dp)) {
                        PillSelector(
                            options = listOf(
                                t(S.PER_APP_MODE_OFF),
                                t(S.PER_APP_MODE_ALLOWED),
                                t(S.PER_APP_MODE_DISALLOWED)
                            ),
                            selectedIndex = PerAppModes.indexOf(mode).coerceAtLeast(0),
                            trackColor = SmuglyBg,
                            onSelected = { onModeChange(PerAppModes[it]) }
                        )
                        // What the current mode actually does, in words. The two non-off modes are
                        // each other's mirror image and the labels alone are easy to read backwards.
                        Text(
                            when {
                                mode == PerAppMode.ALLOWED && selected.isEmpty() ->
                                    t(S.PER_APP_HINT_EMPTY_ALLOWED)
                                mode == PerAppMode.ALLOWED -> t(S.PER_APP_HINT_ALLOWED)
                                mode == PerAppMode.DISALLOWED -> t(S.PER_APP_HINT_DISALLOWED)
                                else -> t(S.PER_APP_HINT_OFF)
                            },
                            color = SmuglyTextMuted,
                            fontSize = 12.5.sp,
                            lineHeight = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                SmuglyTextField(
                    value = query,
                    onValueChange = { query = it },
                    hint = t(S.PER_APP_SEARCH_HINT),
                    leading = {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = null,
                            tint = SmuglyTextMuted,
                            modifier = Modifier.size(20.dp)
                        )
                    },
                    trailing = if (query.isEmpty()) null else {
                        {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .handClickable { query = "" },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.Close,
                                    contentDescription = t(S.PER_APP_SEARCH_CLEAR),
                                    tint = SmuglyTextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
                SettingsCard {
                    SettingsSwitchRow(t(S.PER_APP_SHOW_SYSTEM), showSystem) { showSystem = it }
                }
            }
            if (loading && visible.isEmpty()) {
                item(key = "loading") {
                    Box(
                        motion().fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(34.dp),
                            color = SmuglyAccent,
                            strokeWidth = 3.dp
                        )
                    }
                }
            } else if (visible.isEmpty()) {
                item(key = "status-header") {
                    SettingsGroupHeader(t(S.PER_APP_SECTION_APPS), Icons.Rounded.Apps, modifier = motion())
                }
                item(key = "empty") {
                    SettingsCard(motion()) {
                        Text(
                            t(S.PER_APP_NOTHING_FOUND),
                            color = SmuglyTextMuted,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = SettingsRowPadH, vertical = 20.dp)
                        )
                    }
                }
            }
            if (selectedRows.isNotEmpty()) {
                item(key = "selected-header") {
                    SettingsGroupHeader(
                        "${t(S.PER_APP_SECTION_SELECTED)} · ${selectedRows.size}",
                        Icons.Rounded.CheckCircle,
                        modifier = motion(),
                        trailing = { CompactButton(t(S.PER_APP_CLEAR)) { onSelectedChange(emptySet()) } }
                    )
                }
                appRows("sel", selectedRows)
            }
            if (otherRows.isNotEmpty()) {
                item(key = "apps-header") {
                    SettingsGroupHeader(
                        "${t(S.PER_APP_SECTION_APPS)} · ${otherRows.size}",
                        Icons.Rounded.Apps,
                        modifier = motion()
                    )
                }
                appRows("app", otherRows)
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    icon: ImageBitmap?,
    iconResolved: Boolean,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val iconAlpha by animateFloatAsState(
        targetValue = if (icon != null) 1f else 0f,
        animationSpec = tween(160),
        label = "appIcon"
    )
    Row(
        modifier = modifier
            .padding(bottom = PerAppRowGap)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SmuglyCard)
            .handClickable { onToggle(!checked) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(PerAppIconSize)
                .clip(RoundedCornerShape(9.dp))
                .background(SmuglyCardSoft),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Image(
                    painter = BitmapPainter(icon, filterQuality = FilterQuality.Medium),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(PerAppIconSize).graphicsLayer { alpha = iconAlpha }
                )
            } else if (iconResolved) {
                // No icon yet (or none at all): the app's initial, rather than a placeholder
                // glyph that would read as "this app is broken".
                Text(
                    app.label.take(1).uppercase(),
                    color = SmuglyTextMuted,
                    fontSize = 15.sp
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                color = SmuglyTextPrimary,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                color = SmuglyTextMuted,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.size(32.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = SmuglyAccent,
                uncheckedColor = SmuglyTextMuted,
                checkmarkColor = SmuglyButtonTextPrimary
            )
        )
    }
}
