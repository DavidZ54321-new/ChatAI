package com.zcw.chatai.data.prefs

import kotlinx.serialization.Serializable

/** 一个工作区记住的供应商和模型。两个都空表示还没选过。 */
@Serializable
data class WorkspaceMemory(
    val providerId: String = "",
    val model: String = "",
)

enum class WorkspaceSlot {
    Image,
    Video,
    Text,
}
