package app.smugly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smugly.ui.theme.SmuglyAccent
import app.smugly.ui.theme.SmuglyBg
import app.smugly.ui.theme.SmuglyButtonTextPrimary
import app.smugly.ui.theme.SmuglyCard
import app.smugly.ui.theme.SmuglyCardSoft
import app.smugly.ui.theme.SmuglyStroke
import app.smugly.ui.theme.SmuglyTextMuted
import app.smugly.ui.theme.SmuglyTextPrimary
import app.smugly.ui.theme.SmuglyTextSecondary

val SettingsCardRadius = 14.dp
val SettingsCardShape = RoundedCornerShape(SettingsCardRadius)
val SettingsRowPadH = 16.dp
val SettingsGroupGap = 22.dp

@Composable
fun SettingsGroupHeader(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    first: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = if (first) 10.dp else SettingsGroupGap, bottom = 10.dp)
            .heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = SmuglyAccent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            color = SmuglyTextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke(this)
    }
}

@Composable
fun SettingsGroup(
    title: String,
    icon: ImageVector,
    first: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsGroupHeader(title, icon, first = first)
    SettingsCard(content = content)
}

@Composable
fun SettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(SettingsCardShape)
            .background(SmuglyCard),
        content = content
    )
}

@Composable
fun SettingsDivider() {
    Box(
        Modifier
            .padding(horizontal = SettingsRowPadH)
            .fillMaxWidth()
            .height(1.dp)
            .background(SmuglyStroke)
    )
}

@Composable
fun SettingsRowText(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    subtitleColor: Color = SmuglyTextMuted
) {
    Column(modifier) {
        Text(title, color = SmuglyTextPrimary, fontSize = 15.sp, lineHeight = 19.sp)
        if (!subtitle.isNullOrEmpty()) {
            Text(
                subtitle,
                color = subtitleColor,
                fontSize = 12.5.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = SmuglyTextMuted,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.handClickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = SettingsRowPadH, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowText(title, subtitle, Modifier.weight(1f), subtitleColor)
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
fun SmuglySwitch(checked: Boolean) {
    Switch(
        checked = checked,
        onCheckedChange = null,
        colors = SwitchDefaults.colors(
            checkedThumbColor = SmuglyButtonTextPrimary,
            checkedTrackColor = SmuglyAccent,
            checkedBorderColor = SmuglyAccent,
            uncheckedThumbColor = SmuglyTextMuted,
            uncheckedTrackColor = SmuglyBg,
            uncheckedBorderColor = SmuglyTextMuted
        )
    )
}

@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsRow(title, subtitle, onClick = { onCheckedChange(!checked) }) {
        SmuglySwitch(checked)
    }
}

@Composable
fun SettingsFieldRow(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = SettingsRowPadH, vertical = 12.dp)) {
        SettingsRowText(title, subtitle, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun CompactButton(
    text: String,
    filled: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .height(36.dp)
            .clip(shape)
            .background(if (filled) SmuglyAccent else SmuglyCardSoft)
            .then(if (filled) Modifier else Modifier.border(1.dp, SmuglyStroke, shape))
            .handClickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = when {
                filled -> SmuglyButtonTextPrimary
                enabled -> SmuglyTextPrimary
                else -> SmuglyTextMuted
            },
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
fun SettingsDropdownRow(
    title: String,
    value: String,
    open: Boolean = false,
    onClick: (anchorBottomY: Int) -> Unit
) {
    val bottom = remember { intArrayOf(0) }
    Row(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { bottom[0] = (it.positionInRoot().y + it.size.height).toInt() }
            .handClickable { onClick(bottom[0]) }
            .heightIn(min = 56.dp)
            .padding(start = SettingsRowPadH, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettingsRowText(title, null, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = if (open) SmuglyAccent else SmuglyTextSecondary,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 210.dp)
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            Icons.Rounded.UnfoldMore,
            contentDescription = null,
            tint = SmuglyTextSecondary,
            modifier = Modifier.size(22.dp)
        )
    }
}
