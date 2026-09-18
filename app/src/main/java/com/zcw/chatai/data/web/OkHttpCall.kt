package com.zcw.chatai.data.web

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okio.Buffer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 一次 HTTP 响应：状态码、内容类型、上限内的字节、声明的总长度。 */
internal data class HttpBody(
    val code: Int,
    val contentType: String?,
    val bytes: ByteArray,
    val contentLength: Long,
) {
    val text: String get() = bytes.toString(Charsets.UTF_8)
}

/**
 * 可取消的 OkHttp 调用：**连读 body 都在可取消区内**。
 *
 * 协程被取消时立刻 `call.cancel()`，不再干等阻塞 socket 读超时（搜索 90s / 抓取 30s）。
 * body 读取如果放到 `await` 返回之后再 `body.string()`，取消就管不到它了——所以这里一次性读完。
 */
internal suspend fun OkHttpClient.awaitBody(request: Request, maxBytes: Int): HttpBody =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val buffer = Buffer()
                        response.body.source().read(buffer, maxBytes.toLong())
                        continuation.resume(
                            HttpBody(
                                code = response.code,
                                contentType = response.header("Content-Type"),
                                bytes = buffer.readByteArray(),
                                contentLength = response.body.contentLength(),
                            ),
                        )
                    } catch (t: Throwable) {
                        if (continuation.isCancelled) return
                        continuation.resumeWithException(t)
                    }
                }
            },
        )
    }
