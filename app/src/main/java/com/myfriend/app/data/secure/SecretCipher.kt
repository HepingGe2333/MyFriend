package com.myfriend.app.data.secure

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.myfriend.app.domain.ApiKeyPolicy
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts API keys with a non-exportable AES-256 key held by Android Keystore.
 * Ciphertext format: Base64(IV || AES-GCM ciphertext).
 */
@Singleton
class SecretCipher @Inject constructor() : StringCipher {

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }
    }

    override fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String?): String? {
        if (encoded.isNullOrBlank()) return null
        return runCatching {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            require(combined.size > IV_SIZE)
            val iv = combined.copyOfRange(0, IV_SIZE)
            val payload = combined.copyOfRange(IV_SIZE, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(payload), Charsets.UTF_8)
        }.getOrNull()
    }

    /**
     * 「这条配置到底有没有可用的 Key」= 能解出非空明文（安全审计 P2-6）。
     *
     * 以前只看密文非空：Keystore 里的密钥被系统清掉 / 换机迁移异常之后，密文还在、
     * 但解不出来，界面照样显示「Key 已保存」，发消息只会得到 401。
     *
     * 解密是 Keystore 调用（毫秒级），而这段会被列表页的重组反复调用，所以按**密文**
     * 缓存结果：同一份密文的可用性不会变，密文换了自然就是新键。
     */
    fun hasKey(encoded: String?): Boolean {
        if (encoded.isNullOrBlank()) return false
        usableCache[encoded]?.let { return it }
        val usable = ApiKeyPolicy.isUsable(encoded, decrypt(encoded))
        usableCache[encoded] = usable
        return usable
    }

    /** 密文 → 是否可用。只缓存判定结果（小布尔），不缓存明文。 */
    private val usableCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "myfriend_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
