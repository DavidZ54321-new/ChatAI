package com.zcw.chatai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zcw.chatai.data.model.MessageStatus
import com.zcw.chatai.data.model.Role
import com.zcw.chatai.data.prefs.ThemeMode
import com.zcw.chatai.ui.chat.ChatMessageItem
import com.zcw.chatai.ui.chat.ChatScreen
import com.zcw.chatai.ui.chat.ChatUiState
import com.zcw.chatai.ui.theme.ChatAITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var themeMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                    ) { darkTheme },
                )
            }
            ChatAITheme(darkTheme = darkTheme) {
                var state by remember { mutableStateOf(demoChatState()) }
                ChatScreen(
                    state = state,
                    onInputChange = { state = state.copy(input = it) },
                    onSend = { state = demoAppendUserMessage(state) },
                    onStop = {},
                    onRetry = {},
                    onRegenerate = {},
                    onDeleteMessage = { id ->
                        state = state.copy(messages = state.messages.filterNot { it.id == id })
                    },
                    onNewConversation = { state = demoChatState() },
                    onClearConversation = { state = demoChatState() },
                    onOpenSettings = {},
                    onOpenDrawer = {},
                )
            }
        }
    }
}

private fun demoChatState(): ChatUiState {
    val d = "${'$'}"
    val answer = """
        ## 快速排序

        `quicksort` 的平均时间复杂度是 ${d}O(n \log n)${d}，最坏 ${d}O(n^2)${d}。

        行内公式 ${d}E = mc^2${d}，块级推导：

        ${d}${d}
        T(n) = 2T\left(\frac{n}{2}\right) + O(n)
        ${d}${d}

        ```kotlin
        fun quickSort(items: IntArray, lo: Int = 0, hi: Int = items.lastIndex) {
            if (lo >= hi) return
            val pivot = items[(lo + hi) / 2]
            var i = lo
            var j = hi
            while (i <= j) {
                while (items[i] < pivot) i++
                while (items[j] > pivot) j--
                if (i <= j) {
                    val tmp = items[i]
                    items[i] = items[j]
                    items[j] = tmp
                    i++
                    j--
                }
            }
        }
        ```

        | 算法 | 平均 | 最坏 | 稳定性 |
        | --- | --- | --- | --- |
        | 快排 | O(n log n) | O(n²) | 不稳定 |
        | 归并 | O(n log n) | O(n log n) | 稳定 |

        > 分治思想：先分区，再对两侧递归。

        - 原地排序，常数小
        - 最坏情况出现在已近有序的数据上
        - 随机化选 pivot 可以规避
    """.trimIndent()

    val followUp = """
        简单说，看两点：**稳定性**和**最坏情况**。

        - 要稳定、且要可预测的最坏 ${d}O(n \log n)${d} → 归并排序
        - 追求常数小、内存敏感、平均更快 → 快速排序

        > 工程上通常是折中：标准库多用内省排序（introsort），先在快排上跑，递归过深时切堆排序。
    """.trimIndent()

    return ChatUiState(
        title = "快速排序的原理",
        model = "deepseek-chat",
        messages = listOf(
            ChatMessageItem(
                id = "u1",
                role = Role.USER,
                content = "帮我讲讲快速排序，并给个 Kotlin 示例。",
            ),
            ChatMessageItem(
                id = "a1",
                role = Role.ASSISTANT,
                content = answer,
                model = "deepseek-chat",
                completionTokens = 486,
            ),
            ChatMessageItem(
                id = "u2",
                role = Role.USER,
                content = "它和归并排序该怎么选？",
            ),
            ChatMessageItem(
                id = "a2",
                role = Role.ASSISTANT,
                content = followUp,
                model = "deepseek-chat",
                completionTokens = 128,
            ),
        ),
    )
}

private fun demoAppendUserMessage(state: ChatUiState): ChatUiState {
    val text = state.input.trim()
    if (text.isEmpty()) return state
    val index = state.messages.size
    val userMessage = ChatMessageItem(
        id = "u-$index",
        role = Role.USER,
        content = text,
    )
    val assistantMessage = ChatMessageItem(
        id = "a-$index",
        role = Role.ASSISTANT,
        content = "",
        status = MessageStatus.STREAMING,
        model = state.model,
    )
    return state.copy(
        input = "",
        isStreaming = true,
        streamingMessageId = assistantMessage.id,
        messages = state.messages + userMessage + assistantMessage,
    )
}
