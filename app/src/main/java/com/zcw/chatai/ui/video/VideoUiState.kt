package com.zcw.chatai.ui.video

import com.zcw.chatai.data.video.VideoInputs
import com.zcw.chatai.data.video.VideoMode
import com.zcw.chatai.data.video.VideoTaskStatus
import com.zcw.chatai.ui.chat.ChatMessageItem
import com.zcw.chatai.ui.chat.PendingAttachment

/** 视频页的可选参数（UI 本地状态，不落库；模型与 prompt_extend 走设置）。 */
data class VideoParams(
    val mode: VideoMode = VideoMode.T2V,
    val resolution: String = "720P",
    // 默认是文生：文生发送时不下发 adaptive（见 VideoGenPayload），默认给一个确定可用的比例。
    val ratio: String = "16:9",
    /** 2~30 秒，或 -1（智能时长）。 */
    val duration: Int = -1,
    val audio: Boolean = true,
) {
    /** 本轮最多能选几张图：文生 0、图生 2（首帧 + 尾帧）、参考生 [VideoInputs.MAX_REFERENCES]。 */
    val imageLimit: Int
        get() = when (mode) {
            VideoMode.T2V -> 0
            VideoMode.I2V -> 2
            VideoMode.R2V -> VideoInputs.MAX_REFERENCES
        }

    val supportsRatio: Boolean
        get() = mode != VideoMode.I2V

    /** 可选比例：文生没有输入素材可「自适应」，故不提供该项（避免选了却被静默丢弃）。 */
    val ratioOptions: List<Pair<String, String>>
        get() = buildList {
            if (mode != VideoMode.T2V) add("adaptive" to "自适应")
            add("16:9" to "16:9")
            add("9:16" to "9:16")
            add("1:1" to "1:1")
            add("4:3" to "4:3")
            add("3:4" to "3:4")
            add("21:9" to "21:9")
        }
}

/** 视频页的 UI 状态（消息复用对话的 [ChatMessageItem]）。 */
data class VideoUiState(
    val conversationId: String? = null,
    val title: String = "新视频",
    val model: String = "",
    val params: VideoParams = VideoParams(),
    val messages: List<ChatMessageItem> = emptyList(),
    /** 助手消息 id → 任务状态（用来区分「等待生成」与「生成中」）。 */
    val taskStatuses: Map<String, VideoTaskStatus> = emptyMap(),
    val input: String = "",
    /** 本轮选中的参考图（图生：首帧/尾帧；参考生：参考图）。 */
    val pending: List<PendingAttachment> = emptyList(),
    val isBusy: Boolean = false,
    /** 提示词改写流式进行中。 */
    val rewriting: Boolean = false,
    /** 是否让服务端做提示词智能改写（设置里的 `video_prompt_extend`）。 */
    val promptExtend: Boolean = true,
    val notice: String? = null,
    /** 是否已配置通义千问的 API Key。 */
    val available: Boolean = true,
) {
    val canSend: Boolean
        get() = !isBusy && !rewriting && when (params.mode) {
            VideoMode.T2V -> input.isNotBlank()
            VideoMode.I2V -> pending.isNotEmpty()
            VideoMode.R2V -> input.isNotBlank() && pending.isNotEmpty()
        }

    /** 图生模式里第一张=首帧、第二张=尾帧；参考生模式里全部是参考图。 */
    val firstFrame: PendingAttachment?
        get() = if (params.mode == VideoMode.I2V) pending.getOrNull(0) else null

    val lastFrame: PendingAttachment?
        get() = if (params.mode == VideoMode.I2V) pending.getOrNull(1) else null

    val references: List<PendingAttachment>
        get() = if (params.mode == VideoMode.R2V) pending else emptyList()
}
