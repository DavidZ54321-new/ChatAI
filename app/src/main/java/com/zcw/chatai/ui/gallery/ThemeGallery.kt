package com.zcw.chatai.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zcw.chatai.data.prefs.ThemeMode
import com.zcw.chatai.ui.theme.ChatTheme
import com.zcw.chatai.ui.theme.SpikeMark

@Composable
fun ThemeGallery(themeMode: ThemeMode, onThemeModeChange: (ThemeMode) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        GalleryHeader()
        ThemeModeRow(themeMode, onThemeModeChange)
        Section("色板 / palette") { Palette() }
        Section("字阶 / type scale") { TypeScale() }
        Section("按钮 / buttons") { Buttons() }
        Section("标签 / badges") { Badges() }
        Section("卡片 / cards") { Cards() }
        Section("消息 / messages") { Messages() }
        Section("输入 / input") { InputSample() }
        Footer()
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun GalleryHeader() {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SpikeMark(size = 26.dp, color = scheme.onSurface)
            Text("ChatAI", style = MaterialTheme.typography.displaySmall, color = scheme.onSurface)
        }
        Text(
            text = "Claude / Anthropic 设计系统落地预览",
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThemeModeRow(themeMode: ThemeMode, onChange: (ThemeMode) -> Unit) {
    val colors = ChatTheme.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryTab("跟随系统", themeMode == ThemeMode.SYSTEM) { onChange(ThemeMode.SYSTEM) }
        CategoryTab("浅色", themeMode == ThemeMode.LIGHT) { onChange(ThemeMode.LIGHT) }
        CategoryTab("深色", themeMode == ThemeMode.DARK) { onChange(ThemeMode.DARK) }
    }
}

@Composable
private fun CategoryTab(label: String, active: Boolean, onClick: () -> Unit) {
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) colors.surfaceCard else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) scheme.onSurface else scheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Palette() {
    val scheme = MaterialTheme.colorScheme
    val colors = ChatTheme.colors
    val entries = listOf(
        "canvas 画布" to colors.canvas,
        "surface-soft" to colors.surfaceSoft,
        "surface-card" to colors.surfaceCard,
        "cream-strong" to colors.surfaceCreamStrong,
        "coral 主色" to scheme.primary,
        "ink 正文" to scheme.onSurface,
        "muted 次要" to scheme.onSurfaceVariant,
        "hairline 描边" to colors.hairline,
        "code 代码底" to colors.codeBackground,
        "teal 强调" to colors.accentTeal,
        "amber 强调" to colors.accentAmber,
        "error 错误" to scheme.error,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        entries.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, color) ->
                    ColorChip(label, color, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ColorChip(label: String, color: Color, modifier: Modifier = Modifier) {
    val colors = ChatTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color)
                .border(1.dp, colors.hairline, RoundedCornerShape(8.dp)),
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun TypeScale() {
    val scheme = MaterialTheme.colorScheme
    val styles = listOf(
        "display-sm 28" to MaterialTheme.typography.displaySmall,
        "headline-md 22" to MaterialTheme.typography.headlineMedium,
        "title-lg 22/500" to MaterialTheme.typography.titleLarge,
        "title-md 18/500" to MaterialTheme.typography.titleMedium,
        "body-md 16" to MaterialTheme.typography.bodyLarge,
        "body-sm 14" to MaterialTheme.typography.bodyMedium,
        "caption 13/500" to MaterialTheme.typography.labelMedium,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        styles.forEach { (label, style) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                Text("今天想问点什么？ Aa Bb 0123", style = style, color = scheme.onSurface)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "code 13 mono",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            Text(
                "fun parse(latex: String): MathAtom",
                style = ChatTheme.typography.code,
                color = scheme.onSurface,
            )
        }
    }
}

@Composable
private fun Buttons() {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(scheme.primary)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text("主要操作", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimary)
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(scheme.background)
                .border(1.dp, ChatTheme.colors.hairline, RoundedCornerShape(8.dp))
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text("次要", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(ChatTheme.colors.surfaceCreamStrong)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text("禁用", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        }
        IconCircle { Icon(Icons.Filled.Menu, contentDescription = null, tint = scheme.onSurface) }
        IconCircle { Icon(Icons.Filled.Add, contentDescription = null, tint = scheme.onSurface) }
        IconCircle { Icon(Icons.Filled.Settings, contentDescription = null, tint = scheme.onSurface) }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(scheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = scheme.onPrimary)
        }
    }
}

@Composable
private fun IconCircle(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.background)
            .border(1.dp, ChatTheme.colors.hairline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

@Composable
private fun Badges() {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(ChatTheme.colors.surfaceCard)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("标签", style = MaterialTheme.typography.labelMedium, color = scheme.onSurface)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(scheme.primary)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("NEW", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimary)
            }
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CategoryTab("对话", true) {}
            CategoryTab("设置", false) {}
            CategoryTab("模型", false) {}
            CategoryTab("历史记录", false) {}
        }
    }
}

@Composable
private fun Cards() {
    val scheme = MaterialTheme.colorScheme
    val colors = ChatTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceCard)
                .padding(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Feature 卡片", style = MaterialTheme.typography.titleMedium, color = scheme.onSurface)
                Text(
                    "奶油底卡片，靠颜色分层而不是阴影。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.codeBackground)
                .padding(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("KOTLIN", style = MaterialTheme.typography.labelSmall, color = colors.codeHeaderText)
                    Text("复制", style = MaterialTheme.typography.labelSmall, color = colors.codeHeaderText)
                }
                Text(
                    "val mark = SpikeMark(size = 20.dp)",
                    style = ChatTheme.typography.code,
                    color = colors.codeOnBackground,
                )
            }
        }
    }
}

@Composable
private fun Messages() {
    val scheme = MaterialTheme.colorScheme
    val colors = ChatTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 4.dp))
                    .background(colors.bubbleUser)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    "用户气泡：珊瑚实心、白字、居右。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.bubbleUserText,
                )
            }
        }
        Column(Modifier.fillMaxWidth(0.9f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI 回复无框无底色，占视窗 90% 宽", style = MaterialTheme.typography.headlineMedium, color = scheme.onSurface)
            Text(
                "正文用 Inter，标题用系统衬线；行内代码样式如下：",
                style = MaterialTheme.typography.bodyLarge,
                color = scheme.onSurface,
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surfaceCreamStrong)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text("rememberMarkdownState", style = ChatTheme.typography.code, color = scheme.onSurface)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(40.dp)
                        .background(scheme.primary),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "引用块：左侧珊瑚竖线 + muted 文字。",
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun InputSample() {
    val scheme = MaterialTheme.colorScheme
    val colors = ChatTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceCard)
            .border(1.dp, scheme.primary, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("给 ChatAI 发消息…", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(scheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Footer() {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = ChatTheme.colors.hairline)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpikeMark(size = 14.dp, color = scheme.onSurfaceVariant)
            Text(
                "ChatAI · 设计系统预览页（M1）",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}
