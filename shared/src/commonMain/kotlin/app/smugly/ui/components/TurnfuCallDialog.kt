package app.smugly.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smugly.S
import app.smugly.t
import app.smugly.ui.theme.SmuglyAccent
import app.smugly.ui.theme.SmuglyTextPrimary
import app.smugly.ui.theme.SmuglyTextSecondary

@Composable
fun TurnfuCallDialog(
    visible: Boolean,
    link: String,
    onLink: (String) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit
) {
    AnimatedModalCard(
        visible = visible,
        onDismissRequest = onDismiss,
        modifier = Modifier.widthIn(max = 420.dp)
    ) {
        Text(t(S.TURNFU_CALL_TITLE), color = SmuglyTextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Normal)
        SmuglyTextField(
            value = link,
            onValueChange = onLink,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp)
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
                    .handClickable(onClick = onDismiss)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                t(S.TURNFU_CALL_CONNECT),
                color = SmuglyAccent,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .handClickable(onClick = onConnect)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}
