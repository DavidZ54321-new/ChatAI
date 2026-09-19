package com.zcw.chatai

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import com.zcw.chatai.data.ChatRepository
import com.zcw.chatai.data.db.AppDatabase
import com.zcw.chatai.data.media.AttachmentStore
import com.zcw.chatai.data.media.VideoUploadCoordinator
import com.zcw.chatai.data.net.ChatApi
import com.zcw.chatai.data.net.DashScopeUpload
import com.zcw.chatai.data.net.FailoverChatApi
import com.zcw.chatai.data.net.OpenAiCompatibleChatApi
import com.zcw.chatai.data.prefs.SettingsRepository
import com.zcw.chatai.data.provider.ProviderCatalog
import com.zcw.chatai.data.web.DeepSeekNativeSearchProvider
import com.zcw.chatai.data.web.HttpWebFetcher
import com.zcw.chatai.data.web.ImageSearchProvider
import com.zcw.chatai.data.web.OpenCodeGoSearchRouter
import com.zcw.chatai.data.web.QwenImageSearchProvider
import com.zcw.chatai.data.web.QwenWebSearchProvider
import com.zcw.chatai.data.web.WebFetcher
import com.zcw.chatai.data.web.WebSearchProvider
import com.zcw.chatai.ui.chat.RemoteImages
import com.zcw.chatai.util.MainThreadWatchdog
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

    /** 按供应商选搜索后端：DeepSeek 走 Anthropic 面，Qwen 走 Responses，Go 按模型路由两面。 */
    val webSearchProviders: Map<String, WebSearchProvider> by lazy {
        val anthropicSearch = DeepSeekNativeSearchProvider()
        mapOf(
            ProviderCatalog.DEEPSEEK to anthropicSearch,
            ProviderCatalog.OPENCODE_GO to OpenCodeGoSearchRouter(messages = anthropicSearch),
            ProviderCatalog.QWEN to QwenWebSearchProvider(),
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
            turnForeground = ChatTurnForeground(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        RemoteImages.install(this)
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
        }
    }
}
