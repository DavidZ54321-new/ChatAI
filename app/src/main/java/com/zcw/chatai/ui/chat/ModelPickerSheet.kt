package com.zcw.chatai.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zcw.chatai.ChatAiApp
import com.zcw.chatai.ui.theme.ChatTheme
import kotlinx.coroutines.flow.first

/**
 * 模型选择：优先用标准 `GET /models` 拉到的列表，也允许手填（兼容没实现该端点的服务）。
 * 选择会写到**当前会话**上（conversations.model），不影响其它会话。
 */
@Composable
fun ModelPickerSheet(
    currentModel: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as ChatAiApp
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { app.chatApi.listModels(app.settingsRepository.chatConfig().first()) }
            .onSuccess { models = it }
            .onFailure { error = it.message }
        loading = false
    }

    DarkSheet(onDismiss = onDismiss) {
        Text(
            text = "选择模型",
            style = MaterialTheme.typography.titleMedium,
            color = ChatTheme.colors.codeOnBackground,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Text(
            text = "当前：${currentModel.ifBlank { "未设置" }}",
            style = MaterialTheme.typography.labelMedium,
            color = ChatTheme.colors.codeHeaderText,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        when {
            loading -> Text(
                text = "正在拉取模型列表…",
                style = MaterialTheme.typography.bodyMedium,
                color = ChatTheme.colors.codeHeaderText,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )

            else -> {
                models.forEach { model ->
                    SheetAction(
                        label = if (model == currentModel) "$model（当前）" else model,
                        onClick = { onSelect(model) },
                    )
                }
                error?.let {
                    Text(
                        text = "拉取失败：$it",
                        style = MaterialTheme.typography.bodySmall,
                        color = ChatTheme.colors.accentAmber,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                singleLine = true,
                label = { Text("手动输入模型名") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "使用该模型",
                    style = MaterialTheme.typography.labelLarge,
                    color = ChatTheme.colors.codeOnBackground,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .clickable { if (manual.isNotBlank()) onSelect(manual.trim()) },
                )
                Text(
                    text = "服务与密钥设置",
                    style = MaterialTheme.typography.labelLarge,
                    color = ChatTheme.colors.codeHeaderText,
                    modifier = Modifier
                        .padding(vertical = 8.dp)
                        .clickable(onClick = onOpenSettings),
                )
            }
        }
    }
}
