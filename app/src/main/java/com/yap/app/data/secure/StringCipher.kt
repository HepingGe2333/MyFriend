package com.yap.app.data.secure

/**
 * 「把一段明文变成可以落盘的密文」的最小接口。
 *
 * 生产实现是 [SecretCipher]（Android Keystore + AES-GCM）；抽成接口的唯一原因是
 * 纯 JVM 单测跑不了 Keystore：服务端地址/令牌的加密存储读写、换机后解不开的回退，
 * 这些规则都属于**数据层**，必须能在 `:app:testDebugUnitTest` 里验证。
 *
 * 约定：
 * - [encrypt] 对空串返回空串（「没有值」没有加密的意义）；
 * - [decrypt] 对空串/坏密文返回 `null`，**绝不抛异常**：Keystore 被系统重置、换机迁移失败时，
 *   调用方应该退化成「没有配置」，而不是让设置页或同步协程崩掉。
 */
interface StringCipher {
    fun encrypt(plainText: String): String
    fun decrypt(encoded: String?): String?
}
