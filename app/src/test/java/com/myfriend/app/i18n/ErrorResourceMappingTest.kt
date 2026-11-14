package com.myfriend.app.i18n

import com.myfriend.app.R
import com.myfriend.app.domain.DomainErrorCode
import com.myfriend.app.domain.NetworkErrorKind
import com.myfriend.app.ui.common.ErrorText
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「错误码 → 资源」的完整性守卫。
 *
 * 0.10.1 把数据层/网络层的中文错误换成了结构化错误码，界面靠 `ErrorText` 映射资源。
 * 这里遍历**所有**错误码与网络错误种类，确认：
 *
 * 1. 每一条都映射到某个 `R.string`（不会漏在 when 外面）；
 * 2. 那条资源在 `values/` 与 `values-en/` 里都存在（英文界面不会掉回中文/空串）；
 * 3. 命名统一 `error_` 前缀，方便 review 时一眼看出「这是错误文案」。
 *
 * 不构造 `Context`：只检查资源 id 与 XML 文件里的 key 集合，纯 JVM 就能跑。
 */
class ErrorResourceMappingTest {

    private val zh = TestResources.strings("values")
    private val en = TestResources.strings("values-en")

    /** R.string 的 id → 名字（R 是普通 Java 类，单测里可以直接反射）。 */
    private val namesById: Map<Int, String> = R.string::class.java.declaredFields
        .filter { it.type == Int::class.javaPrimitiveType }
        .associate { it.getInt(null) to it.name }

    /** `R.plurals` 的 id → 名字（计数文案从 `<string>` 换成 `<plurals>` 之后要用）。 */
    private val pluralNamesById: Map<Int, String> = R.plurals::class.java.declaredFields
        .filter { it.type == Int::class.javaPrimitiveType }
        .associate { it.getInt(null) to it.name }

    @Test
    fun `每个数据层错误码都有中英双语资源`() {
        val missing = DomainErrorCode.entries.mapNotNull { code ->
            val id = ErrorText.domainRes(code)
            if (id == 0) return@mapNotNull "$code -> 0（没有映射）"
            val name = namesById[id]
                ?: return@mapNotNull "$code -> id=$id（R.string 里找不到）"
            when {
                !name.startsWith("error_") -> "$code -> $name（应该以 error_ 开头）"
                name !in zh -> "$code -> $name 在 values/ 里缺失"
                name !in en -> "$code -> $name 在 values-en/ 里缺失"
                else -> null
            }
        }
        assertTrue("以下错误码的资源不完整：\n${missing.joinToString("\n")}", missing.isEmpty())
    }

    @Test
    fun `每个网络错误种类都有中英双语资源`() {
        val samples: List<NetworkErrorKind> = listOf(
            NetworkErrorKind.EmptyUrl,
            NetworkErrorKind.InvalidUrl,
            NetworkErrorKind.HttpsRequired,
            NetworkErrorKind.CleartextDisabled,
            NetworkErrorKind.UnsupportedScheme,
            NetworkErrorKind.InvalidBaseUrl,
            NetworkErrorKind.Http(400),
            NetworkErrorKind.Http(401),
            NetworkErrorKind.Http(403),
            NetworkErrorKind.Http(404),
            NetworkErrorKind.Http(408),
            NetworkErrorKind.Http(413),
            NetworkErrorKind.Http(429),
            NetworkErrorKind.Http(500),
            NetworkErrorKind.Http(418),
            NetworkErrorKind.UnparsableModels,
            NetworkErrorKind.EmptyReply,
            NetworkErrorKind.UnparsableReply,
            NetworkErrorKind.EmptyStream,
            NetworkErrorKind.StreamError(500),
            NetworkErrorKind.ResponseTooLong(8L * 1024 * 1024),
            NetworkErrorKind.NoContent,
            NetworkErrorKind.Timeout,
            NetworkErrorKind.UnknownHost,
            NetworkErrorKind.NetworkInterrupted,
            NetworkErrorKind.ChatQueueBusy,
            NetworkErrorKind.BackgroundQueueBusy,
            NetworkErrorKind.ProviderUnavailable("GEMINI"),
            NetworkErrorKind.WebKeyMissing,
            NetworkErrorKind.WebQueryMissing,
            NetworkErrorKind.WebUrlMissing,
            NetworkErrorKind.WebBaseMissing,
            NetworkErrorKind.WebUnparsable,
            NetworkErrorKind.WebPageUnreachable,
            // 安全 P2-11：web_fetch 的本地 URL 校验有四种拒绝原因，各自都要有中英资源。
            NetworkErrorKind.WebUrlRejected(com.myfriend.app.domain.WebUrlPolicy.Rejection.EMPTY),
            NetworkErrorKind.WebUrlRejected(com.myfriend.app.domain.WebUrlPolicy.Rejection.UNSUPPORTED_SCHEME),
            NetworkErrorKind.WebUrlRejected(com.myfriend.app.domain.WebUrlPolicy.Rejection.MISSING_HOST),
            NetworkErrorKind.WebUrlRejected(com.myfriend.app.domain.WebUrlPolicy.Rejection.LOCAL_HOST),
            NetworkErrorKind.WebStatus(401),
            NetworkErrorKind.WebStatus(402),
            NetworkErrorKind.WebStatus(403),
            NetworkErrorKind.WebStatus(408),
            NetworkErrorKind.WebStatus(429),
            NetworkErrorKind.WebStatus(503),
            NetworkErrorKind.WebStatus(418)
        )
        val missing = samples.mapNotNull { kind ->
            val id = ErrorText.networkRes(kind)
            if (id == 0) return@mapNotNull "$kind -> 0（没有映射）"
            val name = namesById[id] ?: return@mapNotNull "$kind -> id=$id（R.string 里找不到）"
            when {
                !name.startsWith("error_") -> "$kind -> $name（应该以 error_ 开头）"
                name !in zh -> "$kind -> $name 在 values/ 里缺失"
                name !in en -> "$kind -> $name 在 values-en/ 里缺失"
                else -> null
            }
        }
        assertTrue("以下网络错误的资源不完整：\n${missing.joinToString("\n")}", missing.isEmpty())
    }

    @Test
    fun `未知错误与连接成功也都有资源`() {
        val unknown = namesById[R.string.error_unknown]
        assertNotNull("error_unknown 丢了，兜底文案就没有了", unknown)
        assertTrue("error_unknown 在 values/ 缺失", unknown in zh)
        assertTrue("error_unknown 在 values-en/ 缺失", unknown in en)

        val connected = namesById[R.string.api_connect_success]
        assertNotNull(connected)
        assertTrue(connected in zh && connected in en)
        // 0.10.2 起「发现 N 个模型」是计数文案：英文要区分 1 model / 2 models，所以换成 plurals。
        val withModels = pluralNamesById[R.plurals.api_connect_success_models]
        assertNotNull(withModels)
        requireNotNull(withModels)
        val zhPlurals = TestResources.plurals("values")
        val enPlurals = TestResources.plurals("values-en")
        assertTrue(withModels in zhPlurals && withModels in enPlurals)
        assertTrue("英文 plurals 少了 one 变体", "one" in enPlurals.getValue(withModels))
    }
}
