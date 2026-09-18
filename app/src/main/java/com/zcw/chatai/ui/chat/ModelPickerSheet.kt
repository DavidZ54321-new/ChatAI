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

/** 列表很长（Qwen 有 200+ 个模型），不筛选时只展示前 N 个，避免一次性铺满整屏。 */
private const val MAX_VISIBLE_MODELS = 40

/**
 * 模型选择：优先用标准 `GET /models` 拉到的列表，也允许手填（兼容没实现该端点的服务）。
 * 选择会写到**当前会话**上（conversations.model），不影响其它会话。
 * 拉取列表用的是**会话绑定供应商**的连接配置。
 */
@Composable
fun ModelPickerSheet(
    currentModel: String,
    providerId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as ChatAiApp
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(providerId) {
        runCatching { app.chatApi.listModels(app.settingsRepository.chatConfig(providerId).first()) }
            .onSuccess { models = it }
            .onFailure { error = it.message }
        loading = false
    }

    val filtered = remember(models, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) models.take(MAX_VISIBLE_MODELS)
        else models.filter { it.contains(keyword, ignoreCase = true) }
    }

    DarkSheet(onDismiss = onDismiss, scroll = true) {
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text(if (models.isEmpty()) "筛选模型" else "筛选模型（共 ${models.size} 个）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            loading -> Text(
                text = "正在拉取模型列表…",
                style = MaterialTheme.typography.bodyMedium,
                color = ChatTheme.colors.codeHeaderText,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )

            else -> {
                filtered.forEach { model ->
                    SheetAction(
                        label = if (model == currentModel) "$model（当前）" else model,
                        onClick = { onSelect(model) },
                    )
                }
                if (query.isBlank() && models.size > MAX_VISIBLE_MODELS) {
                    Text(
                        text = "共 ${models.size} 个，已显示前 $MAX_VISIBLE_MODELS 个；输入关键词可筛选",
                        style = MaterialTheme.typography.bodySmall,
                        color = ChatTheme.colors.codeHeaderText,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
                if (filtered.isEmpty() && query.isNotBlank()) {
                    Text(
                        text = "没有匹配的模型",
                        style = MaterialTheme.typography.bodySmall,
                        color = ChatTheme.colors.codeHeaderText,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
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
