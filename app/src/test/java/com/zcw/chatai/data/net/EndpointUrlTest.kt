package com.zcw.chatai.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointUrlTest {

    @Test
    fun appendsVersionAndPathWhenMissing() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            EndpointUrl.chatCompletions("https://api.deepseek.com"),
        )
    }

    @Test
    fun keepsVersionAndNormalizesTrailingSlash() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            EndpointUrl.chatCompletions("https://api.openai.com/v1/"),
        )
    }

    @Test
    fun keepsLocalBaseUrlAndPort() {
        assertEquals(
            "http://192.168.1.5:11434/v1/chat/completions",
            EndpointUrl.chatCompletions("http://192.168.1.5:11434/v1"),
        )
    }

    @Test
    fun keepsFullEndpointUntouched() {
        assertEquals(
            "https://x.com/v1/chat/completions",
            EndpointUrl.chatCompletions(" https://x.com/v1/chat/completions "),
        )
    }

    @Test
    fun returnsNullForBlankBaseUrl() {
        assertNull(EndpointUrl.chatCompletions("   "))
        assertNull(EndpointUrl.chatCompletions(""))
    }

    @Test
    fun derivesModelsEndpointFromBaseUrl() {
        assertEquals(
            "https://api.deepseek.com/v1/models",
            EndpointUrl.models("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com/v1/models",
            EndpointUrl.models("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.openai.com/v1/models",
            EndpointUrl.models("https://api.openai.com/v1/chat/completions"),
        )
        assertNull(EndpointUrl.models(""))
    }

    @Test
    fun derivesAnthropicMessagesEndpoint() {
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            EndpointUrl.anthropicMessages("https://api.deepseek.com"),
        )
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            EndpointUrl.anthropicMessages("https://api.deepseek.com/v1"),
        )
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            EndpointUrl.anthropicMessages("https://api.deepseek.com/v1/chat/completions"),
        )
        assertEquals(
            "https://api.deepseek.com/anthropic/v1/messages",
            EndpointUrl.anthropicMessages("https://api.deepseek.com/anthropic/v1"),
        )
        assertEquals(
            "http://192.168.1.5:11434/anthropic/v1/messages",
            EndpointUrl.anthropicMessages("http://192.168.1.5:11434/v1"),
        )
        assertNull(EndpointUrl.anthropicMessages(""))
        assertNull(EndpointUrl.anthropicMessages("   "))
    }

    @Test
    fun derivesResponsesEndpoint() {
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/responses",
            EndpointUrl.responses("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/responses",
            EndpointUrl.responses("https://dashscope.aliyuncs.com/compatible-mode/v1/"),
        )
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1/responses",
            EndpointUrl.responses("https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"),
        )
        assertEquals(
            "http://192.168.1.5:11434/v1/responses",
            EndpointUrl.responses("http://192.168.1.5:11434"),
        )
        // 已经是完整端点时不再二次拼接
        assertEquals(
            "https://x.com/v1/responses",
            EndpointUrl.responses(" https://x.com/v1/responses "),
        )
        assertNull(EndpointUrl.responses(""))
        assertNull(EndpointUrl.responses("   "))
    }

    @Test
    fun derivesDashScopeUploadsEndpointFromOrigin() {
        assertEquals(
            "https://dashscope.aliyuncs.com/api/v1/uploads",
            EndpointUrl.dashScopeUploads("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
        assertEquals(
            "https://ws-xxx.cn-beijing.maas.aliyuncs.com/api/v1/uploads",
            EndpointUrl.dashScopeUploads("https://ws-xxx.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"),
        )
        assertNull(EndpointUrl.dashScopeUploads(""))
        assertNull(EndpointUrl.dashScopeUploads("not a url"))
    }

    @Test
    fun originOfExtractsSchemeHostAndPort() {
        assertEquals("https://a.example", EndpointUrl.originOf("https://a.example/v1"))
        assertEquals("https://a.example", EndpointUrl.originOf("https://a.example"))
        assertEquals("http://192.168.1.5:11434", EndpointUrl.originOf("http://192.168.1.5:11434/v1/chat"))
        assertNull(EndpointUrl.originOf(""))
        assertNull(EndpointUrl.originOf("a.example/v1"))
    }
}
