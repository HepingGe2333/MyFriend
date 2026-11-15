package com.myfriend.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.myfriend.app.R
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.SeedPersonas
import com.myfriend.app.data.prefs.AppSettings
import com.myfriend.app.data.prefs.SettingsRepository
import com.myfriend.app.data.repo.ApiConfigRepository
import com.myfriend.app.data.repo.ChatRepository
import com.myfriend.app.data.repo.MediaJanitor
import com.myfriend.app.data.repo.MemoryRepository
import com.myfriend.app.data.repo.MessageBlockWriter
import com.myfriend.app.data.repo.MoodRepository
import com.myfriend.app.data.repo.PersonaCardRepository
import com.myfriend.app.data.repo.PersonaRepository
import com.myfriend.app.data.repo.PersonaRepositoryInitializer
import com.myfriend.app.data.repo.PresenceRepository
import com.myfriend.app.data.repo.ProactiveRepository
import com.myfriend.app.ui.screens.chat.ChatViewModel
import com.myfriend.app.ui.screens.home.HomeScreen
import com.myfriend.app.ui.screens.home.HomeViewModel
import com.myfriend.app.ui.theme.MyFriendTheme
import com.myfriend.app.util.ChatNotifier
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * 路径 6 的两个真实口径（原任务里「清空后重播 3 个人格」这条在 Android 上要分两种情形看）。
 *
 * **6a 清空聊天后：人格还在、会话可以重建并重新打开。**
 * 400 多条历史被清掉之后，首页不该变成空白：三个人格（名字来自当前语言资源）必须还在，
 * 启动编排会给他们各补一段空会话，聊天页能正常打开。
 * 这条钉的是 `ErasurePlan.CLEAR_CHATS` 不含 `personas`、以及 `ensureSessionsForAll` 的接线。
 *
 * **6b 删除全部数据后：Android 不会重新播种。**
 * `SettingsRepository.resetAll()` 会**保留**「已播种」标记（源码注释：「Preserve the seed
 * marker so deleting all data does not resurrect sample personas」），所以 `deleteAllData`
 * 之后 `seedIfNeeded()` 什么也不做，首页停在「从一个名字开始」的空状态。
 * 这是当前实现的事实（与 iOS 的「删除后按当前语言重新播种」不一致），
 * 这里把它钉住而不是断言「会重播」——不改生产代码的前提下，断言真实行为才有意义。
 */
@HiltAndroidTest
class ChatUiBehaviorClearReseedTest {

    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Inject lateinit var database: MyFriendDatabase
    @Inject lateinit var blockWriter: MessageBlockWriter
    @Inject lateinit var chatRepository: ChatRepository
    @Inject lateinit var personaRepository: PersonaRepository
    @Inject lateinit var personaInitializer: PersonaRepositoryInitializer
    @Inject lateinit var apiConfigRepository: ApiConfigRepository
    @Inject lateinit var memoryRepository: MemoryRepository
    @Inject lateinit var personaCardRepository: PersonaCardRepository
    @Inject lateinit var moodRepository: MoodRepository
    @Inject lateinit var presenceRepository: PresenceRepository
    @Inject lateinit var proactiveRepository: ProactiveRepository
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var chatNotifier: ChatNotifier
    @Inject lateinit var mediaJanitor: MediaJanitor

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
            chatNotifier = chatNotifier,
            mediaJanitor = mediaJanitor
        )
    }

    @org.junit.After
    fun tearDown() {
        store.clear()
    }

    @Test
    fun 清空聊天后三个人格还在_会话可以重新建立并打开() {
        // 前置：按生产「首次安装」的写法放三个人格（文案取当前语言资源），再补会话。
        runBlocking {
            harness.wipeAllData()
            harness.personaDao.insertAll(SeedPersonas.build(context, System.currentTimeMillis()))
            assertEquals("三个人格各有一段会话", 3, harness.ensureSessionsForAll())
        }
        val expectedNames = listOf(
            context.getString(R.string.seed_persona_1_name),
            context.getString(R.string.seed_persona_2_name),
            context.getString(R.string.seed_persona_3_name)
        )
        assertEquals(
            "种子人格的名字必须来自当前语言资源",
            expectedNames.toSet(),
            runBlocking { harness.personaDao.getAll() }.map { it.name }.toSet()
        )

        // 先聊两句：清空必须真的把消息清掉。
        val persona = runBlocking { harness.personaDao.getAll() }.first()
        val oldSession = runBlocking { harness.sessionDao.latestForPersona(persona.id) }
        assertNotNull("前置：这个人的会话应该已经补出来了", oldSession)
        val seededSession = requireNotNull(oldSession)
        runBlocking {
            harness.appendMessage(seededSession, "c000", ChatUiHarness.sameDayBaseTime())
            harness.appendMessage(seededSession, "c001", ChatUiHarness.sameDayBaseTime() + 1_000L)
        }
        assertEquals(2, runBlocking { harness.messageDao.countInSession(seededSession.id) })

        // 设置页「清空全部聊天与记忆」最终打到仓库层的这一步。
        runBlocking { harness.clearAllChatData() }

        assertEquals("消息被清空", 0, runBlocking { harness.messageDao.countInSession(seededSession.id) })
        assertEquals("会话也被清空", 0, runBlocking { harness.sessionDao.getAll().size })
        val afterClear = runBlocking { harness.personaDao.getAll() }
        assertEquals("人格不能被「清空聊天」带走（CLEAR_CHATS 不含 personas）", 3, afterClear.size)
        assertEquals(
            "名字还是当前语言那一套",
            expectedNames.toSet(),
            afterClear.map { it.name }.toSet()
        )

        // 下次启动编排会补会话；聊天页能正常打开这段新会话。
        assertEquals(3, runBlocking { harness.ensureSessionsForAll() })
        val fresh = runBlocking { harness.sessionDao.latestForPersona(persona.id) }
        assertNotNull("清空之后仍然能给这个人生成一段新会话", fresh)

        val vm: ChatViewModel = harness.viewModel(store, fresh!!.id)
        compose.setContent { ChatUiHost(vm) }
        compose.awaitChatState(vm) { it.loaded && it.persona != null }
        compose.waitForIdle()

        assertTrue("新会话是空的", vm.uiState.value.messages.isEmpty())
        assertTrue(
            "人格名应当出现在聊天页（标题栏 + 空会话居中标题）",
            compose.onAllNodesWithText(persona.name).fetchSemanticsNodes().isNotEmpty()
        )
        compose.onNodeWithText(context.getString(R.string.chat_local_only)).assertIsDisplayed()
    }

    @Test
    fun 删除全部数据后按当前语言重新播种_与iOS一致() {
        runBlocking {
            harness.wipeAllData()
            harness.personaDao.insertAll(SeedPersonas.build(context, System.currentTimeMillis()))
            harness.ensureSessionsForAll()
            settingsRepository.markInitialPersonasSeeded()
        }

        runBlocking {
            // SettingsViewModel.deleteAllData() 的等价序列：清库 → 清设置（**不保留**播种标记）→ 重新播种。
            // 这里刻意跳过 proactiveRepository.wipeAll()：它的 cancelAll() 需要初始化过的 WorkManager，
            // 而测试进程没有生产环境的 WorkManager；规则表在本用例里本来就是空的，不影响「重播」断言。
            chatRepository.wipeAllData()
            harness.resetSettings(preserveSeedMarker = false)
            personaInitializer.seedIfNeeded()
        }

        assertEquals("删除全部数据后应按当前语言重新播种 3 个人格", 3, runBlocking { harness.personaDao.count() })
        assertEquals("三个人格各有一段会话", 3, runBlocking { harness.ensureSessionsForAll() })
        assertEquals("API 配置也被清掉了", 0, runBlocking { harness.apiConfigDao.getAll().size })

        // 首页显示的是重新播种的三个人，而不是空白状态。
        val homeVm = HomeViewModel(chatRepository, personaRepository, settingsRepository, context)
        compose.setContent { HomeUiHost(homeVm) }
        compose.waitUntil(timeoutMillis = 10_000) { homeVm.uiState.value.contacts.size == 3 }
        compose.waitForIdle()
        val seededName = runBlocking { harness.personaDao.getAll().first().name }
        compose.onNodeWithText(seededName).assertIsDisplayed()
    }
}

/** 首页的空状态断言用的小宿主：只装主题 + `HomeScreen`。 */
@Composable
private fun HomeUiHost(viewModel: HomeViewModel) {
    MyFriendTheme(AppSettings(animationsEnabled = false)) {
        HomeScreen(
            onOpenChat = {},
            onOpenSearch = {},
            onOpenSettings = {},
            onOpenPersonas = {},
            onOpenApiConfigs = {},
            onCreatePersona = {},
            onOpenMemory = {},
            viewModel = viewModel
        )
    }
}
