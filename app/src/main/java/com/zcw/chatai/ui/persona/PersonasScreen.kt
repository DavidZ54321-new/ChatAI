package com.zcw.chatai.ui.persona

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zcw.chatai.ChatAiApp
import com.zcw.chatai.data.persona.PersonaConfigCodec
import com.zcw.chatai.data.persona.PersonaEntry
import com.zcw.chatai.data.prefs.ReasoningEffort
import com.zcw.chatai.ui.settings.SettingsViewModel
import com.zcw.chatai.ui.theme.ChatTheme
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * 角色管理页：查看 / 新增 / 修改 / 删除角色配置（名称 + 系统提示词 + 生成参数）。
 * 整表落盘（`SettingsRepository.updatePersonas`）；至少保留一张，删空会被拦截。
 */
@Composable
fun PersonasScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as ChatAiApp
    val settings by app.settingsRepository.settings.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    var draft by remember { mutableStateOf<PersonaDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val personas = settings?.personas.orEmpty()
    val activeId = settings?.resolvedActivePersonaId.orEmpty()

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
                    contentDescription = "返回设置",
                    tint = scheme.onSurface,
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = "角色",
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "点一行设为新会话的默认角色；会话中途切换在「模型与供应商」弹层里，只影响该会话今后的回答。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            personas.entries.forEach { (id, entry) ->
                PersonaRow(
                    entry = entry,
                    isActive = id == activeId,
                    onSelect = {
                        scope.launch {
                            app.settingsRepository.updateActivePersona(id)
                        }
                    },
                    onEdit = { draft = PersonaDraft.from(id, entry) },
                    onDelete = {
                        if (personas.size <= 1) {
                            notice = "至少保留一个角色"
                        } else {
                            pendingDelete = id
                        }
                    },
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surfaceCard)
                    .clickable { draft = PersonaDraft.new() }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "＋ 新增角色",
                    style = MaterialTheme.typography.labelLarge,
                    color = scheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            notice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.error,
                    modifier = Modifier.clickable { notice = null },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    draft?.let { current ->
        PersonaEditDialog(
            draft = current,
            onDismiss = { draft = null },
            onSave = { saved ->
                scope.launch {
                    val table = personas.toMutableMap()
                    val id = saved.id ?: UUID.randomUUID().toString()
                    table[id] = saved.toEntry()
                    // 新增时直接激活：新会话记住上次选择，不用再点一次。
                    val nextActive = if (saved.id == null) id else activeId
                    app.settingsRepository.updatePersonas(table, nextActive)
                    draft = null
                }
            },
        )
    }

    pendingDelete?.let { id ->
        val entry = personas[id]
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除角色「${entry?.name.orEmpty()}」？") },
            text = { Text("绑定过它的会话会自动跟随默认角色，历史消息不受影响。") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val table = personas - id
                        val nextActive = if (id == activeId) {
                            table.keys.firstOrNull().orEmpty()
                        } else {
                            activeId
                        }
                        app.settingsRepository.updatePersonas(table, nextActive)
                        pendingDelete = null
                    }
                }) { Text("删除", color = scheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun PersonaRow(
    entry: PersonaEntry,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isActive) colors.surfaceCard else colors.surfaceSoft)
            .clickable(onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = entry.name.ifBlank { "未命名" },
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isActive) {
                Text(
                    text = "默认",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(scheme.primary)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                )
            }
        }
        val preview = entry.systemPrompt.lineSequence()
            .firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (preview.isNotBlank()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = personaSummary(entry),
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = "编辑",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
                modifier = Modifier.clickable(onClick = onEdit).padding(vertical = 2.dp),
            )
            Text(
                text = "删除",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.error,
                modifier = Modifier.clickable(onClick = onDelete).padding(vertical = 2.dp),
            )
        }
    }
}

private fun personaSummary(entry: PersonaEntry): String = buildList {
    add("思考 ${entry.reasoningEffort.personaLabel()}")
    entry.temperature?.let { add("温度 $it") }
    entry.maxTokens?.let { add("上限 $it") }
    if (entry.extraParams.isNotBlank()) add("附加参数")
}.joinToString(" · ")

private fun ReasoningEffort.personaLabel(): String = when (this) {
    ReasoningEffort.FOLLOW_DEFAULT -> "跟随服务端"
    ReasoningEffort.OFF -> "关"
    ReasoningEffort.LOW -> "低"
    ReasoningEffort.HIGH -> "高"
    ReasoningEffort.MAX -> "最高"
}

/** 编辑器草稿：id 为 null 表示新增。 */
private data class PersonaDraft(
    val id: String?,
    val name: String,
    val systemPrompt: String,
    val temperature: String,
    val reasoningEffort: ReasoningEffort,
    val maxTokens: String,
    val extraParams: String,
) {
    fun toEntry(): PersonaEntry = PersonaEntry(
        name = name.trim(),
        systemPrompt = systemPrompt,
        temperature = temperature.trim().toDoubleOrNull(),
        reasoningEffort = reasoningEffort,
        maxTokens = maxTokens.trim().toIntOrNull(),
        extraParams = extraParams.trim(),
    )

    companion object {
        fun new(): PersonaDraft = PersonaDraft(
            id = null,
            name = "",
            systemPrompt = "",
            temperature = "",
            reasoningEffort = ReasoningEffort.FOLLOW_DEFAULT,
            maxTokens = "",
            extraParams = "",
        )

        fun from(id: String, entry: PersonaEntry): PersonaDraft = PersonaDraft(
            id = id,
            name = entry.name,
            systemPrompt = entry.systemPrompt,
            temperature = entry.temperature?.toString().orEmpty(),
            reasoningEffort = entry.reasoningEffort,
            maxTokens = entry.maxTokens?.toString().orEmpty(),
            extraParams = entry.extraParams,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonaEditDialog(
    draft: PersonaDraft,
    onDismiss: () -> Unit,
    onSave: (PersonaDraft) -> Unit,
) {
    var state by remember(draft) { mutableStateOf(draft) }
    val nameError = PersonaConfigCodec.validateName(state.name)
    val temperatureError = SettingsViewModel.validateTemperature(state.temperature)
    val maxTokensError = SettingsViewModel.validateMaxTokens(state.maxTokens)
    val extraParamsError = SettingsViewModel.validateExtraParams(state.extraParams)
    val canSave = nameError == null && temperatureError == null &&
        maxTokensError == null && extraParamsError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == null) "新增角色" else "编辑角色") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PersonaField(
                    label = "名称",
                    value = state.name,
                    onValueChange = { state = state.copy(name = it) },
                    placeholder = "例如：翻译官",
                    error = nameError,
                )
                PersonaField(
                    label = "系统提示词",
                    value = state.systemPrompt,
                    onValueChange = { state = state.copy(systemPrompt = it) },
                    placeholder = "留空则不加 system 消息",
                    singleLine = false,
                )
                Text(
                    text = "思考强度",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReasoningEffort.entries.forEach { option ->
                        val selected = option == state.reasoningEffort
                        Text(
                            text = option.personaLabel(),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selected) {
                                MaterialTheme.colorScheme.onPrimary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        ChatTheme.colors.chipBackground
                                    },
                                )
                                .clickable { state = state.copy(reasoningEffort = option) }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
                PersonaField(
                    label = "温度 (temperature)",
                    value = state.temperature,
                    onValueChange = { state = state.copy(temperature = it) },
                    placeholder = "留空 = 服务端默认（思考模式下不生效）",
                    keyboardType = KeyboardType.Decimal,
                    error = temperatureError,
                )
                PersonaField(
                    label = "回复长度上限 (max_tokens)",
                    value = state.maxTokens,
                    onValueChange = { state = state.copy(maxTokens = it) },
                    placeholder = "留空 = 服务端默认",
                    keyboardType = KeyboardType.Number,
                    error = maxTokensError,
                )
                PersonaField(
                    label = "附加请求参数 (JSON)",
                    value = state.extraParams,
                    onValueChange = { state = state.copy(extraParams = it) },
                    placeholder = "{\"top_k\": 20}",
                    singleLine = false,
                    error = extraParamsError,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(state) },
                enabled = canSave,
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun PersonaField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    error: String? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
