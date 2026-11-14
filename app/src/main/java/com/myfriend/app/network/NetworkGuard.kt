package com.myfriend.app.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object NetworkGuard {
    fun parseHttpUrl(raw: String, allowInsecureHttp: Boolean): HttpUrl {
        val value = raw.trim()
        if (value.isEmpty()) throw ApiException(null, "地址不能为空")
        val url = value.toHttpUrlOrNull() ?: throw ApiException(null, "地址格式不正确，请填写完整的 http(s) 地址")
        if (url.scheme == "https") return url
        if (url.scheme == "http" && allowInsecureHttp) return url
        if (url.scheme == "http") {
            throw ApiException(null, "出于安全要求，仅允许 HTTPS。若使用本地服务，请在 API 配置中显式开启“允许不安全 HTTP”。")
        }
        throw ApiException(null, "仅支持 HTTP(S) 地址")
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
            ?: throw ApiException(null, "API 地址无效，请检查 Base URL")
    }
}
