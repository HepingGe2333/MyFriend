package com.yap.app.network

import com.yap.app.domain.NetworkErrorKind
import okio.BufferedSource

/**
 * SSE 单行读取：带长度上限。
 *
 * 为什么单独一个文件：`readUtf8Line()` 会把「直到换行为止」的全部内容读进内存，
 * 一个坏掉/恶意的服务端只要不停发一行不掉换行的数据，App 的堆就会被这一行吃掉。
 * 这里给每行一个上限（默认 8MB），超限就按「这次的回复太长了/服务端异常」中止，
 * 界面上是一句普通错误提示，而不是 OOM。
 *
 * 抽成独立对象是为了能在普通 JVM 单测里用 okio 的 Buffer 直接钉住边界
 * （正常一行 / 没有换行就结束 / 超长一行 / 恰好在上限内）。
 */
object SseLines {

    /** 单行 SSE 上限：8MB。正常分片只有几 KB，超过这个量级只可能是服务端异常。 */
    const val MAX_LINE_BYTES: Long = 8L * 1024 * 1024

    /**
     * 读一行；到流末尾返回 null。
     *
     * @throws ApiException([NetworkErrorKind.ResponseTooLong]) 单行超过 [limit]。
     */
    fun readLine(source: BufferedSource, limit: Long = MAX_LINE_BYTES): String? {
        val buffer = source.buffer
        val newline = buffer.indexOf('\n'.code.toByte(), 0L, limit + 1)
        if (newline == -1L) {
            if (buffer.size > limit) {
                // 丢掉这一行，别让字节继续堆在缓冲区里；随后连接会被 use{} / call.cancel() 关闭。
                source.skip(limit)
                throw ApiException(NetworkErrorKind.ResponseTooLong(limit))
            }
            // 没有换行但也没超限：可能是流末尾（返回 null）或最后一行没有换行符。
            return source.readUtf8Line()
        }
        return source.readUtf8LineStrict(limit)
    }
}
