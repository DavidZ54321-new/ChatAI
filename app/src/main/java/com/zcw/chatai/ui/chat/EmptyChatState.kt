package com.zcw.chatai.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zcw.chatai.ui.theme.SpikeMark
import java.time.LocalTime
import kotlinx.coroutines.delay

@Composable
fun EmptyChatState(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SpikeMark(size = 30.dp, color = scheme.onSurface)
        Spacer(Modifier.height(16.dp))
        Text(
            text = rememberGreeting(),
            style = MaterialTheme.typography.displaySmall,
            color = scheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/** 停在空会话上跨过整点时，等到下一个问候边界再换文案。 */
@Composable
private fun rememberGreeting(): String {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(now) {
        delay(TimeGreeting.millisUntilNextBoundary(now))
        now = LocalTime.now()
    }
    return TimeGreeting.of(now.hour, now.minute)
}
