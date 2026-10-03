package com.zcw.chatai.ui.image

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zcw.chatai.ChatAiApp
import com.zcw.chatai.data.image.imageModelIdFromSuggestion
import com.zcw.chatai.data.image.openai.OpenAiImageOptions
import com.zcw.chatai.data.image.qwen.QwenImageOptions
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.ui.chat.DarkSheet
import com.zcw.chatai.ui.common.ModelAutocompleteField
import com.zcw.chatai.ui.theme.ChatTheme

/**
 * 图像模型：和文本模型同一套「输入框 + 下拉候选」。
 * 供应商用顶部的芯片切换；OpenAI 再往下是质量、尺寸、背景、格式和张数。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageModelSheet(
    state: ImageUiState,
    onDismiss: () -> Unit,
    onSelectModel: (providerId: String, model: String) -> Unit,
    onRefreshModels: () -> Unit,
    onOpenAiImage: (OpenAiImageOptions) -> Unit,
    onQwenImage: (QwenImageOptions) -> Unit,
    onPromptExtend: (Boolean) -> Unit,
) {
    DarkSheet(onDismiss = onDismiss, scroll = true, fullHeight = true, imeAware = true) {
        val app = LocalContext.current.applicationContext as ChatAiApp
        val settings by app.settingsRepository.settings.collectAsState(initial = null)
        var picked by remember(state.providerId) { mutableStateOf(state.providerId) }
        var query by remember { mutableStateOf(state.model) }
        var providerHint by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(picked, state.model) { query = state.model }

        val colors = ChatTheme.colors
        val scheme = MaterialTheme.colorScheme
        val section = state.modelSections.firstOrNull { it.providerId == picked }
        val suggestions = section?.models?.map { it.suggestion() }.orEmpty()
        val manual = imageModelIdFromSuggestion(query)

        Text(
            text = "图像模型",
            style = MaterialTheme.typography.titleMedium,
            color = colors.codeOnBackground,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        Text(
            text = "当前：${state.providerLabel} · ${state.model.ifBlank { "未设置" }}",
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
            state.modelSections.forEach { item ->
                val configured = settings?.providers?.get(item.providerId)?.apiKey?.isNotBlank() == true
                val active = item.providerId == picked
                Text(
                    text = if (configured) item.title else "${item.title} · 未配置",
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
                                    providerHint = "「${item.title}」还没配置 API Key，请先去设置里填写"
                                else -> {
                                    providerHint = null
                                    picked = item.providerId
                                    if (state.model.isNotBlank()) onSelectModel(item.providerId, state.model)
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        providerHint?.let { hint ->
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = colors.accentAmber,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
        }
        key(picked) {
            ModelAutocompleteField(
                label = if (suggestions.isEmpty()) "模型" else "模型（共 ${suggestions.size} 个）",
                value = query,
                onValueChange = { query = it },
                models = suggestions,
                onFetch = onRefreshModels,
                onPick = { suggestion ->
                    val id = imageModelIdFromSuggestion(suggestion)
                    if (id.isNotBlank()) onSelectModel(picked, id)
                },
                imeAction = ImeAction.Done,
                onImeAction = { if (manual.isNotBlank()) onSelectModel(picked, manual) },
                dark = true,
                allowManual = true,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        if (picked == ProviderCatalog.OPENAI) {
            OpenAiImageOptionsBlock(
                options = state.openAiImage,
                onChange = onOpenAiImage,
            )
        }
        if (picked == ProviderCatalog.QWEN) {
            QwenImageOptionsBlock(
                options = state.qwenImage,
                promptExtend = state.imagePromptExtend,
                onChange = onQwenImage,
                onPromptExtend = onPromptExtend,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OpenAiImageOptionsBlock(
    options: OpenAiImageOptions,
    onChange: (OpenAiImageOptions) -> Unit,
) {
    val colors = ChatTheme.colors
    var customSize by remember { mutableStateOf(customSizeOf(options.size)) }
    LaunchedEffect(options.size) { customSize = customSizeOf(options.size) }
    val sizeError = OpenAiImageOptions.sizeProblem(customSize)
    val shownSize = customSize.trim().ifBlank { options.size }

    Text(
        text = "OpenAI 输出",
        style = MaterialTheme.typography.titleSmall,
        color = colors.codeOnBackground,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    OptionChips(
        label = "质量",
        choices = listOf("" to "默认") + OpenAiImageOptions.QUALITIES.map { it to it },
        selected = options.quality,
        onSelect = { onChange(options.copy(quality = it)) },
        hint = "默认不传，服务端按 auto。xhigh / max 只有 GPT Image 2.5 认。部分中转只接受 low。",
    )
    OptionChips(
        label = "尺寸",
        choices = listOf("" to "默认") + OpenAiImageOptions.PRESET_SIZES.map { it to it },
        selected = if (customSize.isNotBlank()) customSize.trim().lowercase() else options.size,
        onSelect = {
            customSize = ""
            onChange(options.copy(size = it))
        },
        hint = "默认是文生图 1024×1024、带参考图 auto。两边都要是 16 的倍数，单边不超过 3840。",
    )
    OutlinedTextField(
        value = customSize,
        onValueChange = { text ->
            customSize = text
            val problem = OpenAiImageOptions.sizeProblem(text)
            when {
                text.isBlank() -> onChange(options.copy(size = ""))
                problem == null -> onChange(options.copy(size = text.trim()))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        singleLine = true,
        label = { Text("自定义尺寸") },
        placeholder = { Text("例如 1536x864") },
        isError = sizeError != null && customSize.isNotBlank(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        shape = RoundedCornerShape(12.dp),
        colors = darkFieldColors(),
    )
    val sizeNote = when {
        sizeError != null && customSize.isNotBlank() -> sizeError
        OpenAiImageOptions.experimentalSize(shownSize) -> "高于 2560×1440 是实验分辨率"
        else -> null
    }
    sizeNote?.let { note ->
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = colors.accentAmber,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
        )
    }
    OptionChips(
        label = "背景",
        choices = listOf("" to "默认", "auto" to "auto", "opaque" to "不透明", "transparent" to "透明"),
        selected = options.background,
        onSelect = { onChange(options.copy(background = it)) },
        hint = "透明背景只能用 png 或 webp。选了 jpeg 会改回 png。",
    )
    OptionChips(
        label = "格式",
        choices = listOf("" to "默认") + OpenAiImageOptions.FORMATS.map { it to it },
        selected = options.outputFormat,
        onSelect = { format ->
            val next = if (options.background == "transparent" && format == "jpeg") "png" else format
            onChange(options.copy(outputFormat = next))
        },
        hint = "默认 png。jpeg 通常比 png 快。",
    )
    if (options.outputFormat == "jpeg" || options.outputFormat == "webp") {
        OptionChips(
            label = "压缩",
            choices = listOf("" to "默认", "100" to "100", "80" to "80", "50" to "50"),
            selected = options.outputCompression?.toString().orEmpty(),
            onSelect = { value ->
                onChange(options.copy(outputCompression = value.toIntOrNull()))
            },
            hint = "只对 jpeg / webp 有效，默认 100。",
        )
    }
    OptionChips(
        label = "张数",
        choices = (1..OpenAiImageOptions.MAX_COUNT).map { it.toString() to it.toString() },
        selected = options.count.toString(),
        onSelect = { onChange(options.copy(count = it.toIntOrNull() ?: 1)) },
        hint = "一次出几张。接口最多 10 张，这里做到 4 张。",
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QwenImageOptionsBlock(
    options: QwenImageOptions,
    promptExtend: Boolean,
    onChange: (QwenImageOptions) -> Unit,
    onPromptExtend: (Boolean) -> Unit,
) {
    val colors = ChatTheme.colors
    var customSize by remember { mutableStateOf(qwenCustomSize(options.size)) }
    var seedText by remember { mutableStateOf(options.seed?.toString().orEmpty()) }
    LaunchedEffect(options.size) { customSize = qwenCustomSize(options.size) }
    LaunchedEffect(options.seed) { seedText = options.seed?.toString().orEmpty() }
    val sizeError = QwenImageOptions.sizeProblem(customSize)
    val seedError = seedProblem(seedText)

    Text(
        text = "千问输出",
        style = MaterialTheme.typography.titleSmall,
        color = colors.codeOnBackground,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    OptionChips(
        label = "智能改写",
        choices = listOf("true" to "开", "false" to "关"),
        selected = promptExtend.toString(),
        onSelect = { onPromptExtend(it == "true") },
        hint = "对应 prompt_extend，和设置页是同一个开关。简单提示词开着更稳。",
    )
    if (promptExtend) {
        OptionChips(
            label = "改写方式",
            choices = listOf(
                "" to "默认",
                QwenImageOptions.MODE_DIRECT to "direct",
                QwenImageOptions.MODE_AGENT to "agent",
            ),
            selected = options.promptExtendMode,
            onSelect = { onChange(options.copy(promptExtendMode = it)) },
            hint = "默认 direct。agent 只对文生图生效，带参考图时不会下发。",
        )
        OptionChips(
            label = "思考",
            choices = listOf("" to "默认", "true" to "开", "false" to "关"),
            selected = when (options.enableThinking) {
                true -> "true"
                false -> "false"
                null -> ""
            },
            onSelect = { value ->
                onChange(
                    options.copy(
                        enableThinking = when (value) {
                            "true" -> true
                            "false" -> false
                            else -> null
                        },
                    ),
                )
            },
            hint = "默认不传。打开后出图更慢，只在智能改写开启时生效。",
        )
    }
    OptionChips(
        label = "尺寸",
        choices = listOf("" to "默认") + QwenImageOptions.PRESET_SIZES.map { it to it },
        selected = if (customSize.isNotBlank()) customSize.trim() else options.size,
        onSelect = {
            customSize = ""
            onChange(options.copy(size = it))
        },
        hint = "默认文生图 1024*1024，带参考图时不传、跟原图。总像素在 512² 到 2048² 之间，长短边不超过 8:1。qwen-image-max / plus 只认 1664*928 那一组固定尺寸。",
    )
    OutlinedTextField(
        value = customSize,
        onValueChange = { text ->
            customSize = text
            val problem = QwenImageOptions.sizeProblem(text)
            when {
                text.isBlank() -> onChange(options.copy(size = ""))
                problem == null -> onChange(options.copy(size = QwenImageOptions.normalizeSize(text)))
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        singleLine = true,
        label = { Text("自定义尺寸") },
        placeholder = { Text("例如 1536*1024") },
        isError = sizeError != null && customSize.isNotBlank(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        shape = RoundedCornerShape(12.dp),
        colors = darkFieldColors(),
    )
    if (sizeError != null && customSize.isNotBlank()) {
        Text(
            text = sizeError,
            style = MaterialTheme.typography.bodySmall,
            color = colors.accentAmber,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
        )
    }
    OptionChips(
        label = "张数",
        choices = (1..QwenImageOptions.MAX_COUNT).map { it.toString() to it.toString() },
        selected = options.count.toString(),
        onSelect = { onChange(options.copy(count = it.toIntOrNull() ?: 1)) },
        hint = "3.0 / 2.0 系列可以 1–6 张。qwen-image-edit 和 max / plus 只出 1 张。",
    )
    OptionChips(
        label = "水印",
        choices = listOf("false" to "关", "true" to "开"),
        selected = options.watermark.toString(),
        onSelect = { onChange(options.copy(watermark = it == "true")) },
        hint = "打开后右下角会有 Qwen-Image 水印。",
    )
    OutlinedTextField(
        value = options.negativePrompt,
        onValueChange = { onChange(options.copy(negativePrompt = it)) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        label = { Text("反向提示词") },
        placeholder = { Text("不希望出现的内容，最多 500 字") },
        minLines = 2,
        maxLines = 4,
        shape = RoundedCornerShape(12.dp),
        colors = darkFieldColors(),
    )
    OutlinedTextField(
        value = seedText,
        onValueChange = { text ->
            seedText = text.filter { it.isDigit() }
            val digits = text.filter { it.isDigit() }
            when {
                digits.isEmpty() -> onChange(options.copy(seed = null))
                else -> digits.toLongOrNull()
                    ?.takeIf { it in 0L..QwenImageOptions.MAX_SEED.toLong() }
                    ?.let { onChange(options.copy(seed = it.toInt())) }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
        singleLine = true,
        label = { Text("种子") },
        placeholder = { Text("留空则随机，0–2147483647") },
        isError = seedError != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        shape = RoundedCornerShape(12.dp),
        colors = darkFieldColors(),
    )
    seedError?.let { note ->
        Text(
            text = note,
            style = MaterialTheme.typography.bodySmall,
            color = colors.accentAmber,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionChips(
    label: String,
    choices: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    hint: String? = null,
) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    Text(
        text = label,
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
        choices.forEach { (value, title) ->
            val active = value == selected
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = if (active) scheme.onPrimary else colors.codeOnBackground,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) scheme.primary else colors.codeButtonBackground)
                    .clickable { if (!active) onSelect(value) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
    hint?.let { text ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = colors.codeHeaderText,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedLabelColor = Color.White.copy(alpha = 0.75f),
    unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
    focusedPlaceholderColor = Color.White.copy(alpha = 0.4f),
    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.4f),
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
    cursorColor = MaterialTheme.colorScheme.primary,
    errorBorderColor = ChatTheme.colors.accentAmber,
    errorLabelColor = ChatTheme.colors.accentAmber,
    errorCursorColor = ChatTheme.colors.accentAmber,
)

private fun customSizeOf(size: String): String =
    size.takeIf { it.isNotBlank() && it !in OpenAiImageOptions.PRESET_SIZES }.orEmpty()

private fun qwenCustomSize(size: String): String =
    size.takeIf { it.isNotBlank() && it !in QwenImageOptions.PRESET_SIZES }.orEmpty()

private fun seedProblem(raw: String): String? {
    val digits = raw.filter { it.isDigit() }
    if (raw.isBlank() || digits.isEmpty()) return null
    val value = digits.toLongOrNull() ?: return "种子要在 0 到 2147483647 之间"
    if (value > QwenImageOptions.MAX_SEED) return "种子要在 0 到 2147483647 之间"
    return null
}
