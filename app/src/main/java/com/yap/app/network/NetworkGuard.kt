package com.yap.app.network

import com.yap.app.BuildConfig
import com.yap.app.domain.CleartextHttpPolicy
import com.yap.app.domain.NetworkErrorKind
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 地址校验。
 *
 * 抛出的异常只带 [NetworkErrorKind]，界面文案由 `ui/` 侧映射资源；
 * 这里不再出现任何用户可见的中文句子。
 *
 * ## 0.10.2：明文 HTTP 是「用户同意 + 构建允许」两件事
 *
 * 主 manifest 从 0.10.2 起是 `usesCleartextTraffic="false"`，只有 debug 构建
 * （`app/src/debug/AndroidManifest.xml`）放开系统层。所以 `allowInsecureHttp`
 * 在发布包里即使为 true 也发不出明文——这里必须**明确拒绝**并给出
 * 「发布版只允许 HTTPS」的原因（[NetworkErrorKind.CleartextDisabled]），
 * 而不是让 OkHttp 抛一个 `UnknownServiceException` 或悄悄超时。
 *
 * [debuggable] 默认取 `BuildConfig.DEBUG`；单测显式传参来覆盖 release 语义。
 */
object NetworkGuard {
    fun parseHttpUrl(
        raw: String,
        allowInsecureHttp: Boolean,
        debuggable: Boolean = BuildConfig.DEBUG
    ): HttpUrl {
        val value = raw.trim()
        if (value.isEmpty()) throw ApiException(NetworkErrorKind.EmptyUrl)
        val url = value.toHttpUrlOrNull() ?: throw ApiException(NetworkErrorKind.InvalidUrl)
        when (url.scheme) {
            "https" -> return url
            "http" -> {
                val rejection = CleartextHttpPolicy.rejection(allowInsecureHttp, debuggable)
                if (rejection != null) throw ApiException(rejection)
                return url
            }
            else -> throw ApiException(NetworkErrorKind.UnsupportedScheme)
        }
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
