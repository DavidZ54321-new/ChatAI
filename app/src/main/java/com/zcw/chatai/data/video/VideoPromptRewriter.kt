package com.zcw.chatai.data.video

import com.zcw.chatai.data.net.ChatRequestMessage

/**
 * 视频提示词的优化改写：把用户的抽象说法改写成**按秒分镜**的镜头脚本。
 * 纯逻辑（系统提示词常量 + 请求构造），实际调用走当前对话模型（见 `VideoRepository.rewritePrompt`）。
 */
object VideoPromptRewriter {

    /** 改写用的系统提示词（中文）。刻意约束成「时间戳 + 镜头」的分镜格式。 */
    const val SYSTEM_PROMPT: String =
        "你是「视频生成提示词改写器」。把用户的输入改写成分镜脚本，供文生/图生视频模型使用。\n" +
            "要求：\n" +
            "1. 按时间轴拆成若干镜头，每个镜头一行，格式：`第N个镜头[起-止秒] 运镜/景别：画面与主体动作；光线/氛围；台词或音效（如有）`。" +
            "时间戳连续覆盖整段时长，不要留空档。\n" +
            "2. 具象化：主体外貌、材质、颜色、动作幅度、环境细节都要写清楚；避免抽象词。\n" +
            "3. 全局风格、色彩、镜头语言（如写实/水墨/3D卡通、青橙色调、一镜到底）放在最前面一段统一交代。\n" +
            "4. 若有「图1/图2/首帧」等引用，保留同样的引用与对应关系，不要打乱。\n" +
            "5. 保持原意与原语言（中文就中文，英文就英文）；不新增用户没有表达的主体或诉求，不解释、不评价。\n" +
            "6. 直接输出改写后的分镜脚本本身：不加前后缀、不用引号包裹、不分点编号。\n" +
            "只输出改写结果。"

    fun request(text: String): List<ChatRequestMessage> = listOf(
        ChatRequestMessage(role = "system", content = SYSTEM_PROMPT),
        ChatRequestMessage(role = "user", content = text),
    )
}
