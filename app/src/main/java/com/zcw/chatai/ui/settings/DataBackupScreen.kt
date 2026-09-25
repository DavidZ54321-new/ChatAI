package com.zcw.chatai.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.zcw.chatai.ChatAiApp
import com.zcw.chatai.data.backup.BackupFormat
import com.zcw.chatai.data.backup.BackupPhase
import com.zcw.chatai.data.backup.BackupPaths
import com.zcw.chatai.data.backup.BackupSummary
import com.zcw.chatai.data.backup.ExportOptions
import com.zcw.chatai.data.backup.ImportMode
import com.zcw.chatai.data.backup.formatBytes
import com.zcw.chatai.ui.chat.DarkSheet
import com.zcw.chatai.ui.chat.SheetAction
import com.zcw.chatai.ui.theme.ChatTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * 数据备份与还原：导出成一个 zip，也能从这个 zip 还原（换手机时免去手动重配）。
 * 导出/导入都跑在 `DataBackup` 自己的 scope 上，所以在页面里退出不会中断；
 * 进度与结果从这个页面订阅，切走再回来还能看到。
 */
@Composable
fun DataBackupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as ChatAiApp
    val progress by app.dataBackup.progress.collectAsState()
    val scope = rememberCoroutineScope()
    val colors = ChatTheme.colors
    val scheme = MaterialTheme.colorScheme
    var includeAttachments by remember { mutableStateOf(true) }
    var includeApiKeys by remember { mutableStateOf(true) }
    var inspecting by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingImport by remember { mutableStateOf<Pair<Uri, BackupSummary>?>(null) }

    // 进页面先清掉上一次的结果条（正在跑的不清）。
    LaunchedEffect(Unit) { app.dataBackup.dismissResult() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri != null) {
            app.dataBackup.export(uri, ExportOptions(includeAttachments, includeApiKeys))
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        inspecting = true
        notice = null
        scope.launch {
            app.dataBackup.inspect(uri)
                .onSuccess { pendingImport = uri to it }
                .onFailure { notice = it.message ?: "无法读取这个备份" }
            inspecting = false
        }
    }

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
                text = "备份与还原",
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
            SectionTitle("导出")
            Text(
                text = "把聊天记录、消息附件、服务商与密钥、角色、生成参数、外观一起打包成一个 zip。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            SwitchRow(
                label = "包含附件（图片 / 视频 / 音频 / 文档）",
                checked = includeAttachments,
                onCheckedChange = { includeAttachments = it },
            )
            SwitchRow(
                label = "包含 API Key",
                checked = includeApiKeys,
                onCheckedChange = { includeApiKeys = it },
            )
            Text(
                text = "zip 是明文：关掉 API Key 之后备份可以放心发给别人，但导入方要自己重新填写密钥；" +
                    "关掉附件体积会小很多，代价是历史消息里的图片/视频变成占位文本。",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
            ActionPill(
                label = if (progress.running) "正在处理…" else "导出备份",
                onClick = {
                    app.dataBackup.dismissResult()
                    exportLauncher.launch(BackupPaths.suggestedFileName(System.currentTimeMillis()))
                },
                enabled = !progress.running && !inspecting,
                primary = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionTitle("还原")
            Text(
                text = "选择一个之前导出的 zip。导入前会先让你确认：覆盖本机数据，或把备份里的会话追加进来。",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
            ActionPill(
                label = if (inspecting) "正在读取备份…" else "从备份还原",
                onClick = {
                    notice = null
                    // 有些文件管理器报 octet-stream，MIME 过滤放宽一档。
                    importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                enabled = !progress.running && !inspecting,
                modifier = Modifier.fillMaxWidth(),
            )

            if (progress.running) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = scheme.primary,
                    )
                    Text(
                        text = progress.label.ifBlank { "正在处理…" },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            progress.error?.let { error ->
                MessageBar(
                    text = error,
                    tone = MessageTone.ERROR,
                    onClick = { app.dataBackup.dismissResult() },
                )
            }
            if (progress.phase == BackupPhase.DONE && progress.label.isNotBlank()) {
                MessageBar(
                    text = progress.label,
                    tone = MessageTone.SUCCESS,
                    onClick = { app.dataBackup.dismissResult() },
                )
            }
            notice?.let { text ->
                MessageBar(
                    text = text,
                    tone = MessageTone.ERROR,
                    onClick = { notice = null },
                )
            }

            Text(
                text = "说明：备份不含系统相册里的导出物与图片缓存；从旧版本 app 读新备份时，" +
                    "未识别的字段会被忽略而不是整包失败。",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    val pending = pendingImport
    if (pending != null) {
        val (uri, summary) = pending
        DarkSheet(onDismiss = { pendingImport = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = summaryTitle(summary),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.codeOnBackground,
                )
                Text(
                    text = summaryDetail(summary),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.codeHeaderText,
                )
            }
            SheetAction(
                label = "覆盖：清空本机聊天与配置后还原",
                color = scheme.error,
                onClick = {
                    pendingImport = null
                    app.dataBackup.import(uri, ImportMode.REPLACE)
                },
            )
            SheetAction(
                label = "合并：保留本机数据，把备份里的会话追加进来",
                onClick = {
                    pendingImport = null
                    app.dataBackup.import(uri, ImportMode.MERGE)
                },
            )
        }
    }
}

private fun summaryTitle(summary: BackupSummary): String {
    val stamp = Instant.ofEpochMilli(summary.exportedAt)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    return "备份于 $stamp"
}

private fun summaryDetail(summary: BackupSummary): String = buildList {
    add("${summary.conversationCount} 个会话")
    add("${summary.messageCount} 条消息")
    if (summary.includesAttachments) {
        add("${summary.attachmentCount} 个附件（${formatBytes(summary.attachmentBytes)}）")
    } else {
        add("不含附件")
    }
    if (!summary.includesApiKeys) add("不含 API Key")
    // 高版本备份允许导入，只是未识别的字段会被忽略——先说清楚，免得用户以为丢数据。
    if (summary.formatVersion > BackupFormat.VERSION) add("来自更新的版本")
}.joinToString(" · ")
