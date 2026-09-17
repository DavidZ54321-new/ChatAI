package com.zcw.chatai.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.zcw.chatai.ChatAiApp
import com.zcw.chatai.data.prefs.ImageDetail
import com.zcw.chatai.data.prefs.ReasoningEffort
import com.zcw.chatai.data.prefs.ThemeMode
import com.zcw.chatai.data.prefs.VisionOverride
import com.zcw.chatai.ui.theme.ChatTheme

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as ChatAiApp
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(app.settingsRepository, app.chatApi),
    )
    val state by viewModel.state.collectAsState()
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .statusBarsPadding()
            .imePadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = scheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("接口")
            Field(
                label = "Base URL",
                value = state.baseUrl,
                onValueChange = { value -> viewModel.update { it.copy(baseUrl = value) } },
                placeholder = "https://api.deepseek.com/v1",
                keyboardType = KeyboardType.Uri,
            )
            Field(
                label = "API Key",
                value = state.apiKey,
                onValueChange = { value -> viewModel.update { it.copy(apiKey = value) } },
                placeholder = "sk-…",
                keyboardType = KeyboardType.Password,
                masked = true,
            )
            Field(
                label = "模型",
                value = state.model,
                onValueChange = { value -> viewModel.update { it.copy(model = value) } },
                placeholder = "deepseek-flash",
            )
            if (state.models.isNotEmpty()) {
                ChipFlow(
                    options = state.models.map { it to it },
                    selected = state.model,
                    onSelect = { value -> viewModel.update { it.copy(model = value) } },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionPill(
                    label = if (state.busy) "连接中…" else "测试连接 / 拉取模型列表",
                    onClick = viewModel::testConnection,
                    enabled = !state.busy,
                )
                if (state.busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = scheme.primary,
                    )
                }
            }

            SectionTitle("生成")
            Field(
                label = "系统提示词",
                value = state.systemPrompt,
                onValueChange = { value -> viewModel.update { it.copy(systemPrompt = value) } },
                placeholder = "留空则不加 system 消息",
                singleLine = false,
            )
            ChoiceRow(
                label = "思考强度",
                hint = "用标准 reasoning_effort 字段；“跟随服务端”不发送该参数",
                options = ReasoningEffort.entries.map { it to it.label() },
                selected = state.reasoningEffort,
                onSelect = { value -> viewModel.update { it.copy(reasoningEffort = value) } },
            )
            Field(
                label = "回复长度上限 (max_tokens)",
                value = state.maxTokens,
                onValueChange = { value -> viewModel.update { it.copy(maxTokens = value) } },
                placeholder = "留空 = 服务端默认",
                keyboardType = KeyboardType.Number,
                error = state.maxTokensError,
            )
            Field(
                label = "温度 (temperature)",
                value = state.temperature,
                onValueChange = { value -> viewModel.update { it.copy(temperature = value) } },
                placeholder = "留空 = 服务端默认（思考模式下不生效）",
                keyboardType = KeyboardType.Decimal,
                error = state.temperatureError,
            )
            SwitchRow(
                label = "流式返回用量统计 (stream_options.include_usage)",
                checked = state.includeUsage,
                onCheckedChange = { value -> viewModel.update { it.copy(includeUsage = value) } },
            )

            SectionTitle("图片")
            ChoiceRow(
                label = "图片精度 (detail)",
                hint = "省流档会把图片缩到 512×512，实测输入 token 约为标准档的 1/5",
                options = ImageDetail.entries.map { it to it.label() },
                selected = state.imageDetail,
                onSelect = { value -> viewModel.update { it.copy(imageDetail = value) } },
            )
            ChoiceRow(
                label = "历史图片策略",
                hint = "更早的图片在请求里替换为占位符，避免每轮重发全部图片",
                options = listOf(
                    -1 to "全部重发",
                    2 to "最近 2 条",
                    5 to "最近 5 条",
                    0 to "不重发历史图片",
                ),
                selected = state.historyImageLimit,
                onSelect = { value -> viewModel.update { it.copy(historyImageLimit = value) } },
            )
            ChoiceRow(
                label = "视觉能力判断",
                hint = "自动 = 按模型名启发式提示；不支持图片的模型会静默忽略图片",
                options = VisionOverride.entries.map { it to it.label() },
                selected = state.visionOverride,
                onSelect = { value -> viewModel.update { it.copy(visionOverride = value) } },
            )

            SectionTitle("外观")
            ChoiceRow(
                label = "主题",
                hint = null,
                options = ThemeMode.entries.map { it to it.label() },
                selected = state.themeMode,
                onSelect = viewModel::setThemeMode,
            )

            SectionTitle("高级")
            Field(
                label = "附加请求参数 (JSON)",
                value = state.extraParams,
                onValueChange = { value -> viewModel.update { it.copy(extraParams = value) } },
                placeholder = "{\"top_k\": 20}",
                singleLine = false,
                error = state.extraParamsError,
            )
            Text(
                text = "这段 JSON 的顶层键会合并进请求体，用来适配各家的长尾参数；" +
                    "model / messages / stream 三个键受保护，不会被覆盖。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            state.error?.let { error ->
                MessageBar(text = error, tone = MessageTone.ERROR, onClick = viewModel::dismissMessages)
            }
            state.status?.let { status ->
                MessageBar(text = status, tone = MessageTone.SUCCESS, onClick = viewModel::dismissMessages)
            }

            Spacer(Modifier.height(4.dp))
        }

        // 状态条固定在保存按钮上方，滚动到哪儿都看得见
        state.error?.let { error ->
            MessageBar(
                text = error,
                tone = MessageTone.ERROR,
                onClick = viewModel::dismissMessages,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
        }
        state.status?.let { status ->
            MessageBar(
                text = status,
                tone = MessageTone.SUCCESS,
                onClick = viewModel::dismissMessages,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            ActionPill(
                label = "保存",
                onClick = viewModel::save,
                enabled = state.loaded && state.canSave,
                primary = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun ReasoningEffort.label(): String = when (this) {
    ReasoningEffort.FOLLOW_DEFAULT -> "跟随服务端"
    ReasoningEffort.OFF -> "关闭思考"
    ReasoningEffort.LOW -> "低"
    ReasoningEffort.HIGH -> "高"
    ReasoningEffort.MAX -> "最高"
}

private fun ImageDetail.label(): String = when (this) {
    ImageDetail.FOLLOW_DEFAULT -> "标准"
    ImageDetail.LOW -> "省流"
    ImageDetail.HIGH -> "高清"
}

private fun VisionOverride.label(): String = when (this) {
    VisionOverride.AUTO -> "自动"
    VisionOverride.SUPPORTED -> "支持图片"
    VisionOverride.UNSUPPORTED -> "不支持图片"
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    masked: Boolean = false,
    error: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            isError = error != null,
            placeholder = placeholder?.let { hint ->
                { Text(hint, style = MaterialTheme.typography.bodySmall) }
            },
            visualTransformation = if (masked) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(12.dp),
        )
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    label: String,
    hint: String?,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChipFlow(options = options, selected = selected, onSelect = onSelect)
        hint?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipFlow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (value, text) ->
            val isSelected = value == selected
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) scheme.onPrimary else scheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isSelected) scheme.primary else colors.chipBackground)
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

private enum class MessageTone { SUCCESS, ERROR }

@Composable
private fun MessageBar(
    text: String,
    tone: MessageTone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    val background = if (tone == MessageTone.ERROR) scheme.error.copy(alpha = 0.14f) else colors.accentTeal.copy(alpha = 0.18f)
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun ActionPill(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    val container = when {
        !enabled -> colors.chipBackground
        primary -> scheme.primary
        else -> colors.surfaceCard
    }
    val content = when {
        !enabled -> scheme.onSurfaceVariant
        primary -> scheme.onPrimary
        else -> scheme.onSurface
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(container)
            .border(
                width = if (primary || !enabled) 0.dp else 1.dp,
                color = if (primary || !enabled) Color.Transparent else colors.hairline,
                shape = RoundedCornerShape(14.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}
