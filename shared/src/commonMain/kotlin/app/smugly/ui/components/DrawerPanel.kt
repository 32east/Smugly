package app.smugly.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smugly.AppVersion
import app.smugly.S
import app.smugly.t
import app.smugly.ui.theme.SmuglyAccent
import app.smugly.ui.theme.SmuglyAccentPressed
import app.smugly.ui.theme.SmuglyButtonTextPrimary
import app.smugly.ui.theme.SmuglyCard
import app.smugly.ui.theme.SmuglyCardSoft
import app.smugly.ui.theme.SmuglyTextMuted
import app.smugly.ui.theme.SmuglyTextPrimary
import app.smugly.ui.theme.SmuglyTextSecondary

private val DrawerRowHeight = 52.dp
private val DrawerRowGap = 4.dp
private val DrawerRowShape = RoundedCornerShape(14.dp)
private val DrawerIconSize = 22.dp
private const val DrawerRevealMs = 120

@Composable
fun DrawerPanel(
    selected: AppDrawerItem,
    showDiagnostics: Boolean,
    onSelect: (AppDrawerItem) -> Unit,
    modifier: Modifier = Modifier,
    showPerApp: Boolean = false,
    showWebCabinet: Boolean = false,
    versionLabel: String = AppVersion.name + " · " + AppVersion.buildDate
) {
    val items = buildList {
        add(AppDrawerItem.HOME)
        if (showWebCabinet) add(AppDrawerItem.WEB_CABINET)
        if (showPerApp) add(AppDrawerItem.PER_APP)
        if (showDiagnostics) add(AppDrawerItem.DIAGNOSTICS)
        add(AppDrawerItem.SETTINGS)
    }
    Column(
        modifier = modifier
            .background(SmuglyCard)
            .padding(top = 20.dp, bottom = 16.dp, start = 12.dp, end = 12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(DrawerRowGap)
        ) {
            items.forEach { item ->
                DrawerRow(
                    item = item,
                    isSelected = item == selected,
                    onClick = { onSelect(item) }
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                t(S.APP_NAME),
                color = SmuglyTextPrimary,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp
            )
            Text(
                versionLabel,
                color = SmuglyTextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun DrawerRow(
    item: AppDrawerItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember(item) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val reveal = remember(item) { Animatable(if (isSelected) 1f else 0f) }
    var wasSelected by remember(item) { mutableStateOf(isSelected) }
    LaunchedEffect(isSelected, pressed) {
        val deselected = wasSelected && !isSelected
        wasSelected = isSelected
        when {
            isSelected || pressed ->
                reveal.animateTo(1f, tween(DrawerRevealMs, easing = LinearOutSlowInEasing))
            deselected -> reveal.snapTo(0f)
            else -> reveal.animateTo(0f, tween(DrawerRevealMs, easing = LinearOutSlowInEasing))
        }
    }
    val progress = reveal.value
    val labelColor = lerp(SmuglyTextPrimary, SmuglyButtonTextPrimary, progress)
    val iconColor = lerp(SmuglyTextSecondary, SmuglyButtonTextPrimary, progress)
    val restingBg by animateColorAsState(
        if (hovered && !isSelected) SmuglyCardSoft.copy(alpha = 0.7f) else Color.Transparent,
        tween(120)
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(DrawerRowHeight)
            .clip(DrawerRowShape)
            .background(restingBg)
            .drawBehind {
                val shown = reveal.value
                if (size.width * shown < 1f) return@drawBehind
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to SmuglyAccent,
                        0.4f to SmuglyAccentPressed,
                        0.92f to SmuglyCard,
                        startX = 0f,
                        endX = size.width * shown
                    )
                )
            }
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = item.icon(),
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(DrawerIconSize)
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = item.label(),
            color = labelColor,
            fontWeight = FontWeight.Normal,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (item == AppDrawerItem.WEB_CABINET) {
            Box(Modifier.padding(start = 8.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = SmuglyTextMuted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

enum class AppDrawerItem {
    HOME, WEB_CABINET, PER_APP, DIAGNOSTICS, SETTINGS;

    @Composable
    fun label(): String = when (this) {
        HOME -> t(S.HOME)
        WEB_CABINET -> t(S.WEB_CABINET)
        PER_APP -> t(S.PER_APP)
        DIAGNOSTICS -> t(S.DIAGNOSTICS)
        SETTINGS -> t(S.SETTINGS)
    }

    fun icon(): ImageVector = when (this) {
        HOME -> Icons.Rounded.Home
        WEB_CABINET -> Icons.Rounded.AccountCircle
        PER_APP -> Icons.Rounded.AltRoute
        DIAGNOSTICS -> Icons.Rounded.MonitorHeart
        SETTINGS -> Icons.Rounded.Settings
    }
}
