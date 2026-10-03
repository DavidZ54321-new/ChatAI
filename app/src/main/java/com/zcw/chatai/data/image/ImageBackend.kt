package com.zcw.chatai.data.image

import com.zcw.chatai.data.model.ChatConfig

/**
 * 一个图像供应商。生图仓库只依赖这份接口。
 *
 * 再加供应商 = 新子包里的一个实现 + 注册一行，不在仓库或界面里写厂商分支。
 */
interface ImageBackend {
    val providerId: String

    /** 单次请求最多几张输入图（含静默带入的上一张）。 */
    val maxInputImages: Int

    fun builtinModels(): List<ImageModelOption>

    /**
     * 远程模型列表。失败、没 Key、空结果都返回空表，调用方继续用 [builtinModels]。
     */
    suspend fun listModels(config: ChatConfig): List<ImageModelOption>

    /** 生成结果的原始字节（调用方落盘）。千问内部仍是 URL 再下载。 */
    suspend fun generate(config: ChatConfig, request: ImageGenerateRequest): List<ByteArray>
}
