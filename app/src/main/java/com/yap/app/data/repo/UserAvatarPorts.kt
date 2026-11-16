package com.yap.app.data.repo

import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.data.remote.ServerChatMediaSender
import com.yap.app.data.remote.UserAvatarSettingsPort
import com.yap.app.data.remote.UserAvatarSnapshot
import com.yap.app.data.remote.UserAvatarUploadPort
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「我的头像」端口的生产实现（DataStore + 服务端设置镜像）。
 *
 * 为什么单独一层而不是让 ViewModel 直接拼：顺序（压缩→上传→写设置→推送）、失败回滚、
 * 旧文件回收都要在纯 JVM 单测里可断言；见 [com.yap.app.data.remote.UserAvatarCoordinator]。
 */
@Singleton
class SettingsUserAvatarPort @Inject constructor(
    private val settingsRepository: SettingsRepository
) : UserAvatarSettingsPort {

    override suspend fun read(): UserAvatarSnapshot = settingsRepository.settings.first().let {
        UserAvatarSnapshot(serverRef = it.userAvatarUri, localRef = it.userAvatarLocalUri)
    }

    override suspend fun write(serverRef: String?, localRef: String?) {
        settingsRepository.setUserAvatar(serverRef, localRef)
    }

    override suspend fun push() {
        settingsRepository.pushServerSettings()
    }

    override suspend fun restore(snapshot: UserAvatarSnapshot) {
        settingsRepository.setUserAvatar(snapshot.serverRef, snapshot.localRef)
    }
}

/** 压缩 + `POST /v1/media?kind=USER_AVATAR` 的生产实现（复用 `ServerChatMediaSender`）。 */
@Singleton
class MediaSenderUserAvatarPort @Inject constructor(
    private val sender: ServerChatMediaSender
) : UserAvatarUploadPort {

    override suspend fun upload(localFileUri: String): String =
        sender.uploadUserAvatar(localFileUri).getOrThrow()
            ?: throw IllegalStateException("avatar upload returned no media reference")
}
