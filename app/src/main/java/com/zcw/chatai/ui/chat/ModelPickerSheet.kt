package com.zcw.chatai.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.zcw.chatai.ChatAiApp
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.ui.common.ModelAutocompleteField
import com.zcw.chatai.ui.theme.ChatTheme
import kotlinx.coroutines.flow.first

/**
 * 模型、供应商与角色选择：模型字段和设置页是同一个「单输入框 + 下拉候选」组件
 * （[ModelAutocompleteField]），输入即筛选、点候选即生效，想手输就敲完按回车。
 * - 顶部切换**本会话**的供应商（未配置 API Key 的不可选，给可读提示）；
 * - 中段切换**本会话**的角色（提示词 + 生成参数，只影响今后的回答）；
 * - 选择写到**当前会话**上（conversations.provider_id / model / persona_id），不影响其它会话；
 *   角色切换会同步默认角色，新会话记住上次选择。
 */
@Composable
fun ModelPickerSheet(
    currentModel: String,
    providerId: String,
    personaId: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectPersona: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    DarkSheet(onDismiss = onDismiss) {
        ModelPickerContent(
            currentModel = currentModel,
            providerId = providerId,
            personaId = personaId,
            onSelect = onSelect,
            onSelectProvider = onSelectProvider,
            onSelectPersona = onSelectPersona,
            onOpenSettings = onOpenSettings,
        )
    }
}

/**
 * 上面那个弹层的内容，拆出来给编辑消息弹层内联复用（两个 ModalBottomSheet 叠窗不可靠）。
 * 直接往宿主 Column 里发节点，所以调用方的布局上下文决定排列方式。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelPickerContent(
    currentModel: String,
    providerId: String,
    /** null = 不涉及角色：编辑消息弹层只改会话级绑定里的模型/供应商，角色段整段隐藏。 */
    personaId: String?,
    onSelect: (String) -> Unit,
    onSelectProvider: (String) -> Unit,
    onSelectPersona: (String) -> Unit,
    /** null = 不给「去设置」入口（编辑弹层里跳设置会丢掉草稿）。 */
    onOpenSettings: (() -> Unit)?,
) {
    val app = LocalContext.current.applicationContext as ChatAiApp
    val settings by app.settingsRepository.settings.collectAsState(initial = null)
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var providerHint by remember { mutableStateOf<String?>(null) }
    // 候选为空时的「重新拉取」用：自增触发下面的 LaunchedEffect。
    var reload by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf(currentModel) }

    // providerId 变化（含用户在顶部切换供应商）时重新拉该供应商的模型列表。
    LaunchedEffect(providerId, reload) {
        models = emptyList()
        error = null
        loading = true
        runCatching { app.chatApi.listModels(app.settingsRepository.chatConfig(providerId).first()) }
            .onSuccess { models = it }
            .onFailure { error = it.message }
        loading = false
    }
    // 供应商切换（或外部改了当前模型）后，输入框回到新的当前模型。
    LaunchedEffect(providerId, currentModel) { query = currentModel }

    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    val providerName = ProviderCatalog.displayName(providerId)
    val personas = settings?.personas.orEmpty()
    val personaName = personaId?.let { personas[it]?.name?.takeIf { name -> name.isNotBlank() } } ?: "默认"
    val manual = query.trim()

    // 候选列表是独立的 Popup 浮层，弹层本身按内容自适应即可——不能固定高度，
    // 否则键盘弹起把弹层压矮时，输入框会被挤到只剩一条缝。
    Text(
        text = "模型与供应商",
        style = MaterialTheme.typography.titleMedium,
        color = colors.codeOnBackground,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    Text(
        text = if (personaId == null) {
            "当前：$providerName · ${currentModel.ifBlank { "未设置" }}"
        } else {
            "当前：$providerName · ${currentModel.ifBlank { "未设置" }} · $personaName"
        },
        style = MaterialTheme.typography.labelMedium,
        color = colors.codeHeaderText,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 10.dp),
    ) {
        ProviderCatalog.presets.forEach { preset ->
            val configured = settings?.providers?.get(preset.id)?.apiKey?.isNotBlank() == true
            val active = preset.id == providerId
            Text(
                text = if (configured) preset.displayName else "${preset.displayName} · 未配置",
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    active -> scheme.onPrimary
                    configured -> colors.codeOnBackground
                    else -> colors.codeHeaderText
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        when {
                            active -> scheme.primary
                            configured -> colors.codeButtonBackground
                            else -> colors.codeButtonBackground.copy(alpha = 0.4f)
                        },
                    )
                    .clickable {
                        when {
                            active -> Unit
                            !configured ->
                                providerHint =
                                    "「${preset.displayName}」还没配置 API Key，请先去设置里填写"
                            else -> {
                                providerHint = null
                                // 先清掉旧供应商的候选：切换在途时列表还没刷新，
                                // 这时点到旧列表里的模型会被写到新供应商上。
                                models = emptyList()
                                error = null
                                onSelectProvider(preset.id)
                            }
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
    providerHint?.let { hint ->
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 2.dp),
        ) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = colors.accentAmber,
                modifier = Modifier.weight(1f),
            )
            if (onOpenSettings != null) {
                Text(
                    text = "去设置",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.codeOnBackground,
                    modifier = Modifier.clickable(onClick = onOpenSettings),
                )
            }
        }
    }
    if (personaId != null) {
        Text(
            text = "角色",
            style = MaterialTheme.typography.labelLarge,
            color = colors.codeHeaderText,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            personas.entries.forEach { (id, entry) ->
                val active = id == personaId
                Text(
                    text = entry.name.ifBlank { "未命名" },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) scheme.onPrimary else colors.codeOnBackground,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (active) scheme.primary else colors.codeButtonBackground)
                        .clickable { if (!active) onSelectPersona(id) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
    // 换供应商后重建，免得复用上一个供应商时的「是否在输入」筛选状态。
    key(providerId) {
        ModelAutocompleteField(
            label = if (models.isEmpty()) "模型" else "模型（共 ${models.size} 个）",
            value = query,
            onValueChange = { query = it },
            models = models,
            busy = loading,
            onFetch = { reload++ },
            onPick = { onSelect(it) },
            imeAction = ImeAction.Done,
            onImeAction = { if (manual.isNotBlank()) onSelect(manual) },
            dark = true,
            allowManual = true,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
    error?.let {
        Text(
            text = "拉取失败：$it",
            style = MaterialTheme.typography.bodySmall,
            color = colors.accentAmber,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
    if (onOpenSettings != null) {
        Text(
            text = "服务与密钥设置",
            style = MaterialTheme.typography.labelLarge,
            color = colors.codeHeaderText,
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .clickable(onClick = onOpenSettings),
        )
    }
}
