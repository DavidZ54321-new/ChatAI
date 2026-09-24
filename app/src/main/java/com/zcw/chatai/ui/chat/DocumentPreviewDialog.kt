package com.zcw.chatai.ui.chat

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zcw.chatai.ui.md.MessageMarkdown
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class DocumentPreviewMode { PDF, TEXT, MARKDOWN, DOCX, OFFICE }

internal object DocumentPreview {
    fun usesMarkdown(mode: DocumentPreviewMode): Boolean =
        mode == DocumentPreviewMode.MARKDOWN ||
            mode == DocumentPreviewMode.DOCX ||
            mode == DocumentPreviewMode.OFFICE

    fun mode(fileName: String?, mimeType: String?): DocumentPreviewMode {
        val extension = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when {
            mimeType == "application/pdf" || extension == "pdf" -> DocumentPreviewMode.PDF
            extension in setOf("md", "markdown") || mimeType == "text/markdown" ->
                DocumentPreviewMode.MARKDOWN
            extension in setOf("docx", "docm") ||
                mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                DocumentPreviewMode.DOCX
            extension in setOf("xlsx", "pptx", "xlsm") ||
                mimeType?.contains("openxmlformats-officedocument.spreadsheetml") == true ||
                mimeType?.contains("openxmlformats-officedocument.presentationml") == true ->
                DocumentPreviewMode.OFFICE
            else -> DocumentPreviewMode.TEXT
        }
    }
}

@Composable
fun DocumentPreviewDialog(document: MessageImage, onDismiss: () -> Unit) {
    val mode = remember(document.id, document.displayName, document.mimeType) {
        DocumentPreview.mode(document.displayName, document.mimeType)
    }
    val title = document.displayName ?: "文档"
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "关闭",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            when (mode) {
                DocumentPreviewMode.PDF -> PdfDocumentContent(document.fullPath)
                DocumentPreviewMode.TEXT,
                DocumentPreviewMode.MARKDOWN,
                DocumentPreviewMode.DOCX,
                DocumentPreviewMode.OFFICE,
                -> TextDocumentContent(document, mode)
            }
        }
    }
}

@Composable
private fun TextDocumentContent(document: MessageImage, mode: DocumentPreviewMode) {
    val path = document.extractedPath
    val textState by produceState<DocumentTextState>(DocumentTextState.Loading, document.id, path) {
        value = withContext(Dispatchers.IO) {
            val file = path?.let(::File)
            if (file == null || !file.isFile) {
                DocumentTextState.Failed
            } else {
                runCatching { file.readText(Charsets.UTF_8) }
                    .fold(DocumentTextState::Ready) { DocumentTextState.Failed }
            }
        }
    }
    when (val state = textState) {
        DocumentTextState.Loading -> PreviewLoading()
        DocumentTextState.Failed -> PreviewMessage("无法读取文档内容")
        is DocumentTextState.Ready -> when {
            state.text.isEmpty() -> PreviewMessage("文档内容为空")
            DocumentPreview.usesMarkdown(mode) -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item {
                        MessageMarkdown(
                            content = state.text,
                            cacheable = true,
                            allowRemoteImages = false,
                        )
                    }
                }
            }
            else -> SelectionContainer {
                Text(
                    text = state.text,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                    style = if (mode == DocumentPreviewMode.TEXT) {
                        MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                    } else {
                        MaterialTheme.typography.bodyLarge
                    },
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun PdfDocumentContent(path: String) {
    val owner = remember(path) { pdfSessionOwner() }
    DisposableEffect(owner) {
        onDispose { owner.close() }
    }
    val sessionState by produceState<PdfSessionState>(
        initialValue = PdfSessionState.Loading,
        path,
        owner,
    ) {
        val opened = withContext(Dispatchers.IO) {
            runCatching { PdfDocumentSession.open(path) }
                .map { session -> owner.publish(session) }
        }
        value = opened.fold(
            onSuccess = { session ->
                session?.let(PdfSessionState::Ready) ?: PdfSessionState.Failed("PDF 预览已关闭")
            },
            onFailure = { PdfSessionState.Failed("无法打开 PDF：${it.message ?: "文件可能已损坏"}") },
        )
    }
    when (val state = sessionState) {
        PdfSessionState.Loading -> PreviewLoading()
        is PdfSessionState.Failed -> PreviewMessage(state.message)
        is PdfSessionState.Ready -> if (state.session.pageCount == 0) {
            PreviewMessage("这个 PDF 没有页面")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.session.pageCount, key = { it }) { index ->
                    PdfPage(session = state.session, index = index)
                }
            }
        }
    }
}

@Composable
private fun PdfPage(session: PdfDocumentSession, index: Int) {
    val density = LocalDensity.current
    val viewportWidth = with(density) { LocalWindowInfo.current.containerSize.width }
    val targetWidth = viewportWidth.coerceAtMost(1800).coerceAtLeast(900)
    val owner = remember(session, index) { pdfPageBitmapOwner() }
    val pageState by produceState<PdfPageState>(PdfPageState.Loading, session, index, targetWidth, owner) {
        val rendered = withContext(Dispatchers.IO) {
            runCatching { session.renderPage(index, targetWidth) }
                .map { bitmap -> owner.publish(bitmap) }
        }
        value = rendered.fold(
            onSuccess = { bitmap -> bitmap?.let(PdfPageState::Ready) ?: PdfPageState.Dropped },
            onFailure = { PdfPageState.Failed },
        )
    }
    DisposableEffect(owner) {
        onDispose { owner.close() }
    }
    when (val state = pageState) {
        PdfPageState.Loading -> Box(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(modifier = Modifier.padding(28.dp)) }
        PdfPageState.Dropped -> Unit
        PdfPageState.Failed -> PreviewMessage("第 ${index + 1} 页渲染失败")
        is PdfPageState.Ready -> Image(
            bitmap = state.bitmap.asImageBitmap(),
            contentDescription = "PDF 第 ${index + 1} 页",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.White),
        )
    }
}

@Composable
private fun PreviewLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PreviewMessage(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private sealed interface DocumentTextState {
    data object Loading : DocumentTextState
    data class Ready(val text: String) : DocumentTextState
    data object Failed : DocumentTextState
}

private sealed interface PdfSessionState {
    data object Loading : PdfSessionState
    data class Ready(val session: PdfDocumentSession) : PdfSessionState
    data class Failed(val message: String) : PdfSessionState
}

private sealed interface PdfPageState {
    data object Loading : PdfPageState
    data class Ready(val bitmap: Bitmap) : PdfPageState
    data object Dropped : PdfPageState
    data object Failed : PdfPageState
}

internal class PreviewResourceOwner<T>(private val release: (T) -> Unit) {
    @Volatile
    private var resource: T? = null

    @Volatile
    private var disposed = false

    @Synchronized
    fun publish(value: T): T? {
        if (disposed) {
            release(value)
            return null
        }
        resource?.takeIf { it !== value }?.let(release)
        resource = value
        return value
    }

    @Synchronized
    fun close() {
        disposed = true
        resource?.let(release)
        resource = null
    }
}

private fun pdfSessionOwner() = PreviewResourceOwner<PdfDocumentSession>(PdfDocumentSession::close)
private fun pdfPageBitmapOwner() = PreviewResourceOwner<Bitmap>(Bitmap::recycle)

private class PdfDocumentSession private constructor(
    private val renderer: PdfRenderer,
) {
    val pageCount: Int = renderer.pageCount

    @Synchronized
    fun renderPage(index: Int, maxEdge: Int): Bitmap {
        check(!closed) { "PDF 已关闭" }
        val page = renderer.openPage(index)
        try {
            val scale = minOf(maxEdge.toFloat() / page.width, maxEdge.toFloat() / page.height)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(AndroidColor.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            } catch (t: Throwable) {
                bitmap.recycle()
                throw t
            }
        } finally {
            page.close()
        }
    }

    @Volatile
    private var closed = false

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        renderer.close()
    }

    companion object {
        fun open(path: String): PdfDocumentSession {
            val file = File(path)
            require(file.isFile) { "文件不存在" }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                PdfDocumentSession(PdfRenderer(descriptor))
            } catch (t: Throwable) {
                descriptor.close()
                throw t
            }
        }
    }
}
