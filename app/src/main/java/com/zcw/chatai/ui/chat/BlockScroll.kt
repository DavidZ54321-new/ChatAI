package com.zcw.chatai.ui.chat

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity

/**
 * 展开块的统一容器（思考过程 / 工具调用结果共用）：
 * 高度上限 [maxHeight]（= 视窗高 30%），超出部分**块内滚动**，
 * 滚到头的余量不交给外层聊天列表。
 *
 * [maxHeight] 传 `Dp.Unspecified` 时 `heightIn` 视为不限制（视窗高未知的兜底）。
 */
@Composable
fun BlockScrollContainer(
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .nestedScroll(remember(scrollState) { isolateBlockScroll(scrollState) })
            .verticalScroll(scrollState),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * 吃掉块内滚动用不完的滚动量和 fling，避免穿透到外层聊天列表。
 *
 * 「只有内容真的超过上限（块内能滚）时才吃」——否则内容本来就装得下，
 * 拖它却被吞掉滚动量，会变成一块点不动的死区（展开的短工具结果最容易命中）。
 */
private fun isolateBlockScroll(state: ScrollState): NestedScrollConnection =
    object : NestedScrollConnection {
        private val overflowing: Boolean get() = state.maxValue > 0

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset = if (overflowing) available else Offset.Zero

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
            if (overflowing) available else Velocity.Zero
    }

// 图行（LazyRow）与块内纵向滚动是不同轴的嵌套滚动容器，Compose 的轴锁定本身就是
// 「先启动的轴获胜」：横向先越过 touch slop 时内层 LazyRow 消费掉手势，纵向滚动不会同时启动。
// 所以这里不需要额外的横向优先手势拦截（加了反而可能在无横向可滚时吞掉纵向拖动）。
