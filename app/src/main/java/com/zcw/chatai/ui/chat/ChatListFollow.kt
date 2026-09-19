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

/** 聊天列表的跟随状态与动作。 */
class ChatListFollow(
    /** 视口是否真的停在列表末尾。 */
    val atBottom: Boolean,
    /** 是否处于「跟随」状态（用户上滑或展开详情会松钉）。 */
    val following: Boolean,
    /** 当前会话是否已完成首次定位（切会话后先藏起来定位，避免看到顶部再瞬移）。 */
    val located: Boolean,
    val nestedScrollConnection: NestedScrollConnection,
    /** 跳到底部并恢复跟随（切会话 / 发消息 / 点「回到底部」）。 */
    val jumpToBottom: () -> Unit,
    /** 临时松钉：用户展开工具/思考详情时调用，避免随后到达的结果把他拽走。 */
    val unpin: () -> Unit,
)

/**
 * 跟随政策：
 * - 切会话（消息就绪后）→ 先**跳**到底并标记已定位，之后才允许显示/跟随（定位门）；
 * - 刚发出用户消息 → **跳**到底；
 * - 其余内容增长（流式正文、工具结果）→ 仅在钉住且已定位时**跟**（只补溢出、不重定位）；
 * - 用户上滑第一下 [atBottom] 往往还是 true，所以钉住和 [atBottom] 必须分开。
 *
 * 定位任务用 [locatingTarget] 标记：**follow 不会取消它**，否则会出现「进去空白、划一下才出来」。
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
    // 已完成首次定位的会话；以及「正在定位哪个会话」。
    val locatedConversation = remember { mutableStateOf<String?>(null) }
    val locatingTarget = remember { mutableStateOf<String?>(null) }
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

    fun cancel() {
        job.value?.cancel()
        job.value = null
    }

    /** 跳到底并（必要时）标记定位完成。定位期间 follow 不会进来抢 job。 */
    fun launchJump(target: String) {
        cancel()
        pinned.value = true
        locatingTarget.value = target
        job.value = scope.launch {
            val reached = listState.jumpToEnd(stillPinned = { pinned.value })
            if (locatingTarget.value == target) {
                // 不管有没有到底都要露出列表，否则定位被打断时会一直不可见；
                // 若还处于跟随态却没到底（极端超长项），再补一次跟随。
                locatedConversation.value = target
                locatingTarget.value = null
                if (!reached && pinned.value) {
                    job.value = scope.launch { listState.followToEnd(stillPinned = { pinned.value }) }
                }
            }
        }
    }

    fun launchFollow() {
        cancel()
        job.value = scope.launch { listState.followToEnd(stillPinned = { pinned.value }) }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 正 y = 内容下移 = 看更早的消息。程序滚动不走 UserInput。
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    pinned.value = false
                    // 首次定位还没完成时别把定位任务掐掉，否则会永远停在「未定位」。
                    if (locatingTarget.value == null) {
                        job.value?.cancel()
                        job.value = null
                    }
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
        val target = conversationId ?: return@LaunchedEffect
        val switched = target != lastSeenConversation
        lastSeenConversation = target
        if (switched) pinned.value = true

        // 定位门：消息还没到（LazyColumn 本来也不显示）时先不动；有消息了才跳，跳完才允许显示。
        if (locatedConversation.value != target) {
            if (messages.isNotEmpty()) launchJump(target)
            return@LaunchedEffect
        }
        if (lastMessage?.role == Role.USER) {
            launchJump(target)
            return@LaunchedEffect
        }
        if (pinned.value) launchFollow()
    }

    val located = conversationId == null || locatedConversation.value == conversationId
    return ChatListFollow(
        atBottom = atBottom,
        following = pinned.value,
        located = located,
        nestedScrollConnection = nestedScrollConnection,
        jumpToBottom = { conversationId?.let { launchJump(it) } },
        unpin = {
            pinned.value = false
            if (locatingTarget.value == null) cancel()
        },
    )
}
