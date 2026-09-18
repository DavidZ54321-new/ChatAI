package com.zcw.chatai.data.net

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TransientNetworkTest {

    @Test
    fun abortResetAndTimeoutAreRetryable() {
        assertTrue(TransientNetwork.isRetryable(SocketException("Software caused connection abort")))
        assertTrue(TransientNetwork.isRetryable(SocketException("Connection reset")))
        assertTrue(TransientNetwork.isRetryable(SocketException("Broken pipe")))
        assertTrue(TransientNetwork.isRetryable(SocketTimeoutException("timeout")))
        assertTrue(TransientNetwork.isRetryable(IOException("unexpected end of stream")))
        assertTrue(TransientNetwork.isRetryable(EOFException()))
        assertTrue(TransientNetwork.isRetryable(SocketException()))
        assertTrue(
            TransientNetwork.isRetryable(
                ChatApiException("联网搜索失败：Software caused connection abort", SocketException("Software caused connection abort")),
            ),
        )
    }

    @Test
    fun cancelAndHttpErrorsAreNotRetryable() {
        assertFalse(TransientNetwork.isRetryable(CancellationException("cancelled")))
        assertFalse(TransientNetwork.isRetryable(IOException("Canceled")))
        assertFalse(TransientNetwork.isRetryable(ChatApiException("API Key 无效")))
        assertFalse(TransientNetwork.isRetryable(IllegalStateException("boom")))
    }

    @Test
    fun retrySucceedsAfterTransientFailures() = runBlocking {
        var attempts = 0
        val result = TransientNetwork.retry {
            attempts++
            if (attempts < 3) throw SocketException("Software caused connection abort")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, attempts)
    }

    @Test
    fun retryStopsOnNonTransient() = runBlocking {
        var attempts = 0
        try {
            TransientNetwork.retry<Unit> {
                attempts++
                throw ChatApiException("API Key 无效")
            }
            fail("expected ChatApiException")
        } catch (e: ChatApiException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("API Key"))
        }
        assertEquals(1, attempts)
    }

    @Test
    fun retryPropagatesCancellationWithoutExtraAttempts() = runBlocking {
        var attempts = 0
        try {
            TransientNetwork.retry<Unit> {
                attempts++
                throw CancellationException("stop")
            }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
        assertEquals(1, attempts)
    }
}
