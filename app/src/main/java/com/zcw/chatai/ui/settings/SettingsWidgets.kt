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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.zcw.chatai.ui.theme.ChatTheme

/**
 * 设置类页面的共用控件（设置页、角色页、备份还原页同一套观感）。
 * 从 `SettingsScreen` 里原样搬出来，只把可见性从 private 放宽到 internal——
 * 调用点与渲染没有任何改动。
 */

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 10.dp),
    )
}

@Composable
internal fun Field(
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
            visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
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
internal fun <T> ChoiceRow(
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
internal fun <T> ChipFlow(
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
internal fun SwitchRow(
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

internal enum class MessageTone { SUCCESS, ERROR }

@Composable
internal fun MessageBar(
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
internal fun ActionPill(
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
