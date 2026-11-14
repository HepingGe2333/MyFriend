package com.myfriend.app.network

import com.myfriend.app.domain.NetworkErrorKind
import com.myfriend.app.domain.UserError
import com.myfriend.app.domain.UserErrorException

/**
 * 把任意异常收敛成 [UserError]，供「要落库 / 要跨协程传」的场景使用。
 *
 * 放在 network 包而不是 domain：domain 不该知道 [ApiException]，而数据层与 UI 都可以依赖 network。
 * 认不出来的异常一律 [UserError.Unknown]（细节只进日志，界面显示通用文案）。
 */
fun Throwable.toUserError(): UserError = when (this) {
    is UserErrorException -> error
    is ApiException -> UserError.Network(kind)
    is ProviderUnavailableException -> UserError.Network(NetworkErrorKind.ProviderUnavailable(provider))
    else -> UserError.Unknown
}

/** 数据层的「抛一个用户可见错误」快捷方式，替代原来 `error("中文")` 的写法。 */
fun userError(
    code: com.myfriend.app.domain.DomainErrorCode,
    arg: String? = null,
    number: Int? = null
): Nothing = throw UserErrorException(code, arg, number)
