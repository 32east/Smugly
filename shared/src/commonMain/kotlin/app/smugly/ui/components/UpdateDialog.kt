package app.smugly.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smugly.AppRelease
import app.smugly.S
import app.smugly.UpdateStatus
import app.smugly.t
import app.smugly.ui.theme.SmuglyAccent
import app.smugly.ui.theme.SmuglyCardSoft
import app.smugly.ui.theme.SmuglyTextMuted
import app.smugly.ui.theme.SmuglyTextPrimary
import app.smugly.ui.theme.SmuglyTextSecondary

/**
 * The one prompt the updater ever shows: "there is a newer build", then the download, then
 * "open the installer".
 *
 * All three are the same card rather than three dialogs — the download is a step inside the
 * decision the user already made, not a new question, so the card stays put and only its
 * buttons change.
 *
 * [release] is passed separately from [status] so the card keeps its content while it animates
 * out: [AnimatedModalCard] plays the exit itself, and reading the release out of a status that
 * has already gone back to Idle would blank the text mid-animation.
 */
@Composable
fun UpdateDialog(
    visible: Boolean,
    status: UpdateStatus,
    release: AppRelease?,
    currentVersion: String,
    onUpdate: () -> Unit,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit
) {
    val downloading = status as? UpdateStatus.Downloading
    val ready = status is UpdateStatus.ReadyToInstall
    AnimatedModalCard(
        visible = visible,
        // While bytes are moving, a tap outside must not look like "cancel" — the download
        // would carry on regardless and the card would come back at ReadyToInstall.
        onDismissRequest = { if (downloading == null) onDismiss() },
        modifier = Modifier.widthIn(max = 380.dp)
    ) {
        Text(
            t(if (ready) S.UPDATE_READY_TITLE else S.UPDATE_AVAILABLE_TITLE),
            color = SmuglyTextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Normal
        )
        Text(
            "$currentVersion → ${release?.versionName.orEmpty()}",
            color = SmuglyAccent,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 6.dp)
        )
        val notes = release?.notes.orEmpty()
        if (notes.isNotBlank()) {
            Text(
                notes,
                color = SmuglyTextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier
                    .padding(top = 10.dp)
                    // Release notes are written by whoever cut the release and can be any
                    // length; cap the card and let the notes scroll inside it.
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
            )
        }
        if (downloading != null) {
            ProgressBar(downloading, modifier = Modifier.padding(top = 14.dp))
        } else if (ready) {
            Text(
                t(S.UPDATE_READY_MESSAGE),
                color = SmuglyTextMuted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (downloading == null) {
                Text(
                    t(S.UPDATE_SKIP_BTN),
                    color = SmuglyTextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .handClickable(onClick = onSkip)
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    t(if (ready) S.UPDATE_INSTALL_BTN else S.UPDATE_NOW_BTN),
                    color = SmuglyAccent,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .handClickable(onClick = if (ready) onInstall else onUpdate)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun ProgressBar(status: UpdateStatus.Downloading, modifier: Modifier = Modifier) {
    val fraction = status.fraction
    // Animated so the bar travels between progress updates instead of stepping once a percent.
    val width by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = tween(200),
        label = "updateProgress"
    )
    Text(
        buildString {
            append(t(S.UPDATE_DOWNLOADING))
            // No total means no percentage: the server sent no Content-Length, and inventing
            // one would be a number the app does not have.
            if (fraction != null) append("  ${(fraction * 100).toInt()}%")
        },
        color = SmuglyTextSecondary,
        fontSize = 13.sp,
        modifier = modifier
    )
    Box(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(SmuglyCardSoft)
    ) {
        if (fraction != null) {
            Box(
                Modifier
                    .fillMaxWidth(width)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(SmuglyAccent)
            )
        }
    }
}
