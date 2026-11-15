package com.myfriend.app.data.repo

import android.content.Context
import com.myfriend.app.domain.MessageAttachment
import com.myfriend.app.util.MediaStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「删文件之前先查引用」的唯一门面（0.12 媒体去重的收尾）。
 *
 * 各条删除路径（删消息 / 删会话 / 清空聊天 / 删人格 / 删预设 / 换头像 / 备份替换 /
 * `DataEraser`）都只调这里，不各自拼守卫——守卫写八遍就会有一遍忘了查引用，
 * 而这一次忘掉的代价是用户另一段对话里的图永久消失。
 *
 * 组合关系：
 * - [MediaStorage] 负责「只删 App 自己目录里的文件」（归属守卫）；
 * - [MediaFileGc] 负责「还有引用吗 → 才删」的纯逻辑；
 * - [MediaReferences] 回答引用问题（Room + DataStore）；
 * - 这里把它们接起来，并按「**先删数据库行、再调这里**」的顺序由调用方使用。
 */
@Singleton
class MediaJanitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val references: MediaReferences
) {

    /**
     * 进程级作用域：给「生命周期已经结束、但文件还得删」的场景兜底。
     *
     * 典型是 `ViewModel.onCleared()`：`viewModelScope` 已经取消，不能在里面 `launch`；
     * 但草稿里的图片（用户选了又没发）仍然要回收，而且回收前照样要查引用
     * （用户可能选了一张已经发过的图——内容寻址会让两次选图指向同一个文件）。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 删一个文件（`file://` 路径或裸文件名）；还有引用就留着。 */
    suspend fun deleteIfUnreferenced(uri: String?): Int {
        val value = uri?.trim().orEmpty()
        if (value.isEmpty()) return 0
        return MediaStorage.deleteIfUnreferenced(context, value) { names -> references.referencedNames(names) }
    }

    /** 批量删文件；还有引用就留着。 */
    suspend fun deleteFilesIfUnreferenced(uris: List<String>): Int {
        if (uris.isEmpty()) return 0
        return MediaStorage.deleteFilesIfUnreferenced(context, uris) { names -> references.referencedNames(names) }
    }

    /** 删消息附件（删消息 / 删会话 / 清空上下文 / 放弃草稿都走这里）。 */
    suspend fun deleteAttachmentsIfUnreferenced(attachments: List<MessageAttachment>): Int =
        deleteFilesIfUnreferenced(attachments.map { it.uri })

    /**
     * 只删聊天图（`files/media/chat-*`），[keepFileNames] 是这次导入刚写进来的新图。
     *
     * 备份「替换」导入用它：旧图按引用回收；新图既在 keep 里、也刚被库引用，双保险。
     */
    suspend fun clearChatImagesExcept(keepFileNames: Set<String>): Int =
        MediaStorage.clearChatImagesExceptIfUnreferenced(context, keepFileNames) { names ->
            references.referencedNames(names)
        }

    /**
     * 删头像目录里没有被 [keepUris] 引用、且数据库里也没有行引用的文件。
     *
     * [keepUris] 之外还会按 [MediaReferences] 再查一遍：`keepUris` 是按「备份里的引用」算的，
     * 一个刚写进来、但备份里没写成引用格式的新头像会漏在 keep 外面——只按 keep 删就会把它删掉，
     * 用户看到的是一个「接回来了却没有脸」的人格。
     */
    suspend fun clearUnreferencedAvatars(keepUris: Set<String>): Int =
        MediaStorage.clearUnreferencedAvatarsIfUnreferenced(context, keepUris) { names ->
            references.referencedNames(names)
        }

    /**
     * `ViewModel.onCleared()` 用：进程级作用域上异步删，守卫照旧。
     *
     * 用 `runCatching` 吞掉失败：ViewModel 已经销毁，没人能处理异常；留下一个孤儿文件
     * 远好过让进程崩在 `onCleared` 里。
     */
    fun deleteAttachmentsLater(attachments: List<MessageAttachment>) {
        if (attachments.isEmpty()) return
        val uris = attachments.map { it.uri }
        scope.launch { runCatching { deleteFilesIfUnreferenced(uris) } }
    }
}
