package com.yap.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 一条消息里附带的一张图片，file uri 指向应用私有目录中的副本。 */
@Serializable
data class MessageAttachment(
    val uri: String,
    val mime: String = "image/jpeg",
    val width: Int = 0,
    val height: Int = 0
) {
    val isImage: Boolean get() = mime.startsWith("image/")
}

object AttachmentsCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(items: List<MessageAttachment>): String? {
        if (items.isEmpty()) return null
        return runCatching { json.encodeToString(items) }.getOrNull()
    }

    fun decode(raw: String?): List<MessageAttachment> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<MessageAttachment>>(raw) }
            .getOrDefault(emptyList())
            .filter { it.uri.isNotBlank() }
    }
}
