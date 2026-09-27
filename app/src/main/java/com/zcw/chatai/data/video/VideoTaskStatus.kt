package com.zcw.chatai.data.video

/**
 * 一条视频任务在本地库里的状态。远端 `task_status` 会折叠进 [RUNNING] / [SUCCEEDED] / [FAILED]。
 * [PENDING] = 尚未提交、[SUBMITTED]/[RUNNING] = 已提交待出结果。
 */
enum class VideoTaskStatus {
    PENDING,
    SUBMITTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    ;

    /** 未到终态：冷启动要对它重排 Worker。 */
    val isActive: Boolean
        get() = this == PENDING || this == SUBMITTED || this == RUNNING

    companion object {
        val ACTIVE: List<String> = entries.filter { it.isActive }.map { it.name }

        fun fromString(value: String): VideoTaskStatus =
            entries.firstOrNull { it.name == value } ?: PENDING
    }
}
