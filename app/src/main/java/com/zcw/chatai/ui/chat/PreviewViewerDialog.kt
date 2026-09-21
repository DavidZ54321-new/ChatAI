package com.zcw.chatai.ui.chat

import android.annotation.SuppressLint
import android.content.Intent
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import com.zcw.chatai.data.media.ExportChunkAssembler
import com.zcw.chatai.data.media.GalleryStore
import com.zcw.chatai.data.media.GifAssembler
import com.zcw.chatai.data.media.GifResult
import com.zcw.chatai.data.media.SvgAnimation
import com.zcw.chatai.ui.md.PreviewLanguage
import com.zcw.chatai.ui.md.PreviewTarget
import com.zcw.chatai.ui.md.supportsPngExport
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 预览全屏 viewer（独立 Dialog，随开随建、退出即销毁，列表里永远没有 WebView）。
 *
 * - mermaid：asset 壳离线渲染，页内渐进显示（大图转多久都不超时，用户随时可关），支持缩放；
 * - PlantUML：同一套 asset 壳离线渲染（@plantuml/core 的 TeaVM 引擎），同样支持缩放与深色；
 * - 动效 SVG：在这里播（SMIL/CSS/JS 通吃），支持缩放；
 * - HTML：在这里跑，站外跳转转外部浏览器；
 * - WebView 锁定：JS 开，只有 mermaid / PlantUML 壳页挂了收完成信号的 bridge。
 *   mermaid 走 `file://` asset，PlantUML 因 ES module 同源要求改走 WebViewAssetLoader；
 *   两者都不给外人用 `file://`（PlantUML 侧更是显式拒绝一切站外子资源）。
 *
 * 导出 PNG：在页内把渲染出的 SVG 栅格化（不截 WebView、不截视窗），base64 分片回传后存相册。
 * GIF（动效 SVG）后续再接。
 */
@Composable
fun PreviewViewerDialog(
    target: PreviewTarget,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val isMermaid = target.language == PreviewLanguage.MERMAID
    val isPlantUml = target.language == PreviewLanguage.PLANTUML
    var pageReady by remember { mutableStateOf(false) }
    var renderRequested by remember { mutableStateOf(false) }
    // 渲染有结果了（成功/失败/进程被杀）：轮询据此收手，别再盖写成人话之外的结论。
    var renderSettled by remember { mutableStateOf(false) }
    var notice by remember(target) {
        mutableStateOf(if (isMermaid || isPlantUml) "渲染中…" else null)
    }
    val title = when (target.language) {
        PreviewLanguage.MERMAID -> "mermaid 预览"
        PreviewLanguage.SVG -> "SVG 预览"
        PreviewLanguage.HTML -> "HTML 预览"
        PreviewLanguage.PLANTUML -> "PlantUML 预览"
    }
    var exporting by remember(target) { mutableStateOf(false) }
    // 顶栏按钮要直接触发页内导出，所以留一个 WebView 引用（factory 里赋值）。
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    // 只有 SMIL/CSS 动效的 SVG 才给「保存 GIF」（脚本动效在 <img> 里不播，做不出来）。
    val isAnimatedSvg = target.language == PreviewLanguage.SVG &&
        SvgAnimation.kind(target.code) == SvgAnimation.Kind.SMIL_OR_CSS

    // bridge 只建一次；用 rememberUpdatedState 读「当前」的状态与 scope，避免捕获旧的 setter。
    val onExportPng = rememberUpdatedState<(ByteArray?, String) -> Unit>(
        { bytes, error ->
            if (bytes == null) {
                exporting = false
                notice = "保存失败：$error"
            } else {
                scope.launch {
                    val name = GalleryStore.exportFileName(System.currentTimeMillis(), "png")
                    val saved = withContext(Dispatchers.IO) {
                        GalleryStore.saveBytes(context, bytes, "image/png", name)
                    }
                    exporting = false
                    notice = if (saved) "已保存到相册" else "保存失败"
                }
            }
        },
    )
    val onExportGif = rememberUpdatedState<(List<String>, String) -> Unit>(
        { frames, error ->
            when {
                error.isNotEmpty() -> {
                    exporting = false
                    notice = "保存失败：$error"
                }

                frames.isEmpty() -> {
                    exporting = false
                    notice = "保存失败：没有可用帧"
                }

                else -> scope.launch {
                    notice = "正在编码 GIF…"
                    // 编码是 CPU（逐帧 NeuQuant 量化），落盘是阻塞 IO：分开调度。
                    val result = withContext(Dispatchers.Default) {
                        GifAssembler.assemble(frames, 1000 / ExportJs.GIF_FPS)
                    }
                    val (saved, message) = withContext(Dispatchers.IO) {
                        val now = System.currentTimeMillis()
                        when (result) {
                            is GifResult.Animated -> GalleryStore.saveBytes(
                                context,
                                result.bytes,
                                "image/gif",
                                GalleryStore.exportFileName(now, "gif"),
                            ) to "已保存动图到相册"

                            is GifResult.Static -> GalleryStore.saveBytes(
                                context,
                                result.pngBytes,
                                "image/png",
                                GalleryStore.exportFileName(now, "png"),
                            ) to "该动效无法导出 GIF，已保存首帧 PNG"

                            GifResult.Failed -> false to "保存失败"
                        }
                    }
                    exporting = false
                    notice = if (saved) message else "保存失败"
                }
            }
        },
    )
    val onExportProgress = rememberUpdatedState<(Int, Int) -> Unit> { index, total ->
        notice = "正在导出 GIF… $index/$total"
    }
    val exportSink = remember {
        ExportBridge(
            onPng = { bytes, error -> onExportPng.value(bytes, error) },
            onGif = { frames, error -> onExportGif.value(frames, error) },
            onProgress = { index, total -> onExportProgress.value(index, total) },
        )
    }
    val canExport = target.language.supportsPngExport && pageReady && !exporting

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                // 保存 PNG：页内栅格化（mermaid / plantuml / 静态 SVG）。HTML 是任意网页，不导出。
                if (target.language.supportsPngExport) {
                    TextButton(
                        enabled = canExport,
                        onClick = {
                            exporting = true
                            notice = "正在导出…"
                            // 先武装桥：只有用户发起的这次导出会被接受。
                            exportSink.arm()
                            webViewRef.value?.evaluateJavascript(
                                "window.exportPng(${ExportJs.MAX_EDGE})",
                                null,
                            )
                        },
                    ) { Text("保存 PNG") }
                }
                // 动效 SVG：逐帧烘焙后编成 GIF（CSS 动效烘焙不出差异时会降级存首帧 PNG）。
                if (isAnimatedSvg) {
                    TextButton(
                        enabled = canExport,
                        onClick = {
                            exporting = true
                            notice = "正在导出 GIF…"
                            exportSink.arm()
                            webViewRef.value?.evaluateJavascript(
                                "window.exportGif(${ExportJs.gifArgs()})",
                                null,
                            )
                        },
                    ) { Text("保存 GIF") }
                }
                // HTML 的「外部打开」是交给浏览器，不是本机转换，保留。
                if (target.language == PreviewLanguage.HTML) {
                    TextButton(
                        onClick = { notice = openExternally(context, target.code) },
                    ) { Text("外部打开") }
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            notice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef.value = this
                        // 导出回调桥：所有可导出页面共用（HTML 不触发导出，挂着也无害）。
                        addJavascriptInterface(exportSink, "ExportSink")
                        when {
                            isMermaid -> {
                                configureAssetLocked()
                                addJavascriptInterface(
                                    ViewerBridge(
                                        onDone = { ok, msg ->
                                            // 错误快路；成功以轮询为准（bridge 成功回执在真机上丢过）。
                                            if (!ok) {
                                                Log.d(TAG, "viewer onDone ok=false msg=${msg.take(120)}")
                                                pageReady = false
                                                renderSettled = true
                                                notice = "渲染失败：$msg"
                                            }
                                        },
                                    ),
                                    "MermaidViewer",
                                )
                                webViewClient = viewerClient(
                                    onPageFinished = {
                                        if (!renderRequested) {
                                            renderRequested = true
                                            // 先注入导出工具（__chataiSvgToPng / __chataiEmitPng）。
                                            evaluateJavascript(ExportJs.HELPERS, null)
                                            evaluateJavascript(
                                                "window.renderIntoPage(" +
                                                    JSONObject.quote(target.code) + "," +
                                                    (if (dark) "true" else "false") + ")",
                                                null,
                                            )
                                            // 成功判定不依赖 bridge：轮询 SVG 是否落页，
                                            // 60s 不出图才报超时（大图只管等）。
                                            scope.launch {
                                                val deadline =
                                                    android.os.SystemClock.uptimeMillis() + 60_000
                                                while (
                                                    !renderSettled &&
                                                    android.os.SystemClock.uptimeMillis() < deadline
                                                ) {
                                                    kotlinx.coroutines.delay(500)
                                                    if (renderSettled) return@launch
                                                    if (this@apply.hasSvg()) {
                                                        pageReady = true
                                                        renderSettled = true
                                                        notice = null
                                                        return@launch
                                                    }
                                                }
                                                // 只有真的没人给过结论才算超时（别把失败改写成超时）。
                                                if (!renderSettled) {
                                                    renderSettled = true
                                                    notice = "渲染超时，可关闭重试"
                                                }
                                            }
                                        }
                                    },
                                    // 渲染进程被杀（低内存 + 3.5MB JS 是高危组合）就是白屏：
                                    // 吃掉崩溃、打日志、给人话提示。
                                    onRenderGone = {
                                        Log.e(TAG, "webview render process gone")
                                        pageReady = false
                                        renderSettled = true
                                        notice = "渲染进程被系统回收，请关闭重试"
                                    },
                                )
                                webChromeClient = viewerChromeClient()
                                loadUrl("file:///android_asset/mermaid/render.html")
                            }

                            isPlantUml -> {
                                configurePlantUmlLocked()
                                val assetLoader = WebViewAssetLoader.Builder()
                                    .addPathHandler(
                                        "/assets/",
                                        WebViewAssetLoader.AssetsPathHandler(ctx),
                                    )
                                    .build()
                                addJavascriptInterface(
                                    ViewerBridge(
                                        onDone = { ok, msg ->
                                            if (!ok) {
                                                Log.d(TAG, "plantuml onDone ok=false msg=${msg.take(120)}")
                                                renderSettled = true
                                                notice = "渲染失败：$msg"
                                            }
                                        },
                                    ),
                                    "PlantUmlViewer",
                                )
                                webViewClient = assetLockedClient(
                                    assetLoader = assetLoader,
                                    onPageFinished = {
                                        if (!renderRequested) {
                                            renderRequested = true
                                            evaluateJavascript(ExportJs.HELPERS, null)
                                            renderIntoPage(target.code, dark)
                                            // 引擎异步写 SVG：轮询落页。首次要 boot 6.8MB 引擎
                                            // （可能还有内部 worker），超时给得比 mermaid 宽。
                                            scope.launch {
                                                val ok = awaitSvgOrSettle(90_000) { renderSettled }
                                                if (ok) {
                                                    pageReady = true
                                                    renderSettled = true
                                                    notice = null
                                                } else if (!renderSettled) {
                                                    renderSettled = true
                                                    notice = "渲染超时，可关闭重试"
                                                }
                                            }
                                        }
                                    },
                                    onRenderGone = {
                                        Log.e(TAG, "plantuml render process gone")
                                        renderSettled = true
                                        notice = "渲染进程被系统回收，请关闭重试"
                                    },
                                )
                                webChromeClient = viewerChromeClient()
                                loadUrl(PLANTUML_PAGE_URL)
                            }

                            else -> {
                                configureLocked()
                                webViewClient = viewerClient(
                                    onPageFinished = {
                                        evaluateJavascript(ExportJs.HELPERS, null)
                                        if (target.language == PreviewLanguage.SVG) {
                                            evaluateJavascript(ExportJs.SVG_PAGE_EXPORT, null)
                                        }
                                        pageReady = true
                                    },
                                    // SVG/HTML 页没有 mermaid 那套渲染进程可等，直接算"有结果"。
                                    onRenderGone = {
                                        renderSettled = true
                                        notice = "渲染进程被系统回收，请关闭重试"
                                    },
                                )
                                loadDataWithBaseURL(
                                    "https://localhost/",
                                    wrapHtml(target),
                                    "text/html",
                                    "UTF-8",
                                    null,
                                )
                            }
                        }
                    }
                },
                onRelease = { view ->
                    runCatching {
                        view.stopLoading()
                        view.removeJavascriptInterface("MermaidViewer")
                        view.removeJavascriptInterface("PlantUmlViewer")
                        view.removeJavascriptInterface("ExportSink")
                        view.destroy()
                    }
                    webViewRef.value = null
                },
            )
        }
    }
}

/** viewer 页内渲染的完成信号（fire-and-forget，结果已由页面自己展示）。 */
private class ViewerBridge(private val onDone: (Boolean, String) -> Unit) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    // bridge 回调在 WebView 的 binder 线程，不在主线程：state 读写必须抛回主线程，
    // 否则直接抛 "Java exception was raised during method invocation"（真机实测）。
    @JavascriptInterface
    fun onDone(ok: Boolean, message: String) {
        main.post { onDone(ok, message) }
    }
}

/**
 * 导出桥：页内把结果回传（bridge 单次传输有上限，PNG 必须分片）。
 *
 * - PNG 模式：`append` 累积分片，`finish` 解码成字节；
 * - GIF 模式：JS 先 `beginGif`，再 `frame` 逐帧回传整帧 PNG base64，`finish` 交出帧列表。
 *
 * 所有回调都抛回主线程：顺序由主线程队列保证，也与 state 写入同线程。
 */
private class ExportBridge(
    private val onPng: (ByteArray?, String) -> Unit,
    private val onGif: (List<String>, String) -> Unit,
    private val onProgress: (Int, Int) -> Unit,
) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val chunks = ExportChunkAssembler()
    private val frames = ArrayList<String>()
    private var gifMode = false
    private var armed = false

    /**
     * 由 Kotlin 在用户点按钮时调用（**不是** `@JavascriptInterface`，页面脚本够不着）：
     * 只有「已武装」的导出才会落盘。否则预览页里的任意脚本都能自己往相册写。
     */
    fun arm() {
        main.post {
            armed = true
            gifMode = false
            frames.clear()
            chunks.reset()
        }
    }

    @JavascriptInterface
    fun beginGif() {
        main.post { if (armed) gifMode = true }
    }

    @JavascriptInterface
    fun append(chunk: String) {
        main.post { if (armed) chunks.append(chunk) }
    }

    @JavascriptInterface
    fun frame(base64: String) {
        main.post { if (armed) frames.add(base64) }
    }

    @JavascriptInterface
    fun progress(index: Int, total: Int) {
        main.post { if (armed) onProgress(index, total) }
    }

    @JavascriptInterface
    fun finish(ok: Boolean, message: String) {
        main.post {
            // 未武装 = 不是用户发起的导出（或重复的 finish）：忽略。
            if (!armed) return@post
            armed = false
            if (gifMode) {
                val collected = ArrayList(frames)
                frames.clear()
                gifMode = false
                onGif(
                    if (ok) collected else emptyList(),
                    if (ok) "" else message.ifBlank { "导出失败" },
                )
                return@post
            }
            val bytes = if (ok) chunks.decode() else null
            val error = when {
                !ok -> message.ifBlank { "导出失败" }
                chunks.overflowed -> "导出数据过大"
                bytes == null || bytes.isEmpty() -> "导出数据无效"
                else -> null
            }
            chunks.reset()
            if (error == null) onPng(bytes, "") else onPng(null, error)
        }
    }
}


@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configureLocked() {
    settings.javaScriptEnabled = true
    // 初始缩放钉死 100%：WebView 默认按内容自适应缩放，观感与页面不一致。
    setInitialScale(100)
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = true
    settings.setSupportMultipleWindows(false)
    // 大图/长页可缩放查看（mermaid 知识地图、动效 SVG 都用得上）。
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
}

/** mermaid asset 壳专用：只读本包 asset，不访问网络。 */
@SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
private fun WebView.configureAssetLocked() {
    settings.javaScriptEnabled = true
    setInitialScale(100)
    settings.allowFileAccess = true
    settings.allowContentAccess = false
    settings.allowFileAccessFromFileURLs = true
    settings.allowUniversalAccessFromFileURLs = false
    settings.blockNetworkLoads = true
    settings.domStorageEnabled = true
    settings.setSupportMultipleWindows(false)
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
}

/** PlantUML asset 壳专用：内容全部来自 WebViewAssetLoader（appassets 域，https 同源，
 *  ES module 才被允许加载）；file://、content:// 一律关掉。 */
@SuppressLint("SetJavaScriptEnabled")
private fun WebView.configurePlantUmlLocked() {
    settings.javaScriptEnabled = true
    setInitialScale(100)
    // minSdk 29：allowFileAccessFromFileURLs / allowUniversalAccessFromFileURLs 默认即 false，
    // 且在 allowFileAccess=false 下无意义，不再显式设置（它们是 deprecated 字段）。
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.domStorageEnabled = true
    settings.setSupportMultipleWindows(false)
    settings.setSupportZoom(true)
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
}

private const val TAG = "PreviewViewer"

/** PlantUML 壳页地址：WebViewAssetLoader 把 assets/plantuml/ 映射到这个 https 同源地址。 */
private const val PLANTUML_PAGE_URL =
    "https://appassets.androidplatform.net/assets/plantuml/render.html"

private fun viewerClient(
    onPageFinished: () -> Unit,
    onRenderGone: () -> Unit = {},
): WebViewClient =
    object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (url == "https://localhost/") return false
            // 站外跳转一律转外部浏览器，预览页本身不导航。
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, request.url)
                view.context.startActivity(intent)
            }
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            onPageFinished()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onRenderGone()
            return true
        }
    }

/**
 * WebViewAssetLoader 版 client：只服务 appassets 域里的本地 asset（PlantUML 壳页及其
 * plantuml.js / viz-global.js），其余子资源一律空响应——预览页离线，不给网络任何口子。
 */
private fun assetLockedClient(
    assetLoader: WebViewAssetLoader,
    onPageFinished: () -> Unit,
    onRenderGone: () -> Unit,
): WebViewClient =
    object : WebViewClient() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val url = request.url
            if (url.host == WebViewAssetLoader.DEFAULT_DOMAIN) {
                // 本地 asset 命中即返回；未命中也不回落到网络。
                return assetLoader.shouldInterceptRequest(url) ?: emptyResponse()
            }
            return emptyResponse()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            if (url.host == WebViewAssetLoader.DEFAULT_DOMAIN) return false
            // 站外跳转一律转外部浏览器，预览页本身不导航。
            runCatching {
                val intent = Intent(Intent.ACTION_VIEW, url)
                view.context.startActivity(intent)
            }
            return true
        }

        override fun onPageFinished(view: WebView, url: String) {
            onPageFinished()
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            onRenderGone()
            return true
        }
    }

private fun emptyResponse(): WebResourceResponse =
    WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

/** JS console 直通 logcat：白屏时先看这里。 */
private fun viewerChromeClient(): WebChromeClient =
    object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            Log.d(TAG, "console [${message.messageLevel()}] ${message.message()} @${message.lineNumber()}")
            return true
        }
    }

/** 页里有没有渲染出的 SVG（mermaid 成功轮询，主线程调用）。 */
private suspend fun WebView.hasSvg(): Boolean = withContext(Dispatchers.Main) {
    val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
    evaluateJavascript("String(!!document.querySelector('svg'))") { value ->
        done.complete(value == "\"true\"" || value == "true")
    }
    done.await()
}

/** 调壳页的 window.renderIntoPage(code, dark) 起渲染（引擎异步写 SVG，随后轮询）。 */
private fun WebView.renderIntoPage(code: String, dark: Boolean) {
    evaluateJavascript(
        "window.renderIntoPage(" + JSONObject.quote(code) + "," +
            (if (dark) "true" else "false") + ")",
        null,
    )
}

/** 轮询 SVG 是否落页；[isSettled] 为 true（失败/进程被杀）时立即收手。返回 SVG 是否出现。 */
private suspend fun WebView.awaitSvgOrSettle(timeoutMs: Long, isSettled: () -> Boolean): Boolean {
    val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
    while (!isSettled() && android.os.SystemClock.uptimeMillis() < deadline) {
        kotlinx.coroutines.delay(500)
        if (isSettled()) return false
        if (hasSvg()) return true
    }
    return false
}

/** viewer 装载模板：SVG 居中白底（动效原样播放），HTML 原样装载。 */
private fun wrapHtml(target: PreviewTarget): String =
    if (target.language == PreviewLanguage.SVG) {
        "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>html,body{margin:0;padding:0;background:#fff;}" +
            "body{display:flex;align-items:center;justify-content:center;min-height:100vh;}" +
            "svg{max-width:100%;height:auto;}</style></head><body>" +
            target.code + "</body></html>"
    } else {
        target.code
    }

/** HTML 外部打开：落 cache + FileProvider 分享出去；返回提示文案。 */
private fun openExternally(context: android.content.Context, code: String): String = try {
    val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    val file = File(dir, "preview_${System.currentTimeMillis()}.html")
    file.writeText(code)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "text/html")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "用浏览器打开"))
    "已调起外部浏览器"
} catch (_: Throwable) {
    "打开失败"
}
