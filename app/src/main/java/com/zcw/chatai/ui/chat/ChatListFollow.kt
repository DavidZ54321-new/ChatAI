package com.zcw.chatai.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import com.zcw.chatai.data.model.Role
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 聊天列表是否钉在底部，以及如何滚到底。 */
class ChatListFollow(
    val atBottom: Boolean,
    val nestedScrollConnection: NestedScrollConnection,
    val scrollToEnd: () -> Unit,
)

/**
 * 跟随政策：切会话 / 刚发出用户消息 → 钉住并滚到底；其余只在已钉住时跟随。
 * 上滑第一下 [atBottom] 往往还是 true，所以钉住和 [atBottom] 必须分开。
 */
@Composable
fun rememberChatListFollow(
    listState: LazyListState,
    conversationId: String?,
    messages: List<ChatMessageItem>,
    isStreaming: Boolean,
): ChatListFollow {
    val scope = rememberCoroutineScope()
    val pinned = remember { mutableStateOf(true) }
    val job = remember { mutableStateOf<Job?>(null) }
    val lastMessage = messages.lastOrNull()

    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            isChatListAtBottom(
                lastVisibleIndex = info.visibleItemsInfo.lastOrNull()?.index,
                totalItems = info.totalItemsCount,
                canScrollForward = listState.canScrollForward,
            )
        }
    }

    fun scrollToEnd() {
        job.value?.cancel()
        job.value = scope.launch {
            listState.scrollToEnd(stillPinned = { pinned.value })
        }
    }

    fun pinAndScroll() {
        pinned.value = true
        scrollToEnd()
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 正 y = 内容下移 = 看更早的消息。程序滚动不走 UserInput。
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    pinned.value = false
                    job.value?.cancel()
                    job.value = null
                }
                return Offset.Zero
            }
        }
    }

    var previouslyAtBottom by remember { mutableStateOf(true) }
    LaunchedEffect(atBottom) {
        if (atBottom && !previouslyAtBottom) pinned.value = true
        previouslyAtBottom = atBottom
    }

    var lastSeenConversation by remember { mutableStateOf(conversationId) }
    LaunchedEffect(
        conversationId,
        messages.size,
        lastMessage?.id,
        lastMessage?.content?.length,
        lastMessage?.reasoning?.length,
        isStreaming,
    ) {
        val switched = conversationId != lastSeenConversation
        lastSeenConversation = conversationId
        if (messages.isEmpty()) {
            if (switched) pinned.value = true
            return@LaunchedEffect
        }
        if (switched || lastMessage?.role == Role.USER) {
            pinAndScroll()
            return@LaunchedEffect
        }
        if (pinned.value) scrollToEnd()
    }

    return ChatListFollow(
        atBottom = atBottom,
        nestedScrollConnection = nestedScrollConnection,
        scrollToEnd = { pinAndScroll() },
    )
}
