package com.zcw.chatai.data.net

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 可恢复的传输层失败：系统掐 socket、对端 reset、读超时。
 * HTTP 4xx/5xx 和用户取消不在此列。纯函数，JVM 可测。
 */
object TransientNetwork {

    const val MAX_ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 300L

    fun isRetryable(error: Throwable): Boolean {
        var current: Throwable? = error
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current is CancellationException) return false
            val lower = current.message.orEmpty().lowercase()
            if (lower.contains("canceled") || lower.contains("cancelled")) return false
            when (current) {
                is SocketTimeoutException -> return true
                is EOFException -> return true
                is SocketException -> if (looksLikeAbortOrReset(lower) || lower.isEmpty()) return true
                is IOException -> {
                    if (looksLikeAbortOrReset(lower) || lower.contains("unexpected end of stream")) {
                        return true
                    }
                }
            }
            current = current.cause
        }
        return false
    }

    private fun looksLikeAbortOrReset(lower: String): Boolean =
        lower.contains("abort") ||
            lower.contains("reset") ||
            lower.contains("broken pipe") ||
            lower.contains("connection closed")

    suspend fun <T> retry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                attempt++
                if (attempt >= MAX_ATTEMPTS || !isRetryable(t)) throw t
                delay(RETRY_DELAY_MS * attempt)
            }
        }
    }
}
