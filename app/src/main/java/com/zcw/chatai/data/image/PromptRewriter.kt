package com.zcw.chatai.data.image

import com.zcw.chatai.data.net.ChatRequestMessage

/**
 * 生图/改图的提示词优化改写：把用户的抽象说法改写成**画面式**的具体描述。
 * 纯逻辑（系统提示词常量 + 请求构造），实际调用走当前对话模型（见 `ImageRepository.rewritePrompt`）。
 */
object PromptRewriter {

    /** 改写用的系统提示词（中文）。刻意约束「画面式而非动作式」，并保留多图引用与图号。 */
    const val SYSTEM_PROMPT: String =
        "你是「图像生成/编辑提示词改写器」。把用户输入的提示词改写为**直接可用的画面描述**，" +
            "供文生图与图像编辑模型使用。\n" +
            "要求：\n" +
            "1. 具象化：把抽象、笼统的说法换成可见的具体细节——主体的形态外貌、材质、服饰、颜色、" +
            "数量、位置、画面内文字及其样式。\n" +
            "2. 画面式而非动作式：优先描述「画面里看起来是什么样」（构图、镜头、视角、景别、光线、" +
            "色调、氛围、风格、画质），而不是「做了什么动作」。\n" +
            "3. 若输入提到「图1/图2」等多图引用，保留同样的图号引用与对应关系，不要打乱。\n" +
            "4. 保持原意与原语言（中文就中文，英文就英文）；不新增用户没有表达的主体或诉求，不解释、不评价。\n" +
            "5. 直接输出改写后的提示词本身：不加「改写后：」之类的前后缀，不用引号包裹，不分点编号。\n" +
            "只输出改写结果。"

    fun request(text: String): List<ChatRequestMessage> = listOf(
        ChatRequestMessage(role = "system", content = SYSTEM_PROMPT),
        ChatRequestMessage(role = "user", content = text),
    )
}
