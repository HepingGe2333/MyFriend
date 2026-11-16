package com.yap.app.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 0.12.1「我的头像」上传链路的纯 JVM 单测。
 *
 * 钉住四件事：
 * 1. **顺序**：本地模式只写本地；服务端模式先压缩上传、再写设置、最后镜像；
 * 2. **失败保留旧头像**：上传失败一个字都不写；推送失败回滚本地设置；
 * 3. **拉取设置能还原引用**：`userAvatarUri` / 旧键 `myAvatarUri` 都认，缺键不清空；
 * 4. **本地模式回归**：不发任何网络请求，旧文件按引用回收。
 */
class UserAvatarTest {

    private class FakeSettings(
        var snapshot: UserAvatarSnapshot = UserAvatarSnapshot()
    ) : UserAvatarSettingsPort {
        val events = mutableListOf<String>()
        var failPush = false

        override suspend fun read(): UserAvatarSnapshot = snapshot

        override suspend fun write(serverRef: String?, localRef: String?) {
            events += "write:$serverRef|$localRef"
            snapshot = UserAvatarSnapshot(serverRef, localRef)
        }

        override suspend fun push() {
            events += "push"
            if (failPush) error("push failed")
        }

        override suspend fun restore(snapshot: UserAvatarSnapshot) {
            events += "restore:${snapshot.serverRef}|${snapshot.localRef}"
            this.snapshot = snapshot
        }
    }

    private class FakeUploader(
        private val reference: String = "/v1/media/avatar-new",
        private val fail: Boolean = false
    ) : UserAvatarUploadPort {
        val events = mutableListOf<String>()

        override suspend fun upload(localFileUri: String): String {
            events += "upload:$localFileUri"
            if (fail) error("upload failed")
            return reference
        }
    }

    private class FakeDeleter : UserAvatarLocalDeleter {
        val deleted = mutableListOf<String?>()
        override suspend fun delete(uri: String?) {
            deleted += uri
        }
    }

    @Test
    fun `服务端模式 压缩上传后写引用再镜像 最后回收旧文件`() = runBlocking {
        val settings = FakeSettings(UserAvatarSnapshot(serverRef = "/v1/media/old", localRef = "file:///old.jpg"))
        val uploader = FakeUploader()
        val deleter = FakeDeleter()
        val coordinator = UserAvatarCoordinator(
            settings = settings,
            uploader = uploader,
            isServerMode = { true },
            deleter = deleter
        )

        val saved = coordinator.applyPicked("file:///new.jpg")

        assertEquals("/v1/media/avatar-new", saved)
        assertEquals(listOf("upload:file:///new.jpg"), uploader.events)
        assertEquals(
            listOf("write:/v1/media/avatar-new|file:///new.jpg", "push"),
            settings.events
        )
        assertEquals("/v1/media/avatar-new", settings.snapshot.serverRef)
        assertEquals("本机缓存要留一份供离线显示", "file:///new.jpg", settings.snapshot.localRef)
        assertEquals(listOf<String?>("file:///old.jpg"), deleter.deleted)
    }

    @Test
    fun `上传失败 设置一个字节都不动 旧头像保留`() = runBlocking {
        val settings = FakeSettings(UserAvatarSnapshot(serverRef = "/v1/media/old", localRef = "file:///old.jpg"))
        val deleter = FakeDeleter()
        val coordinator = UserAvatarCoordinator(
            settings = settings,
            uploader = FakeUploader(fail = true),
            isServerMode = { true },
            deleter = deleter
        )

        try {
            coordinator.applyPicked("file:///new.jpg")
            fail("expected upload failure")
        } catch (expected: IllegalStateException) {
            assertEquals("upload failed", expected.message)
        }

        assertTrue(settings.events.isEmpty())
        assertEquals("/v1/media/old", settings.snapshot.serverRef)
        assertEquals("file:///old.jpg", settings.snapshot.localRef)
        assertTrue("失败时不能删旧文件", deleter.deleted.isEmpty())
    }

    @Test
    fun `镜像设置失败 回滚本地引用 旧头像继续显示`() = runBlocking {
        val settings = FakeSettings(UserAvatarSnapshot(serverRef = "/v1/media/old", localRef = "file:///old.jpg"))
        settings.failPush = true
        val deleter = FakeDeleter()
        val coordinator = UserAvatarCoordinator(
            settings = settings,
            uploader = FakeUploader(),
            isServerMode = { true },
            deleter = deleter
        )

        try {
            coordinator.applyPicked("file:///new.jpg")
            fail("expected push failure")
        } catch (expected: IllegalStateException) {
            assertEquals("push failed", expected.message)
        }

        assertEquals(
            listOf(
                "write:/v1/media/avatar-new|file:///new.jpg",
                "push",
                "restore:/v1/media/old|file:///old.jpg"
            ),
            settings.events
        )
        assertEquals("/v1/media/old", settings.snapshot.serverRef)
        assertEquals("file:///old.jpg", settings.snapshot.localRef)
        assertTrue("回滚时不能删旧文件", deleter.deleted.isEmpty())
    }

    @Test
    fun `本地模式只写本地文件 不上传不镜像 旧文件按引用回收`() = runBlocking {
        val settings = FakeSettings(UserAvatarSnapshot(serverRef = null, localRef = null))
        val uploader = FakeUploader()
        val deleter = FakeDeleter()
        val coordinator = UserAvatarCoordinator(
            settings = settings,
            uploader = uploader,
            isServerMode = { false },
            deleter = deleter
        )

        val saved = coordinator.applyPicked("file:///new.jpg")

        assertEquals("file:///new.jpg", saved)
        assertTrue("本地模式一次网络都不发", uploader.events.isEmpty())
        assertEquals(listOf("write:file:///new.jpg|null"), settings.events)
        assertEquals("file:///new.jpg", settings.snapshot.serverRef)
        assertNull("本地模式不该有单独缓存副本", settings.snapshot.localRef)
        assertTrue("没有旧文件可删", deleter.deleted.isEmpty())
    }

    @Test
    fun `恢复默认头像 服务端模式先清引用再镜像`() = runBlocking {
        val settings = FakeSettings(UserAvatarSnapshot(serverRef = "/v1/media/old", localRef = "file:///old.jpg"))
        val deleter = FakeDeleter()
        val coordinator = UserAvatarCoordinator(
            settings = settings,
            uploader = FakeUploader(),
            isServerMode = { true },
            deleter = deleter
        )

        coordinator.clear()

        assertEquals(listOf("write:null|null", "push"), settings.events)
        assertNull(settings.snapshot.serverRef)
        assertNull(settings.snapshot.localRef)
        assertEquals(listOf<String?>("file:///old.jpg"), deleter.deleted)
    }

    @Test
    fun `恢复默认头像镜像失败 回滚旧头像`() = runBlocking {
        val settings = FakeSettings(UserAvatarSnapshot(serverRef = "/v1/media/old", localRef = "file:///old.jpg"))
        settings.failPush = true
        val deleter = FakeDeleter()
        val coordinator = UserAvatarCoordinator(
            settings = settings,
            uploader = FakeUploader(),
            isServerMode = { true },
            deleter = deleter
        )

        try {
            coordinator.clear()
            fail("expected push failure")
        } catch (expected: IllegalStateException) {
            assertEquals("push failed", expected.message)
        }
        assertEquals("/v1/media/old", settings.snapshot.serverRef)
        assertTrue(deleter.deleted.isEmpty())
    }

    @Test
    fun `拉取服务端设置能还原头像引用 旧键 myAvatarUri 也认`() {
        val current = ServerSettingsWrite.read(
            buildJsonObject {
                put("themeMode", "DARK")
                put("myAvatarUri", "/v1/media/avatar-legacy")
            }
        )
        assertEquals("/v1/media/avatar-legacy", current.userAvatarUri)
        assertFalse(current.clearUserAvatar)

        val modern = ServerSettingsWrite.read(
            buildJsonObject { put("userAvatarUri", "/v1/media/avatar-me") }
        )
        assertEquals("/v1/media/avatar-me", modern.userAvatarUri)

        // 服务端版本较老、没这个键 = 不知道，不能把本地头像清掉。
        val missing = ServerSettingsWrite.read(buildJsonObject { put("themeMode", "LIGHT") })
        assertNull(missing.userAvatarUri)
        assertFalse(missing.clearUserAvatar)

        // 显式 null = 服务端明确清空。
        val cleared = ServerSettingsWrite.read(
            buildJsonObject {
                put("userAvatarUri", kotlinx.serialization.json.JsonNull)
            }
        )
        assertTrue(cleared.clearUserAvatar)
        assertEquals("/v1/media/avatar-me", ServerBootstrapMapping.avatarRef("avatar-me"))
        assertNull(ServerBootstrapMapping.avatarRef(""))
    }

    @Test
    fun `设置 payload 带上头像引用 本地模式为 null`() {
        val payload = ServerSettingsWrite.payload(
            com.yap.app.data.prefs.AppSettings(
                userAvatarUri = "/v1/media/avatar-me",
                userAvatarLocalUri = "file:///cache.jpg"
            )
        )
        assertEquals("/v1/media/avatar-me", payload.getValue("userAvatarUri").toString().trim('"'))
        assertTrue("本机缓存副本不能发给服务端", !payload.toString().contains("cache.jpg"))

        val empty = ServerSettingsWrite.payload(com.yap.app.data.prefs.AppSettings())
        assertTrue(empty.getValue("userAvatarUri") is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `USER_AVATAR 上传走 POST v1 media 并返回规范引用`() = runBlocking {
        val server = FakeHttpTestServer()
        try {
            server.respondJson("""{"id":"user-avatar-1","url":"/v1/media/user-avatar-1","kind":"USER_AVATAR"}""")
            val api = ServerApi(
                config = ServerConfig(baseUrl = server.baseUrl, token = "test-token", enabled = true),
                debuggable = true
            )
            val media = api.uploadMedia(
                bytes = ByteArray(32) { 1 },
                mime = "image/jpeg",
                kind = ServerMediaKinds.USER_AVATAR,
                personaId = null
            )
            assertEquals("/v1/media/user-avatar-1", ServerMediaRef.canonical(media.url))
            val request = server.requests.single()
            assertEquals("POST", request.method)
            assertEquals("/v1/media", request.path)
            assertEquals("USER_AVATAR", request.query["kind"])
            assertEquals("image/jpeg", request.query["mime"])
            assertEquals("Bearer test-token", request.headers["authorization"])
        } finally {
            server.close()
        }
    }
}
