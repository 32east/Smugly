package app.smugly.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.foundation.Image
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smugly.PerAppMode
import app.smugly.S
import app.smugly.t
import app.smugly.ui.InstalledApp
import app.smugly.ui.components.AccentLinkButton
import app.smugly.ui.components.HintText
import app.smugly.ui.components.PillSelector
import app.smugly.ui.components.SmuglyCheckbox
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
import kotlinx.coroutines.withContext

/** Icon box side; the row's height follows from it, the way the profile card's does. */
private val PerAppIconSize = 38.dp

/** Height of the "show system apps / clear" line, so it cannot change with the selection. */
private val PerAppFilterRowHeight = 44.dp

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

    // Selected apps first, always, and against the live selection — ticking a box moves that
    // row to the top on the spot. The rows carry a placement animation, so it travels there
    // instead of teleporting, which is what makes a list that re-orders itself readable.
    val visible = remember(apps, query, showSystem, selected, stickyVisible) {
        val needle = query.trim().lowercase()
        apps.asSequence()
            .filter {
                showSystem || !it.isSystem ||
                    it.packageName in selected || it.packageName in stickyVisible
            }
            .filter {
                needle.isEmpty() ||
                    it.label.lowercase().contains(needle) ||
                    it.packageName.lowercase().contains(needle)
            }
            .sortedWith(
                compareBy<InstalledApp> { if (it.packageName in selected) 0 else 1 }
                    .thenBy { it.label.lowercase() }
                    .thenBy { it.packageName }
            )
            .toList()
    }

    Column(Modifier.fillMaxSize().background(SmuglyBg)) {
        TopBar(title = t(S.PER_APP), onMenu = onMenu)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 24.dp)
        ) {
            item(key = "mode") {
                PillSelector(
                    options = listOf(
                        t(S.PER_APP_MODE_OFF),
                        t(S.PER_APP_MODE_ALLOWED),
                        t(S.PER_APP_MODE_DISALLOWED)
                    ),
                    selectedIndex = PerAppModes.indexOf(mode).coerceAtLeast(0),
                    onSelected = { onModeChange(PerAppModes[it]) }
                )
                Spacer(Modifier.height(8.dp))
                // What the current mode actually does, in words. The two non-off modes are
                // each other's mirror image and the labels alone are easy to read backwards.
                HintText(
                    when {
                        mode == PerAppMode.ALLOWED && selected.isEmpty() ->
                            t(S.PER_APP_HINT_EMPTY_ALLOWED)
                        mode == PerAppMode.ALLOWED -> t(S.PER_APP_HINT_ALLOWED)
                        mode == PerAppMode.DISALLOWED -> t(S.PER_APP_HINT_DISALLOWED)
                        else -> t(S.PER_APP_HINT_OFF)
                    }
                )
                HintText(t(S.PER_APP_APPLIES_ON_RECONNECT))
                Spacer(Modifier.height(6.dp))
                SmuglyTextField(
                    value = query,
                    onValueChange = { query = it },
                    hint = t(S.PER_APP_SEARCH_HINT)
                )
                Spacer(Modifier.height(4.dp))
                // Both controls on one line, and the line's height is pinned: "clear" is a
                // taller hit target than the checkbox and it only exists once something is
                // selected, so a self-sizing row would grow the moment the first box was
                // ticked and push the whole list down.
                Row(
                    Modifier.fillMaxWidth().height(PerAppFilterRowHeight),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SmuglyCheckbox(
                        showSystem,
                        t(S.PER_APP_SHOW_SYSTEM),
                        Modifier.weight(1f)
                    ) { showSystem = it }
                    if (selected.isNotEmpty()) {
                        AccentLinkButton(t(S.PER_APP_CLEAR)) { onSelectedChange(emptySet()) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            if (loading) {
                item(key = "loading") {
                    Text(
                        t(S.PER_APP_LOADING),
                        color = SmuglyTextMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                    )
                }
            } else if (visible.isEmpty()) {
                item(key = "empty") {
                    Text(
                        t(S.PER_APP_NOTHING_FOUND),
                        color = SmuglyTextMuted,
                        fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp)
                    )
                }
            } else {
                items(visible, key = { it.packageName }) { app ->
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
                        checked = app.packageName in selected,
                        onToggle = { on ->
                            stickyVisible = stickyVisible + app.packageName
                            onSelectedChange(
                                if (on) selected + app.packageName else selected - app.packageName
                            )
                        },
                        // No fade: the row is not appearing or leaving, it is moving. Fading
                        // it as well would read as one row vanishing and another arriving.
                        modifier = Modifier.animateItem(
                            fadeInSpec = null,
                            fadeOutSpec = null,
                            placementSpec = tween(220, easing = FastOutSlowInEasing)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    icon: ImageBitmap?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .handClickable { onToggle(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(PerAppIconSize)
                .clip(RoundedCornerShape(9.dp))
                .background(SmuglyCard),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Image(
                    painter = BitmapPainter(icon, filterQuality = FilterQuality.Medium),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(PerAppIconSize)
                )
            } else {
                // No icon yet (or none at all): the app's initial, rather than a placeholder
                // glyph that would read as "this app is broken".
                Text(
                    app.label.take(1).uppercase(),
                    color = SmuglyTextMuted,
                    fontSize = 15.sp
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                color = SmuglyTextPrimary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                app.packageName,
                color = SmuglyTextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(6.dp))
        Checkbox(
            checked = checked,
            onCheckedChange = onToggle,
            modifier = Modifier.size(32.dp),
            colors = CheckboxDefaults.colors(
                checkedColor = SmuglyAccent,
                uncheckedColor = SmuglyTextMuted,
                checkmarkColor = SmuglyButtonTextPrimary
            )
        )
    }
}
