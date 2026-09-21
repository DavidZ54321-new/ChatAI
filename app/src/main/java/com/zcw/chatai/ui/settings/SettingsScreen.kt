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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import com.zcw.chatai.ChatAiApp
import com.zcw.chatai.data.prefs.ImageDetail
import com.zcw.chatai.data.prefs.ThemeMode
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.provider.ProviderEntry
import com.zcw.chatai.ui.common.ModelAutocompleteField
import com.zcw.chatai.ui.persona.PersonasScreen
import com.zcw.chatai.ui.theme.ChatTheme
import com.zcw.chatai.ui.theme.ThemeRegistry

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPersonas by remember { mutableStateOf(false) }
    BackHandler(enabled = showPersonas) { showPersonas = false }
    if (showPersonas) {
        PersonasScreen(onBack = { showPersonas = false }, modifier = modifier)
        return
    }
    val app = LocalContext.current.applicationContext as ChatAiApp
    val viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(app.settingsRepository, app.chatApi),
    )
    val state by viewModel.state.collectAsState()
    // 角色名直接读 live 设置流：角色管理页绕过 viewModel 表单直接写库，
    // 用快照的话返回后还显示旧名。
    val liveSettings by app.settingsRepository.settings.collectAsState(initial = null)
    val activePersonaName = liveSettings?.activePersona?.name?.takeIf { it.isNotBlank() }
        ?: state.activePersonaName
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    var toolEndpointsExpanded by remember { mutableStateOf(false) }

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
            SectionTitle("服务商")
            ChipFlow(
                options = ProviderCatalog.presets.map { it.id to it.displayName },
                selected = state.activeProviderId,
                onSelect = viewModel::selectProvider,
            )
            ProviderNote(state.activeProviderId)

            SectionTitle("接口")
            Field(
                label = "Base URL（Chat 兼容）",
                value = state.baseUrl,
                onValueChange = { value -> viewModel.update { it.copy(baseUrl = value) } },
                placeholder = ProviderCatalog.byId(state.activeProviderId)?.defaultBaseUrl?.takeIf { it.isNotBlank() }
                    ?: "https://…",
                keyboardType = KeyboardType.Uri,
            )
            Text(
                text = if (toolEndpointsExpanded) "收起工具端点" else "其它协议端点（搜索/图搜，非主对话）",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { toolEndpointsExpanded = !toolEndpointsExpanded }
                    .padding(vertical = 4.dp),
            )
            if (toolEndpointsExpanded) {
                Field(
                    label = "Anthropic Base URL",
                    value = state.displayedAnthropicBase,
                    onValueChange = { value ->
                        viewModel.update {
                            it.copy(
                                anthropicBaseUrl = SettingsViewModel.storedOverride(
                                    value,
                                    SettingsViewModel.derivedAnthropicBase(it.activeProviderId, it.baseUrl),
                                ),
                            )
                        }
                    },
                    placeholder = SettingsViewModel.derivedAnthropicBase(state.activeProviderId, state.baseUrl)
                        .ifBlank { "https://…/v1" },
                    keyboardType = KeyboardType.Uri,
                )
                Field(
                    label = "Responses Base URL",
                    value = state.displayedResponsesBase,
                    onValueChange = { value ->
                        viewModel.update {
                            it.copy(
                                responsesBaseUrl = SettingsViewModel.storedOverride(
                                    value,
                                    SettingsViewModel.derivedResponsesBase(it.baseUrl),
                                ),
                            )
                        }
                    },
                    placeholder = SettingsViewModel.derivedResponsesBase(state.baseUrl)
                        .ifBlank { "https://…/v1" },
                    keyboardType = KeyboardType.Uri,
                )
                Text(
                    text = "留空或改回推导值即跟随 Chat Base URL。DeepSeek 搜索走 Anthropic，" +
                        "通义千问搜索/图搜走 Responses，OpenCode Go 搜索走同一 v1 根的 /messages。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Field(
                label = "API Key",
                value = state.apiKey,
                onValueChange = { value -> viewModel.update { it.copy(apiKey = value) } },
                placeholder = "sk-…",
                keyboardType = KeyboardType.Password,
                masked = true,
            )
            ModelAutocompleteField(
                label = if (state.models.isEmpty()) "模型" else "模型（共 ${state.models.size} 个）",
                value = state.model,
                onValueChange = { value -> viewModel.update { it.copy(model = value) } },
                placeholder = ProviderCatalog.byId(state.activeProviderId)?.defaultModel?.takeIf { it.isNotBlank() }
                    ?: "model-name",
                models = state.models,
                busy = state.busy,
                onFetch = viewModel::testConnection,
            )
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

            SectionTitle("工具")
            ChoiceRow(
                label = "联网搜索后端",
                hint = "这些工具经对应供应商的 API 执行，与当前对话模型无关（借道），按次计费；" +
                    "选中项只是首选，失败（空结果或报错）会自动借道其它已配置的后端",
                options = buildList {
                    add(null to "跟随会话")
                    ProviderCatalog.presets
                        .filter { it.caps.textSearch && state.providers.containsKey(it.id) }
                        .forEach { add(it.id to it.displayName) }
                },
                selected = state.searchProviderId,
                onSelect = { value -> viewModel.update { it.copy(searchProviderId = value) } },
            )
            Field(
                label = "图搜模型（按优先级）",
                value = state.imageSearchModels,
                onValueChange = { value -> viewModel.update { it.copy(imageSearchModels = value) } },
                placeholder = ProviderCatalog.byId(ProviderCatalog.QWEN)?.toolModels
                    ?.joinToString(",")
                    ?: "qwen3.8-27b,qwen3.8-max",
                singleLine = true,
                error = state.imageSearchModelsError,
            )
            Text(
                text = "文搜图 / 图搜图借道通义千问时按顺序尝试：先 27b，空结果或报错再退下一个；" +
                    "留空用内置默认。与「通义千问」条目里的对话模型无关。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            ImageSearchStatus(providers = state.providers)

            SectionTitle("角色")
            Text(
                text = "当前默认：${activePersonaName.ifBlank { "未设置" }}（新会话记住上次在对话里切换的角色）",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            ActionPill(
                label = "管理角色（新增 / 修改 / 删除）",
                onClick = { showPersonas = true },
            )
            Text(
                text = "每份角色自带系统提示词、温度、思考强度、长度上限与附加参数；" +
                    "会话过程中在「模型与供应商」弹层里切换角色，只影响该会话今后的回答。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )

            SectionTitle("生成")
            SwitchRow(
                label = "流式返回用量统计 (stream_options.include_usage)",
                checked = state.includeUsage,
                onCheckedChange = { value -> viewModel.update { it.copy(includeUsage = value) } },
            )
            SwitchRow(
                label = "上下文末尾附带当前时间",
                checked = state.includeEnvTime,
                onCheckedChange = { value -> viewModel.update { it.copy(includeEnvTime = value) } },
            )
            Text(
                text = "打开后，每次请求最后会带一条系统时间（年月日、星期、时分秒），方便模型判断时效；关闭则完全不发送。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
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
                label = "历史图片轮次",
                hint = "保留「当前轮 + 最近 N 轮」的图片（单位是轮次，不是消息条数）；" +
                    "每张图都带来源标注，当前轮的图永远保留",
                options = listOf(
                    1 to "最近 1 轮",
                    2 to "最近 2 轮",
                    3 to "最近 3 轮",
                    -1 to "全部",
                    0 to "只发当前轮",
                ),
                selected = state.historyImageTurns,
                onSelect = { value -> viewModel.update { it.copy(historyImageTurns = value) } },
            )

            SectionTitle("外观")
            ChoiceRow(
                label = "明暗",
                hint = null,
                options = ThemeMode.entries.map { it to it.label() },
                selected = state.themeMode,
                onSelect = viewModel::setThemeMode,
            )
            ChoiceRow(
                label = "配色",
                hint = null,
                options = ThemeRegistry.options().map { (family, theme) -> family to theme.displayName },
                selected = state.themeFamily,
                onSelect = viewModel::setThemeFamily,
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

private fun ImageDetail.label(): String = when (this) {
    ImageDetail.FOLLOW_DEFAULT -> "标准"
    ImageDetail.LOW -> "省流"
    ImageDetail.HIGH -> "高清"
}

@Composable
private fun ProviderNote(providerId: String) {
    val text = when (providerId) {
        ProviderCatalog.QWEN ->
            "通义千问：联网搜索/文搜图/图搜图走 Responses API（搜索 4 元/千次、文搜图 24 元/千次、" +
                "图搜图 48 元/千次）；视频 ≤5MB 内联发送，更大的自动走免费临时上传（48 小时有效）。"
        ProviderCatalog.DEEPSEEK ->
            "DeepSeek：联网搜索走 Anthropic 兼容面（web_search_20260209），暂不支持视频。抓取由本机完成。"
        ProviderCatalog.OPENCODE_GO ->
            "OpenCode Go：主对话走 Chat 兼容面；联网搜索借道同一 v1 根的 Anthropic /messages。" +
                "Grok/GPT/Muse 仅支持 Responses 面，本应用主对话暂不可用；不支持视频。抓取由本机完成。"
        else ->
            "自定义端点：使用标准 OpenAI 兼容接口；连接与密钥只存在本机。"
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
    )
}

@Composable
private fun ImageSearchStatus(providers: Map<String, ProviderEntry>) {
    val qwenConfigured = providers.containsKey(ProviderCatalog.QWEN)
    Text(
        text = if (qwenConfigured) {
            "文搜图 / 图搜图：通义千问（已配置，自动随 🌐 开关可用）"
        } else {
            "文搜图 / 图搜图：未配置通义千问，暂不可用（去「服务商」添加）"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
    )
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
