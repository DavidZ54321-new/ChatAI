package com.zcw.chatai

import android.app.Application
import android.content.pm.ApplicationInfo
import com.zcw.chatai.data.ChatRepository
import com.zcw.chatai.data.db.AppDatabase
import com.zcw.chatai.data.media.AttachmentStore
import com.zcw.chatai.data.net.ChatApi
import com.zcw.chatai.data.net.OpenAiCompatibleChatApi
import com.zcw.chatai.data.prefs.SettingsRepository
import com.zcw.chatai.data.web.DeepSeekNativeSearchProvider
import com.zcw.chatai.data.web.HttpWebFetcher
import com.zcw.chatai.data.web.WebFetcher
import com.zcw.chatai.data.web.WebSearchProvider
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

    val chatApi: ChatApi by lazy { OpenAiCompatibleChatApi() }

    val attachmentStore: AttachmentStore by lazy { AttachmentStore(this) }

    val webSearchProvider: WebSearchProvider by lazy { DeepSeekNativeSearchProvider() }

    val webFetcher: WebFetcher by lazy { HttpWebFetcher() }

    val chatRepository: ChatRepository by lazy {
        ChatRepository(
            db = database,
            settingsRepository = settingsRepository,
            api = chatApi,
            attachmentStore = attachmentStore,
            searchProvider = webSearchProvider,
            webFetcher = webFetcher,
        )
    }

    override fun onCreate() {
        super.onCreate()
        MainThreadWatchdog.startIfDebuggable(
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
        appScope.launch {
            runCatching { chatRepository.sweepOrphanAttachments() }
        }
    }
}
