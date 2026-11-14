package com.myfriend.app.network

import com.myfriend.app.domain.NetworkErrorKind
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 地址校验。
 *
 * 抛出的异常只带 [NetworkErrorKind]，界面文案由 `ui/` 侧映射资源；
 * 这里不再出现任何用户可见的中文句子。
 */
object NetworkGuard {
    fun parseHttpUrl(raw: String, allowInsecureHttp: Boolean): HttpUrl {
        val value = raw.trim()
        if (value.isEmpty()) throw ApiException(NetworkErrorKind.EmptyUrl)
        val url = value.toHttpUrlOrNull() ?: throw ApiException(NetworkErrorKind.InvalidUrl)
        if (url.scheme == "https") return url
        if (url.scheme == "http" && allowInsecureHttp) return url
        if (url.scheme == "http") {
            throw ApiException(NetworkErrorKind.HttpsRequired)
        }
        throw ApiException(NetworkErrorKind.UnsupportedScheme)
    }

    fun normalizeBaseUrl(raw: String): String {
        var base = raw.trim().trimEnd('/')
        base = base.removeSuffix("/chat/completions").removeSuffix("/models")
        return base
    }

    fun endpoint(baseUrl: String, path: String): HttpUrl {
        val base = normalizeBaseUrl(baseUrl)
        val suffix = path.trimStart('/')
        val root = if (base.endsWith("/v1")) base else "$base/v1"
        return "$root/$suffix".toHttpUrlOrNull()
            ?: throw ApiException(NetworkErrorKind.InvalidBaseUrl)
    }
}
