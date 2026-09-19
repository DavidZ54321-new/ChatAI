package com.zcw.chatai.ui.common

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.zcw.chatai.data.provider.ModelFilter
import com.zcw.chatai.ui.theme.ChatTheme
import kotlinx.coroutines.delay

/** 候选浮层一次最多显示几行，下方/上方空间不够时再压得更矮。 */
private const val MODEL_SUGGESTION_ROWS = 5

/** 单行候选高度（与 DropdownMenuItem 的最小行高一致），用来估算可用行数。 */
private val MODEL_ROW_HEIGHT = 48.dp

/**
 * 模型自动完成输入框（设置页与会话模型弹层共用，行为一致）：
 * - 原地可输入（手输不受候选限制），输入即筛选；右侧下拉图标展开候选；
 * - 候选浮层与输入框同宽、最多 5 行懒加载；**下方空间不够（例如输入法弹起）就翻到输入框上方**，
 *   始终不会被输入法挡住；
 * - 焦点落到输入框时光标移到末尾（不是停在最前面）；点下拉图标只展开候选、不唤起输入法；
 * - 点候选默认回填字段（[onPick] 可改成直接生效，比如会话里选中即切换）。
 *
 * [allowManual] 为 true 时，输入了一个不在候选里的名字会在浮层顶部给一条「使用「…」」，
 * 让手输模型也有可见入口（会话弹层用；设置页字段本身就是模型值，不需要）。
 *
 * [dark] 为 true 时用深色浮层配色：弹层背景是深的，不能跟随（可能是浅色的）应用主题。
 */
@Composable
fun ModelAutocompleteField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    models: List<String>,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    busy: Boolean = false,
    onFetch: (() -> Unit)? = null,
    onPick: (String) -> Unit = onValueChange,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    dark: Boolean = false,
    allowManual: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    // 只有用户真的改过输入框才按字段文本筛选；单纯展开下拉看全部候选。
    var typing by remember { mutableStateOf(false) }
    // 刚聚焦时把光标掰回末尾，避免它停在字符串最前面。
    var placeCaretAtEnd by remember { mutableStateOf(false) }
    // 浮层「点外面收起」会先于按钮点击生效：用它区分「这次点 ▼ 是想收起」和「想展开」。
    var justDismissed by remember { mutableStateOf(false) }
    var anchorTop by remember { mutableStateOf(0f) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val scheme = MaterialTheme.colorScheme
    val keyword = if (typing) value else ""
    val matches = remember(models, keyword) { ModelFilter.filter(models, keyword) }
    val manual = value.trim()

    // 自己持有 TextFieldValue：这样能控制光标位置，也让外部改值（切供应商）后光标落到末尾。
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var reported by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != reported) {
            field = TextFieldValue(value, TextRange(value.length))
            reported = value
        }
    }

    // 下拉的可用高度：扣掉输入法占的高度；下方塞不下就翻到输入框上方。
    val windowHeightPx = LocalWindowInfo.current.containerSize.height
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val gapPx = with(density) { 8.dp.toPx() }
    val minRowsPx = with(density) { MODEL_ROW_HEIGHT.toPx() }
    val maxRowsPx = with(density) { MODEL_ROW_HEIGHT.toPx() * MODEL_SUGGESTION_ROWS }
    val spaceBelow = windowHeightPx - imeBottomPx - (anchorTop + anchorSize.height) - gapPx
    val spaceAbove = anchorTop - gapPx
    // 下方放不下整套候选（常见于输入法弹起）且上方更宽松时，翻到输入框上方。
    val openUp = spaceBelow < maxRowsPx && spaceAbove > spaceBelow
    val dropdownMaxHeight = with(density) {
        (if (openUp) spaceAbove else spaceBelow)
            .coerceAtLeast(minRowsPx)
            .coerceAtMost(maxRowsPx)
            .toDp()
    }

    val fieldColors = if (dark) {
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedLabelColor = Color.White.copy(alpha = 0.75f),
            unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
            focusedPlaceholderColor = Color.White.copy(alpha = 0.4f),
            unfocusedPlaceholderColor = Color.White.copy(alpha = 0.4f),
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
            cursorColor = scheme.primary,
        )
    } else {
        OutlinedTextFieldDefaults.colors()
    }

    // 「点外面收起」刚发生后短时间内别再让按钮把浮层打开，否则点 ▼ 会一开一关地打架。
    LaunchedEffect(justDismissed) {
        if (justDismissed) {
            delay(200)
            justDismissed = false
        }
    }

    // 浮层不可获焦 → 系统返回不会自动收起它；先吃掉返回，免得直接退出所在页面/弹层。
    BackHandler(enabled = expanded) { expanded = false }

    Column(modifier = modifier.heightIn(min = 56.dp)) {
        Box(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = field,
                onValueChange = { updated ->
                    // 点一下输入框也会触发 onValueChange（只是光标位置变了，文本没变），
                    // 不能把它当成「用户在筛选」，否则候选会被当前模型名筛到只剩一条。
                    val normalized =
                        if (updated.text == reported && placeCaretAtEnd && updated.text.isNotEmpty()) {
                            updated.copy(selection = TextRange(updated.text.length))
                        } else {
                            updated
                        }
                    placeCaretAtEnd = false
                    field = normalized
                    if (normalized.text != reported) {
                        reported = normalized.text
                        typing = true
                        onValueChange(normalized.text)
                    }
                    expanded = true
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        anchorTop = coords.positionInWindow().y
                        anchorSize = coords.size
                    }
                    // 浮层自己不可获焦（否则会抢走输入焦点、收起键盘）；改由字段失焦收起。
                    .onFocusChanged { focus ->
                        if (focus.isFocused) {
                            expanded = true
                            // 刚拿到焦点时把光标放到末尾（不是最前面）；之后用户再点就按落点走。
                            placeCaretAtEnd = true
                            if (field.text.isNotEmpty()) {
                                field = field.copy(selection = TextRange(field.text.length))
                            }
                        } else {
                            expanded = false
                            placeCaretAtEnd = false
                        }
                    },
                singleLine = true,
                label = { Text(label) },
                placeholder = placeholder?.let { hint -> { Text(hint) } },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = imeAction,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        onImeAction?.invoke()
                        expanded = false
                    },
                ),
                colors = fieldColors,
                shape = RoundedCornerShape(12.dp),
                // 只占位：真正的下拉按钮是下面的兄弟覆盖层，不参与输入框焦点。
                trailingIcon = { Spacer(Modifier.size(40.dp)) },
            )
            // 覆盖层下拉按钮：点它不会让输入框失焦/聚焦，既不唤起输入法，
            // 也不会和 onFocusChanged 抢着改 expanded（之前点了收不起来的根因）。
            IconButton(
                onClick = {
                    // 浮层打开时点 ▼ 的「收起」由上面 onDismissRequest 完成；这里只负责展开。
                    if (!justDismissed) {
                        typing = false
                        expanded = true
                    }
                    justDismissed = false
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .focusProperties { canFocus = false },
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = "展开候选",
                    tint = if (dark) Color.White.copy(alpha = 0.75f) else scheme.onSurfaceVariant,
                )
            }
            if (expanded && anchorSize != IntSize.Zero) {
                Popup(
                    alignment = if (openUp) Alignment.BottomStart else Alignment.TopStart,
                    offset = if (openUp) IntOffset(0, -anchorSize.height) else IntOffset(0, anchorSize.height),
                    onDismissRequest = {
                        expanded = false
                        justDismissed = true
                    },
                    properties = PopupProperties(focusable = false),
                ) {
                    Surface(
                        modifier = Modifier
                            .width(with(density) { anchorSize.width.toDp() })
                            .heightIn(max = dropdownMaxHeight),
                        shape = RoundedCornerShape(12.dp),
                        // 深色弹层（会话弹层）里也用深色底，别跟随可能是浅色的应用主题。
                        color = if (dark) ChatTheme.colors.codeButtonBackground else scheme.surface,
                        shadowElevation = 8.dp,
                        tonalElevation = 3.dp,
                    ) {
                        LazyColumn {
                            // 手输的模型名不在候选里时，给一个可见的「使用」入口。
                            if (allowManual && typing && manual.isNotEmpty() && matches.none { it == manual }) {
                                item(key = "manual-apply") {
                                    ModelSuggestion(
                                        text = "使用「$manual」",
                                        onClick = {
                                            typing = false
                                            expanded = false
                                            field = TextFieldValue(manual, TextRange(manual.length))
                                            reported = manual
                                            onPick(manual)
                                        },
                                    )
                                }
                            }
                            when {
                                models.isEmpty() -> item {
                                    ModelSuggestion(
                                        text = if (busy) "正在拉取模型列表…" else "没有可用的模型",
                                        enabled = onFetch != null && !busy,
                                        onClick = {
                                            expanded = false
                                            onFetch?.invoke()
                                        },
                                    )
                                }

                                matches.isEmpty() -> item {
                                    ModelSuggestion(text = "没有匹配的模型", enabled = false, onClick = {})
                                }

                                else -> items(matches, key = { it }) { model ->
                                    ModelSuggestion(
                                        text = if (model == value) "$model（当前）" else model,
                                        onClick = {
                                            typing = false
                                            expanded = false
                                            field = TextFieldValue(model, TextRange(model.length))
                                            reported = model
                                            onPick(model)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSuggestion(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        enabled = enabled,
        onClick = onClick,
    )
}
