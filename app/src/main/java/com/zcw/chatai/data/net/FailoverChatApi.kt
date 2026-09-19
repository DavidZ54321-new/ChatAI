package com.zcw.chatai.data.net

import android.util.Log
import com.zcw.chatai.data.model.ChatConfig
import com.zcw.chatai.data.provider.ProviderCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 主对话的面级兜底装饰器：chat/completions 不可用时换 Responses 面重放整回合。
 *
 * 两条保命规则（顺序不能反）：
 * 1. **只在零内容时换面**（fail-fast）：第一个有效增量到达之前抛端点级错误才换；
 *    一旦流出过任何正文/思考/工具增量，再失败就直接抛——中途换面会造成
 *    重复计费和内容叠写，比报错更坏。
 * 2. **只给已知好用的映射换面**：仅当 [GoDialogueFace] 判定该模型优先 Responses
 *    且本供应商配了 Responses 兜底时才排第二面；否则单面直发、错误原样透出。
 *    反例：deepseek-flash 的 Responses 面会无视工具空转，chat 503 时换过去
 *    只会得到一个更迷惑的空回答，不如直接报"服务暂时不可用"。
 *
 * 换面只换**协议面**，不换供应商、不换 key、不换模型：同一套 [ChatConfig] 原样重放，
 * 达到一次尝试的上限（两面各一次）即停，没有重试风暴。
 * 对上层它就是一个普通的 [ChatApi]：`ChatRepository` / UI / 落库零感知。
 */
class FailoverChatApi(
    private val primary: ChatApi,
    /** providerId → 该供应商的 Responses 兜底面（目前只有 OpenCode Go）。 */
    private val responsesFallbacks: Map<String, ChatApi> = mapOf(
        ProviderCatalog.OPENCODE_GO to ResponsesChatApi(),
    ),
    /** 换面日志（默认 logcat；JVM 单测没有 android.util.Log，只能注入）。 */
    private val log: (String) -> Unit = { message -> Log.i(TAG, message) },
) : ChatApi {

    override fun stream(config: ChatConfig, messages: List<ChatRequestMessage>): Flow<ChatStreamEvent> = flow {
        val fallback = responsesFallbacks[config.providerId]
        val faces = if (fallback != null && GoDialogueFace.prefersResponses(config.model)) {
            listOf(fallback to "responses", primary to "chat")
        } else {
            listOf(primary to "chat")
        }
        var lastError: Throwable? = null
        faces.forEachIndexed { index, (face, faceName) ->
            if (index > 0) log("主对话换面 (${config.model})：${faces[index - 1].second} 不可用，改走 $faceName")
            var grew = false
            try {
                face.stream(config, messages).collect { event ->
                    if (event.grewContent()) grew = true
                    emit(event)
                }
                return@flow
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                lastError = t
                // 有内容了才失败 = 真·中途掐断，换面只会更糟；够换面条件才继续。
                if (grew || !isEndpointError(t) || index == faces.lastIndex) throw t
            }
        }
        throw lastError ?: IllegalStateException("no dialogue face attempted")
    }

    /** 设置页的测试连接/模型列表：走主面即可，兜底面没有列表端点也不需要。 */
    override suspend fun listModels(config: ChatConfig): List<String> = primary.listModels(config)

    private fun ChatStreamEvent.grewContent(): Boolean = when (this) {
        is ChatStreamEvent.Delta -> content?.isNotEmpty() == true || reasoning?.isNotEmpty() == true
        is ChatStreamEvent.ToolCallDelta ->
            id?.isNotEmpty() == true || name?.isNotEmpty() == true || arguments?.isNotEmpty() == true
        else -> false
    }

    /** 端点级错误（面没挂载）才值得换面：key 错/余额/限流换面也没用。 */
    private fun isEndpointError(t: Throwable): Boolean {
        val message = t.message ?: return false
        return ApiErrorMapper.isEndpointUnavailable(message) ||
            ApiErrorMapper.isUnsupportedFormat(message)
    }

    companion object {
        private const val TAG = "FailoverChatApi"
    }
}
