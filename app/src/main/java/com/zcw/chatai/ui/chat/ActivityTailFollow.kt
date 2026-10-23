package com.zcw.chatai.ui.chat

/**
 * 思考详情的贴底跟随。纯逻辑，JVM 可测。
 *
 * 第一次测量只登记、不滚动：打开一页已经比视口高的内容时停在开头。
 * 之后只有仍贴底、而且可滚高度变大时，才返回新的底部。
 * 人往上翻由 [Pin.onUserScroll] 松开；再滚回底部又钉上。
 * 面板入场把视口从很矮拉高时，可滚高度会变小。变到内容又能铺满视口（人已经在底）后，
 * 下一次变高仍会跟上，不会被第一帧的小高度钉死。
 */
object ActivityTailFollow {

    const val THRESHOLD_PX = 2

    fun atBottom(value: Int, max: Int): Boolean = max - value <= THRESHOLD_PX

    class Pin {
        var pinned: Boolean = false
            private set

        private var primed = false
        private var previousMax = 0

        /**
         * 视口已经有高度时调用。返回要滚到的位置；null 表示不动。
         * [following] 为 false 时只更新记录，不产生滚动。
         */
        fun onMeasured(value: Int, max: Int, following: Boolean): Int? {
            if (!primed) {
                primed = true
                previousMax = max
                pinned = following && atBottom(value, max)
                return null
            }
            if (!following) {
                pinned = false
                previousMax = max
                return null
            }
            if (pinned && max > previousMax) {
                previousMax = max
                return max
            }
            previousMax = max
            pinned = atBottom(value, max)
            return null
        }

        /** 只有用户自己的滚动调用。程序滚到底不算松开。 */
        fun onUserScroll(value: Int, max: Int, following: Boolean) {
            if (!following) return
            pinned = atBottom(value, max)
        }
    }
}
