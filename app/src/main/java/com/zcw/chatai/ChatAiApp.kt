package com.zcw.chatai

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import com.zcw.chatai.data.ChatRepository
import com.zcw.chatai.data.ConversationLamps
import com.zcw.chatai.data.backup.DataBackup
import com.zcw.chatai.data.db.AppDatabase
import com.zcw.chatai.data.image.ImageRepository
import com.zcw.chatai.data.media.AttachmentStore
import com.zcw.chatai.data.media.VideoUploadCoordinator
import com.zcw.chatai.data.net.ChatApi
import com.zcw.chatai.data.net.DashScopeImageClient
import com.zcw.chatai.data.net.DashScopeUpload
import com.zcw.chatai.data.net.FailoverChatApi
import com.zcw.chatai.data.net.OpenAiCompatibleChatApi
import com.zcw.chatai.data.prefs.SettingsRepository
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.web.deepseek.DeepSeekNativeSearchProvider
import com.zcw.chatai.data.web.HttpWebFetcher
import com.zcw.chatai.data.web.ImageSearchProvider
import com.zcw.chatai.data.web.mimo.MiMoWebSearchProvider
import com.zcw.chatai.data.web.opencode.OpenCodeGoSearchRouter
import com.zcw.chatai.data.web.qwen.QwenImageSearchProvider
import com.zcw.chatai.data.web.qwen.QwenWebSearchProvider
import com.zcw.chatai.data.web.WebFetcher
import com.zcw.chatai.data.web.WebSearchProvider
import com.zcw.chatai.ui.chat.RemoteImages
import com.zcw.chatai.util.MainThreadWatchdog
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 手写 ServiceLocator：全部懒加载单例，无 DI 框架。 */
class ChatAiApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.build(this) }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    /** 主对话：chat 面挂掉的模型（如 Go 的 Luna/Grok/Muse）自动换 Responses 面，无感。 */
    val chatApi: ChatApi by lazy { FailoverChatApi(OpenAiCompatibleChatApi()) }

    val attachmentStore: AttachmentStore by lazy { AttachmentStore(this) }

    /** 按供应商选搜索后端：DeepSeek 走 Anthropic 面，Qwen 走 Responses，Go 按模型路由两面，MiMo 走 Chat 面插件。 */
    val webSearchProviders: Map<String, WebSearchProvider> by lazy {
        val anthropicSearch = DeepSeekNativeSearchProvider()
        mapOf(
            ProviderCatalog.DEEPSEEK to anthropicSearch,
            ProviderCatalog.OPENCODE_GO to OpenCodeGoSearchRouter(messages = anthropicSearch),
            ProviderCatalog.QWEN to QwenWebSearchProvider(),
            ProviderCatalog.MIMO to MiMoWebSearchProvider(),
        )
    }

    /** 图搜后端（文搜图/以图搜图）：目前只有 Qwen 的 Responses 原生工具。 */
    val imageSearchProviders: Map<String, ImageSearchProvider> by lazy {
        mapOf(ProviderCatalog.QWEN to QwenImageSearchProvider())
    }

    val webFetcher: WebFetcher by lazy { HttpWebFetcher() }

    val dashScopeUpload: DashScopeUpload by lazy { DashScopeUpload() }

    val videoUploadCoordinator: VideoUploadCoordinator by lazy {
        VideoUploadCoordinator(attachmentStore, dashScopeUpload)
    }

    /** DashScope 千问图像生成/编辑客户端（同步接口，文生图与改图共用）。 */
    val dashScopeImageClient: DashScopeImageClient by lazy { DashScopeImageClient() }

    /** 前台保活：对话与生图**共用同一实例**（代数计数共享，避免互相把服务拆掉）。 */
    private val turnForeground: ChatTurnForeground by lazy { ChatTurnForeground(this) }

    /** 会话列表状态灯：对话与生图共用。会话 id 全局唯一，屏幕上同时只看着一条。 */
    val conversationLamps: ConversationLamps by lazy { ConversationLamps() }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(
            db = database,
            settingsRepository = settingsRepository,
            api = chatApi,
            attachmentStore = attachmentStore,
            searchProviders = webSearchProviders,
            imageProviders = imageSearchProviders,
            videoUploadCoordinator = videoUploadCoordinator,
            webFetcher = webFetcher,
            turnForeground = turnForeground,
            lamps = conversationLamps,
        )
    }

    /** 生图会话的唯一业务入口（与对话平级，共用会话/消息表与附件存储）。 */
    val imageRepository: ImageRepository by lazy {
        ImageRepository(
            db = database,
            settingsRepository = settingsRepository,
            imageClient = dashScopeImageClient,
            attachmentStore = attachmentStore,
            chatApi = chatApi,
            turnForeground = turnForeground,
            lamps = conversationLamps,
        )
    }

    /** 数据备份/还原（zip）。跑在自己的 scope 上，导出/导入过程中退到后台也不中断。 */
    val dataBackup: DataBackup by lazy {
        DataBackup(
            context = this,
            db = database,
            settingsRepository = settingsRepository,
            attachmentStore = attachmentStore,
            // 有回合在跑时拒绝导入：正在写的消息行会被覆盖。
            busy = {
                chatRepository.busyConversations.value.isNotEmpty() ||
                    imageRepository.busyConversations.value.isNotEmpty()
            },
            // 附件路径可能被复用（同 id 换成另一张图），还原后必须丢掉旧位图缓存。
            onImported = { RemoteImages.clear() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        RemoteImages.install(this)
        // PdfBox-Android 的字体/AFM 资源走 APK assets，必须先给它 AssetManager，
        // 否则 new PDFTextStripper() 直接炸（表现为解析失败，堆栈只剩类名）。
        // 初始化失败必须留日志：之后每个 PDF 都会失败，静默吞掉就没法查了。
        runCatching { PDFBoxResourceLoader.init(this) }
            .onFailure { android.util.Log.w("ChatAiApp", "PDFBox 初始化失败，PDF 解析不可用", it) }
        registerComponentCallbacks(
            object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    RemoteImages.onTrimMemory(level)
                }

                override fun onConfigurationChanged(newConfig: Configuration) = Unit

                override fun onLowMemory() {
                    RemoteImages.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
                }
            },
        )
        ChatTurnService.ensureChannel(this)
        MainThreadWatchdog.startIfDebuggable(
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
        appScope.launch {
            runCatching { chatRepository.sweepOrphanAttachments() }
            // 上次导入中途被杀会留下解压目录；它不在 attachments/ 下，上面那次清理扫不到。
            runCatching { dataBackup.clearStaleStaging() }
        }
    }
}
