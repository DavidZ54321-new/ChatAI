package com.zcw.chatai.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
    /** 是否处于「跟随」状态（用户上滑、触摸或展开详情会松钉）。 */
    val following: Boolean,
    /** 当前会话是否已完成首次定位（切会话后先藏起来定位，避免看到顶部再瞬移）。 */
    val located: Boolean,
    val nestedScrollConnection: NestedScrollConnection,
    /** 跳到底部并恢复跟随（切会话 / 发消息 / 点「回到底部」）。 */
    val jumpToBottom: () -> Unit,
    /** 停止跟随：用户触摸列表 / 展开工具、思考详情时调用。 */
    val unpin: () -> Unit,
)

/**
 * 跟随政策（用户正在看内容就绝不能被打扰）：
 * - **只有贴底跟随时才随生成下滑**，且只向前补溢出、绝不重定位；
 * - **其余任何时刻不自动跳转**——停在内容上（生成气泡区/历史）就纹丝不动；
 * - 切会话（消息就绪后）→ 先**跳**到底并标记已定位（定位门）；
 * - 列表**长出**新的用户消息（刚发出/继续）→ **跳**到底；
 *   流式幽灵消失等造成的列表回缩**不算**——那是竞态帧，跳过去就是「瞬移到用户气泡」；
 * - **手指一碰就松钉**（不再跟随）；上滑第一下 [atBottom] 往往还是 true，
 *   所以钉住和 [atBottom] 必须分开；
 * - 回到底部**不自动重钉**：只有用户自己滚回到底（[userScrolled]）或走 [jumpToBottom]
 *   才恢复跟随，避免生成结束时高度突变把 [atBottom] 闪成 true 就把人拽回底部。
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
    // 用户手指刚滚过列表：只有这种情况「滚回到底」才恢复跟随。
    val userScrolled = remember { mutableStateOf(false) }
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

    // 身份固定：pointerInput / nestedScroll 拿到的永远是同一个 lambda。
    val stopFollowing = remember {
        {
            pinned.value = false
            // 首次定位还没完成时别把定位任务掐掉，否则会永远停在「未定位」。
            if (locatingTarget.value == null) {
                job.value?.cancel()
                job.value = null
            }
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 正 y = 内容下移 = 看更早的消息。程序滚动不走 UserInput。
                if (source == NestedScrollSource.UserInput) {
                    userScrolled.value = true
                    if (available.y > 0f) {
                        pinned.value = false
                        if (locatingTarget.value == null) {
                            job.value?.cancel()
                            job.value = null
                        }
                    }
                }
                return Offset.Zero
            }
        }
    }

    var previouslyAtBottom by remember { mutableStateOf(true) }
    LaunchedEffect(atBottom) {
        when {
            !atBottom -> {
                // 离开底部就把「用户滚过」的记号清掉：此后哪怕高度突变把 atBottom 闪回 true，
                // 也不再自动重钉（那是生成结束拽回底部的元凶）。
                userScrolled.value = false
            }

            !previouslyAtBottom && userScrolled.value -> {
                // 用户自己滚回到底 → 恢复跟随。
                userScrolled.value = false
                pinned.value = true
            }
        }
        previouslyAtBottom = atBottom
    }

    var lastSeenConversation by remember { mutableStateOf(conversationId) }
    var lastSeenSize by remember { mutableIntStateOf(-1) }
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
        if (switched) {
            pinned.value = true
            lastSeenSize = -1
        }
        val grewToNewMessage = lastSeenSize >= 0 && messages.size > lastSeenSize
        lastSeenSize = messages.size

        // 定位门：消息还没到（LazyColumn 本来也不显示）时先不动；有消息了才跳，跳完才允许显示。
        if (locatedConversation.value != target) {
            if (messages.isNotEmpty()) launchJump(target)
            return@LaunchedEffect
        }
        // 「长出了用户消息」= 刚发出/继续，跳到底。
        // 回缩（流式幽灵消失、重生成删除）时末条也可能是 USER，那不是发送，绝不能跳。
        if (lastMessage?.role == Role.USER && grewToNewMessage) {
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
        unpin = stopFollowing,
    )
}
