package app.smugly.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smugly.S
import app.smugly.t
import app.smugly.trialDaysText
import app.smugly.ui.theme.SmuglyAccent
import app.smugly.ui.theme.SmuglyButtonTextPrimary
import app.smugly.ui.theme.SmuglyTextMuted
import app.smugly.ui.theme.SmuglyTextPrimary
import app.smugly.ui.theme.SmuglyTextSecondary

@Composable
private fun CenteredPanel(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.widthIn(max = 380.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) { content() }
    }
}

@Composable
private fun PanelTitle(text: String) {
    Text(
        text,
        color = SmuglyTextPrimary,
        fontSize = 22.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun PanelText(text: String, color: androidx.compose.ui.graphics.Color = SmuglyTextSecondary) {
    Text(
        text,
        color = color,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
    )
}

@Composable
private fun BigButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SmuglyAccent)
            .handClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = SmuglyButtonTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun QuietLink(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = SmuglyTextSecondary,
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .handClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

@Composable
fun WelcomePanel(
    trialDays: Int?,
    onBuy: () -> Unit,
    onSignIn: () -> Unit
) {
    CenteredPanel {
        PanelTitle(t(S.WELCOME_TITLE))
        PanelText(t(S.WELCOME_TEXT))
        if (trialDays != null && trialDays > 0) {
            PanelText(trialDaysText(trialDays), color = SmuglyTextPrimary)
        }
        Spacer(Modifier.height(26.dp))
        BigButton(t(S.BUY_SUBSCRIPTION), onBuy)
        Spacer(Modifier.height(4.dp))
        QuietLink(t(S.HAVE_ACCOUNT_SIGN_IN), onSignIn)
    }
}

@Composable
fun AccountNoPlanPanel(
    accountName: String,
    trialAvailable: Boolean,
    trialDays: Int,
    trialNeedsTelegram: Boolean,
    onOpenCabinet: () -> Unit
) {
    CenteredPanel {
        PanelTitle(t(S.ACCOUNT_NO_PLAN_TITLE))
        PanelText(t(S.ACCOUNT_NO_PLAN_TEXT))
        if (trialAvailable && trialDays > 0) {
            PanelText(
                if (trialNeedsTelegram) t(S.ACCOUNT_TRIAL_NEEDS_TELEGRAM) else trialDaysText(trialDays),
                color = SmuglyTextPrimary
            )
        }
        Spacer(Modifier.height(26.dp))
        BigButton(t(S.BUY_SUBSCRIPTION), onOpenCabinet)
        if (accountName.isNotBlank()) {
            Text(
                "${t(S.ACCOUNT_SIGNED_IN_AS)} $accountName",
                color = SmuglyTextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            )
        }
    }
}

@Composable
fun AccountPreparingPanel() {
    CenteredPanel {
        CircularProgressIndicator(
            modifier = Modifier.size(38.dp),
            color = SmuglyAccent,
            strokeWidth = 3.dp
        )
        Spacer(Modifier.height(18.dp))
        PanelText(t(S.ACCOUNT_PREPARING), color = SmuglyTextPrimary)
    }
}

@Composable
fun LinkWaitingDialog(
    visible: Boolean,
    onReopen: () -> Unit,
    onCancel: () -> Unit
) {
    AnimatedModalCard(
        visible = visible,
        onDismissRequest = {},
        modifier = Modifier.widthIn(max = 380.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                color = SmuglyAccent,
                strokeWidth = 2.5.dp
            )
            Text(
                t(S.LINK_WAITING_TITLE),
                color = SmuglyTextPrimary,
                fontSize = 17.sp,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        Text(
            t(S.LINK_WAITING_TEXT),
            color = SmuglyTextSecondary,
            fontSize = 14.sp,
            lineHeight = 19.sp,
            modifier = Modifier.padding(top = 12.dp, bottom = 16.dp)
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                t(S.CANCEL_BTN),
                color = SmuglyTextSecondary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .handClickable(onClick = onCancel)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Text(
                t(S.LINK_REOPEN_BTN),
                color = SmuglyAccent,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .handClickable(onClick = onReopen)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}
