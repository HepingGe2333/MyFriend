package com.yap.app.domain

/**
 * 「这条 API 配置到底有没有可用的 Key」（安全审计 P2-6）。
 *
 * 以前 `SecretCipher.hasKey` 只看密文非空：Keystore 里的密钥被系统清掉、换机迁移异常、
 * 或者用户恢复了备份之后，密文还在、但**解不出来**。界面显示「Key 已保存」，
 * 发消息只会得到 401——用户看到的是「API Key 无效」，于是去检查一个其实没问题的 Key。
 *
 * 现在分三态：[ApiKeyState.USABLE] 才算已配置；[ApiKeyState.UNRECOVERABLE] 说明句是
 * 「密钥无法解密，请重新填写」（资源化文案），而不是降级成 401。
 *
 * 判定是纯函数（密文 + 解出来的明文 → 状态），Keystore 是 Android 侧的实现细节。
 */
enum class ApiKeyState {
    /** 没有配 Key（密文为空）。 */
    MISSING,

    /** 能解出非空 Key：真的可用。 */
    USABLE,

    /** 有密文但解不出来：Keystore 失效，需要用户重新填写。 */
    UNRECOVERABLE
}

/** 三态判定的唯一实现。 */
object ApiKeyPolicy {

    fun evaluate(cipherText: String?, decrypted: String?): ApiKeyState = when {
        cipherText.isNullOrBlank() -> ApiKeyState.MISSING
        decrypted.isNullOrBlank() -> ApiKeyState.UNRECOVERABLE
        else -> ApiKeyState.USABLE
    }

    /** 界面上的「已保存」= [ApiKeyState.USABLE]。 */
    fun isUsable(cipherText: String?, decrypted: String?): Boolean =
        evaluate(cipherText, decrypted) == ApiKeyState.USABLE
}
