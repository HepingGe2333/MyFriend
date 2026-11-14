package com.myfriend.app.domain

/**
 * 「允许不安全 HTTP」到底能不能生效。
 *
 * 0.10.2 起主 manifest 是 `usesCleartextTraffic="false"`，只有 debug 构建
 * （`app/src/debug/AndroidManifest.xml`）才放开系统层。也就是说：**发布包里
 * 用户就算把开关打开，请求也发不出去**。这时候必须在 App 自己的闸门（[com.myfriend.app.network.NetworkGuard]）
 * 给出明确原因，而不是让 OkHttp 抛一个「明文被系统拒绝」的 IO 异常、
 * 或者悄悄失败成超时。
 *
 * 语义只有两条：
 * - 用户没显式同意 → 拒绝（`HttpsRequired`）；
 * - 用户同意了，但构建本身不允许（release）→ 拒绝并给「发布版只允许 HTTPS」的文案。
 *
 * 纯函数，release 语义可以在 JVM 单测里直接验（单测的 `BuildConfig.DEBUG` 是 true，
 * 所以这里不读它，由调用方传进来）。
 */
object CleartextHttpPolicy {

    /** 这次构建 + 这次用户同意，能不能发 `http://`。 */
    fun allowsInsecureHttp(allowInsecureHttp: Boolean, debuggable: Boolean): Boolean =
        allowInsecureHttp && debuggable

    /**
     * 被拒绝时该报哪种原因。
     *
     * @return 允许时返回 null；否则返回 [NetworkErrorKind.HttpsRequired]（用户没同意）
     *         或 [NetworkErrorKind.CleartextDisabled]（构建不允许）。
     */
    fun rejection(allowInsecureHttp: Boolean, debuggable: Boolean): NetworkErrorKind? = when {
        allowsInsecureHttp(allowInsecureHttp, debuggable) -> null
        !allowInsecureHttp -> NetworkErrorKind.HttpsRequired
        else -> NetworkErrorKind.CleartextDisabled
    }
}
