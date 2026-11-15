package com.myfriend.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.myfriend.app.R
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.prefs.SettingsRepository
import com.myfriend.app.data.repo.ApiConfigRepository
import com.myfriend.app.data.repo.ChatRepository
import com.myfriend.app.data.repo.MemoryRepository
import com.myfriend.app.data.repo.MessageBlockWriter
import com.myfriend.app.data.repo.MoodRepository
import com.myfriend.app.data.repo.PersonaCardRepository
import com.myfriend.app.data.repo.PersonaRepository
import com.myfriend.app.data.repo.PresenceRepository
import com.myfriend.app.data.repo.ProactiveRepository
import com.myfriend.app.ui.screens.chat.ChatViewModel
import com.myfriend.app.util.ChatNotifier
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * 路径 5：**发送失败时草稿与已选图片都要留着，并给出一条资源化的失败提示**。
 *
 * 失败怎么注入（刻意不用假客户端）：
 *
 * `ChatViewModel.send()` 只在**写用户消息失败**时保留草稿；如果消息写进去了、只是模型没回来，
 * 草稿已经按「发出去了」清掉了（见 `send()` 里 `draft = ""` 的位置）。所以这条路径的
 * 真实失败面是「落库失败」。这里在 `messages` 表上挂一个 `BEFORE INSERT ... RAISE(ABORT)`
 * 触发器，让下一次落库真的失败——不关库、不改生产代码、测完就 DROP，可重复。
 *
 * 断言：
 * - Snackbar 出现 `R.string.chat_send_failed`（`ErrorText` 把未知异常回落成这个资源）；
 * - 输入框里的文字还在（读 `EditableText` 语义，不只看状态）；
 * - 待发送图片的缩略图还在（`chat_pending_image` 的 contentDescription）；
 * - 状态层：`draft` 原样、`images` 还是 1 张、消息总数没变（失败的那条没落库）。
 */
@HiltAndroidTest
class ChatUiBehaviorSendFailureTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Inject lateinit var database: MyFriendDatabase
    @Inject lateinit var blockWriter: MessageBlockWriter
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var personaRepository: PersonaRepository
    @Inject lateinit var apiConfigRepository: ApiConfigRepository
    @Inject lateinit var memoryRepository: MemoryRepository
    @Inject lateinit var personaCardRepository: PersonaCardRepository
    @Inject lateinit var moodRepository: MoodRepository
    @Inject lateinit var presenceRepository: PresenceRepository
    @Inject lateinit var proactiveRepository: ProactiveRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var chatNotifier: ChatNotifier

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store = ViewModelStore()
    private lateinit var harness: ChatUiHarness

    @Before
    fun setUp() {
        hilt.inject()
        harness = ChatUiHarness(
            context = context,
            db = database,
            blockWriter = blockWriter,
            chatRepository = chatRepository,
            personaRepository = personaRepository,
            apiConfigRepository = apiConfigRepository,
            memoryRepository = memoryRepository,
            personaCardRepository = personaCardRepository,
            moodRepository = moodRepository,
            presenceRepository = presenceRepository,
            proactiveRepository = proactiveRepository,
            settingsRepository = settingsRepository,
            chatNotifier = chatNotifier
        )
    }

    @org.junit.After
    fun tearDown() {
        dropSendFailureTrigger()
        store.clear()
    }

    @Test
    fun 写库失败时草稿与图片都保留_并显示发送失败提示() {
        val seeded = runBlocking { harness.seedConversation(messageCount = 4) }
        val vm: ChatViewModel = harness.viewModel(store, seeded.session.id)
        compose.setContent { ChatUiHost(vm) }
        compose.awaitChatState(vm) { it.loaded && it.messages.isNotEmpty() }
        compose.waitForIdle()

        val draft = "这条发不出去"
        compose.onNode(hasSetTextAction()).performTextInput(draft)
        compose.awaitChatState(vm) { it.draft == draft }

        // 一张真的落盘的图片（走生产的 MediaStorage：contentResolver 读 file:// 即可）。
        vm.addImages(listOf(createTempImage()))
        compose.awaitChatState(vm) { it.images.size == 1 }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(context.getString(R.string.chat_pending_image))
            .assertExists()

        // 让下一步「写用户消息」真的失败。
        installSendFailureTrigger()

        compose.onNodeWithContentDescription(context.getString(R.string.chat_send)).performClick()

        val sendFailed = context.getString(R.string.chat_send_failed)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(sendFailed).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(sendFailed).assertExists()

        val state = vm.uiState.value
        assertEquals("草稿必须留着（用户不会想重打一遍）", draft, state.draft)
        assertEquals("已选图片也要留着", 1, state.images.size)
        assertEquals("失败的那条不该落库", 4, state.messages.size)

        val editable = compose.onNode(hasSetTextAction())
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
        assertTrue("输入框里的文字应该还在（实际 \"${editable.text}\"）", editable.text.contains(draft))
        compose.onNodeWithContentDescription(context.getString(R.string.chat_pending_image))
            .assertExists()
    }

    // ------------------------------------------------------------------ 工具

    private fun installSendFailureTrigger() {
        harness.database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER IF NOT EXISTS ui_test_fail_send " +
                "BEFORE INSERT ON messages BEGIN SELECT RAISE(ABORT, 'ui-test: send must fail'); END"
        )
    }

    private fun dropSendFailureTrigger() {
        runCatching {
            harness.database.openHelper.writableDatabase
                .execSQL("DROP TRIGGER IF EXISTS ui_test_fail_send")
        }
    }

    private fun createTempImage(): Uri {
        val file = File(context.cacheDir, "ui-test-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(Color.RED)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return Uri.fromFile(file)
    }
}
