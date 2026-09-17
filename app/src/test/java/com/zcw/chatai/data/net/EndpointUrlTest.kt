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
}
