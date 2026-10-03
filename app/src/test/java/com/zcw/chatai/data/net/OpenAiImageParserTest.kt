package com.zcw.chatai.data.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiImageParserTest {

    @Test
    fun decodesB64JsonAndIgnoresUrl() {
        val raw = """
            {"created":1,"data":[{"b64_json":"YQ==","url":"https://example.com/ignored.png"}]}
        """.trimIndent()
        val outcome = OpenAiImageParser.parse(raw) as OpenAiImageOutcome.Success
        assertEquals(1, outcome.images.size)
        assertEquals("a", outcome.images[0].decodeToString())
    }

    @Test
    fun readsOpenAiErrorMessage() {
        val outcome = OpenAiImageParser.parse("""{"error":{"message":"organization must be verified"}}""")
        val failure = outcome as OpenAiImageOutcome.Failure
        assertEquals("organization must be verified", failure.message)
    }

    @Test
    fun emptyDataIsFailure() {
        val outcome = OpenAiImageParser.parse("""{"data":[]}""")
        assertTrue(outcome is OpenAiImageOutcome.Failure)
    }

    @Test
    fun malformedBody() {
        assertTrue(OpenAiImageParser.parse("not-json") is OpenAiImageOutcome.Malformed)
    }
}
