package com.myfriend.app.di

import android.app.ActivityManager
import android.content.Context
import androidx.room.Room
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.myfriend.app.data.local.ApiConfigDao
import com.myfriend.app.data.local.ApiUsageDao
import com.myfriend.app.data.local.ChatSessionDao
import com.myfriend.app.data.local.MemoryDao
import com.myfriend.app.data.local.MemoryTermDao
import com.myfriend.app.data.local.MemoryVectorDao
import com.myfriend.app.data.local.MemoryVersionDao
import com.myfriend.app.data.local.MemoryConsolidationDao
import com.myfriend.app.data.local.MemoryEchoDao
import com.myfriend.app.data.local.MediaReferenceDao
import com.myfriend.app.data.local.MessageBlockDao
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.AgentIntentDao
import com.myfriend.app.data.local.AgentTickDao
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaPendingPatchDao
import com.myfriend.app.data.local.PersonaPresetDao
import com.myfriend.app.data.local.PersonaRevisionDao
import com.myfriend.app.data.local.ProactiveRuleDao
import com.myfriend.app.BuildConfig
import com.myfriend.app.core.AgentCoreClient
import com.myfriend.app.core.AgentCoreClients
import com.myfriend.app.data.ai.DefaultJsonLlmClient
import com.myfriend.app.data.ai.JsonLlmClient
import com.myfriend.app.data.repo.DefaultPersonaTranslationGateway
import com.myfriend.app.data.repo.PersonaTranslationGateway
import com.myfriend.app.data.repo.AndroidErasureFiles
import com.myfriend.app.data.repo.MediaReferenceSource
import com.myfriend.app.data.repo.RoomErasureStore
import com.myfriend.app.data.repo.RoomMediaReferenceSource
import com.myfriend.app.data.repo.RoomServerChatSink
import com.myfriend.app.data.repo.RoomServerEventSink
import com.myfriend.app.data.repo.RoomServerStateSink
import com.myfriend.app.data.remote.CredentialBackend
import com.myfriend.app.data.remote.DataStoreServerLocalStore
import com.myfriend.app.data.remote.DataStoreServerPresenceStore
import com.myfriend.app.data.remote.ServerApiRemote
import com.myfriend.app.data.remote.ServerChatGateway
import com.myfriend.app.data.remote.ServerChatSink
import com.myfriend.app.data.remote.ServerEventNotifier
import com.myfriend.app.data.remote.ServerEventPoller
import com.myfriend.app.data.remote.ServerEventSink
import com.myfriend.app.data.remote.ServerLocalStore
import com.myfriend.app.data.remote.ServerModeRepository
import com.myfriend.app.data.remote.ServerPresenceStore
import com.myfriend.app.data.remote.ModeServerRemoteProvider
import com.myfriend.app.data.remote.ServerRemoteProvider
import com.myfriend.app.data.remote.ServerStateSink
import com.myfriend.app.data.remote.ServerModeStateSource
import com.myfriend.app.data.remote.ServerStateSource
import com.myfriend.app.data.remote.ServerSyncManager
import com.myfriend.app.data.remote.SharedPreferencesCredentialBackend
import com.myfriend.app.data.secure.SecretCipher
import com.myfriend.app.data.secure.StringCipher
import com.myfriend.app.util.ServerModeNotifier
import com.myfriend.app.domain.AppMemoryBudget
import com.myfriend.app.domain.DataEraser
import com.myfriend.app.domain.ErasureFiles
import com.myfriend.app.domain.ErasureStore
import com.myfriend.app.domain.SessionGenerationRegistry
import com.myfriend.app.network.AiProviderRegistry
import com.myfriend.app.network.ApiUsageRecorder
import com.myfriend.app.network.ClaudeAdapter
import com.myfriend.app.network.GeminiAdapter
import com.myfriend.app.network.LlmGatekeeper
import com.myfriend.app.network.OpenAiAdapter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MyFriendDatabase =
        Room.databaseBuilder(context, MyFriendDatabase::class.java, MyFriendDatabase.NAME)
            .addMigrations(
                MyFriendDatabase.MIGRATION_1_2,
                MyFriendDatabase.MIGRATION_2_3,
                MyFriendDatabase.MIGRATION_3_4,
                MyFriendDatabase.MIGRATION_4_5,
                MyFriendDatabase.MIGRATION_5_6,
                MyFriendDatabase.MIGRATION_6_7,
                MyFriendDatabase.MIGRATION_7_8,
                MyFriendDatabase.MIGRATION_8_9,
                MyFriendDatabase.MIGRATION_9_10,
                MyFriendDatabase.MIGRATION_10_11,
                MyFriendDatabase.MIGRATION_11_12,
                MyFriendDatabase.MIGRATION_12_13,
                MyFriendDatabase.MIGRATION_13_14
            )
            // 这里刻意不写 fallbackToDestructiveMigration()。
            // 对他来说，记忆就是「记得」本身：迁移写错时宁可当场崩溃让我们看见，
            // 也不能把用户几年攒下来的对话和记忆悄悄清空——那种损失用户根本无从察觉，
            // 只会觉得「他变了、他忘了我」，而这恰恰是最不能发生的事。
            .build()

    @Provides fun personaDao(db: MyFriendDatabase): PersonaDao = db.personaDao()
    @Provides fun revisionDao(db: MyFriendDatabase): PersonaRevisionDao = db.revisionDao()
    @Provides fun presetDao(db: MyFriendDatabase): PersonaPresetDao = db.presetDao()
    @Provides fun apiConfigDao(db: MyFriendDatabase): ApiConfigDao = db.apiConfigDao()
    @Provides fun chatSessionDao(db: MyFriendDatabase): ChatSessionDao = db.chatSessionDao()
    @Provides fun messageDao(db: MyFriendDatabase): MessageDao = db.messageDao()
    @Provides fun messageBlockDao(db: MyFriendDatabase): MessageBlockDao = db.messageBlockDao()
    @Provides fun memoryDao(db: MyFriendDatabase): MemoryDao = db.memoryDao()
    @Provides fun proactiveRuleDao(db: MyFriendDatabase): ProactiveRuleDao = db.proactiveRuleDao()
    @Provides fun memoryEchoDao(db: MyFriendDatabase): MemoryEchoDao = db.memoryEchoDao()
    @Provides fun memoryConsolidationDao(db: MyFriendDatabase): MemoryConsolidationDao = db.memoryConsolidationDao()
    @Provides fun apiUsageDao(db: MyFriendDatabase): ApiUsageDao = db.apiUsageDao()
    @Provides fun memoryTermDao(db: MyFriendDatabase): MemoryTermDao = db.memoryTermDao()
    @Provides fun memoryVersionDao(db: MyFriendDatabase): MemoryVersionDao = db.memoryVersionDao()
    @Provides fun memoryVectorDao(db: MyFriendDatabase): MemoryVectorDao = db.memoryVectorDao()
    @Provides fun personaPendingPatchDao(db: MyFriendDatabase): PersonaPendingPatchDao = db.personaPendingPatchDao()

    @Provides fun mediaReferenceDao(db: MyFriendDatabase): MediaReferenceDao = db.mediaReferenceDao()

    /**
     * 媒体引用计数的数据来源（0.12）：删文件之前「还有没有人引用」的唯一查询口。
     *
     * 接口在 data 层、实现打 Room + DataStore；抽接口只为了纯 JVM 单测能塞内存假实现
     * （见 `MediaReferenceGcTest`），生产装配没有第二份实现。
     */
    @Provides
    @Singleton
    fun provideMediaReferenceSource(impl: RoomMediaReferenceSource): MediaReferenceSource = impl

    @Provides fun agentIntentDao(db: MyFriendDatabase): AgentIntentDao = db.agentIntentDao()

    @Provides fun agentTickDao(db: MyFriendDatabase): AgentTickDao = db.agentTickDao()

    /**
     * 四条删除路径共用的清理器。
     *
     * [DataEraser] 本身是纯 Kotlin（在 `:agentcore`，两端共用同一份 class），所以不能在它头上写
     * `@Inject`；接口在 domain、实现（Room / Android 文件）在 data 层，这里做绑定。
     */
    @Provides
    @Singleton
    fun provideErasureStore(impl: RoomErasureStore): ErasureStore = impl

    @Provides
    @Singleton
    fun provideErasureFiles(impl: AndroidErasureFiles): ErasureFiles = impl

    @Provides
    @Singleton
    fun provideDataEraser(store: ErasureStore, files: ErasureFiles): DataEraser =
        DataEraser(store, files)

    /** 会话世代号：清空/删除时 bump，历史压缩与生成收尾写库前核对。 */
    @Provides
    @Singleton
    fun provideSessionGeneration(): SessionGenerationRegistry = SessionGenerationRegistry()

    /**
     * agent 决策层：默认进程内；只有构建开关 `AGENT_CORE_REMOTE=true` 时才走 `agentd`。
     *
     * 为什么在这里装配而不是让业务代码自己 new：
     * - 调用点（ChatRepository / ToolExecutor / AgentLoop / 心跳 / 主动判定）只依赖 [AgentCoreClient]，
     *   换实现不用改业务；
     * - 远程实现带了「失败回退进程内 + 记日志」，装配成单例后所有调用点共享同一个连接池与超时策略；
     * - 这里**没有**用户可见开关：远程模式是 `-PagentCoreRemote=true` 的构建配置，release 默认进程内。
     */
    @Provides
    @Singleton
    fun provideAgentCoreClient(okHttp: OkHttpClient): AgentCoreClient = AgentCoreClients.create(
        remoteEnabled = BuildConfig.AGENT_CORE_REMOTE,
        baseUrl = BuildConfig.AGENT_CORE_BASE_URL,
        httpClient = okHttp
    )

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
        isLenient = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 全 App 唯一的 Coil [ImageLoader]。
     *
     * 之前没人提供它，Coil 就用默认值自己造了一个：内存缓存上限是「可用内存的 25%」，
     * 在一个 8GB 的机器上等于几百 MB 的额度——上限不是实际占用，但它意味着系统压力
     * 上来之前，图片缓存愿意替我们攒多少张已经翻过去的图。对「头像 + 聊天图片」这种
     * 场景，收成 `min(可用内存×12%, 48MB)` 更合理，剩下的内存还给系统。
     *
     * 其它几条都是有意为之：
     * - `respectCacheHeaders(false)`：本地文件和相册图片没有有意义的缓存头，
     *   关掉之后同一张头像不会因为服务端没给 `Cache-Control` 就反复解码；
     * - 磁盘 128MB：头像原始文件在 files/ 里，这层只是 Coil 的解码缓存；
     * - **位图缓存不碰**：沿用 Coil 默认的 BitmapPool，避免自己调错复用策略反而更抖。
     *
     * 所有 `AsyncImage` 最终都通过 `MyFriendApplication` 的 `ImageLoaderFactory`
     * 拿到这一个实例（见 `MyFriendApplication.newImageLoader`）。
     */
    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val availableMemoryBytes = activityManager?.let { manager ->
            ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }.availMem
        } ?: 0L
        // 0 会让 Coil 的 MemoryCache 计算出一个无效上限，这里至少给 1 字节（等于不缓存）。
        // Coil 的 MemoryCache 上限参数是 Int，48MB 远在 Int 范围内。
        val memoryCacheBytes = AppMemoryBudget.imageMemoryCacheBytes(availableMemoryBytes)
            .coerceAtLeast(1L)
            .toInt()
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizeBytes(memoryCacheBytes)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve(AppMemoryBudget.IMAGE_DISK_CACHE_DIR))
                    .maxSizeBytes(AppMemoryBudget.IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }

    /**
     * OpenAI 系 adapter 显式拿到记账器与并发闸：两者都不是可选项，
     * 所以这里直接塞进构造参数，而不是让 adapter 去某个静态句柄上「碰运气」。
     */
    @Provides
    @Singleton
    fun provideOpenAiAdapter(
        client: OkHttpClient,
        json: Json,
        recorder: ApiUsageRecorder,
        gatekeeper: LlmGatekeeper
    ): OpenAiAdapter = OpenAiAdapter(client, json, recorder, gatekeeper)

    @Provides
    @Singleton
    fun provideGeminiAdapter(): GeminiAdapter = GeminiAdapter()

    @Provides
    @Singleton
    fun provideClaudeAdapter(): ClaudeAdapter = ClaudeAdapter()

    @Provides
    @Singleton
    fun provideRegistry(openAi: OpenAiAdapter, gemini: GeminiAdapter, claude: ClaudeAdapter): AiProviderRegistry =
        AiProviderRegistry(openAi, gemini, claude)

    /**
     * 话风/世界补全用的一次性 JSON 调用。
     *
     * 接口只暴露「给我一段 JSON」，生产实现复用现有 API 配置与 Provider 适配器；
     * 单测里注入假实现就能把各种坏输出跑一遍（不需要网络、不需要数据库）。
     */
    @Provides
    @Singleton
    fun provideJsonLlmClient(impl: DefaultJsonLlmClient): JsonLlmClient = impl

    /**
     * 「按需翻译人设」的依赖面（`spec/persona-translation/README.md` §2）。
     *
     * 绑定成接口而不是直接用实现：协调器的去重/缓存规则必须在纯 JVM 单测里可跑，
     * 而生产实现背后是 Room + DataStore。
     */
    @Provides
    @Singleton
    fun providePersonaTranslationGateway(
        impl: DefaultPersonaTranslationGateway
    ): PersonaTranslationGateway = impl

    // ---------------------------------------------------------------- 服务端模式（M3/M4）

    /**
     * 服务端地址/令牌复用 API Key 的 Keystore AES-GCM 方案。
     *
     * 抽 [StringCipher] 接口只为了纯 JVM 单测：Keystore 在单测里不存在，但「地址/令牌
     * 是不是加密存的、解不开时怎么回退」必须能测。
     */
    @Provides
    @Singleton
    fun provideStringCipher(impl: SecretCipher): StringCipher = impl

    @Provides
    @Singleton
    fun provideCredentialBackend(impl: SharedPreferencesCredentialBackend): CredentialBackend = impl

    @Provides
    @Singleton
    fun provideServerLocalStore(impl: DataStoreServerLocalStore): ServerLocalStore = impl

    @Provides
    @Singleton
    fun provideServerPresenceStore(impl: DataStoreServerPresenceStore): ServerPresenceStore = impl

    @Provides
    @Singleton
    fun provideServerStateSink(impl: RoomServerStateSink): ServerStateSink = impl

    @Provides
    @Singleton
    fun provideServerStateSource(impl: ServerModeStateSource): ServerStateSource = impl

    @Provides
    @Singleton
    fun provideServerChatSink(impl: RoomServerChatSink): ServerChatSink = impl

    @Provides
    @Singleton
    fun provideServerEventSink(impl: RoomServerEventSink): ServerEventSink = impl

    @Provides
    @Singleton
    fun provideServerEventNotifier(impl: ServerModeNotifier): ServerEventNotifier = impl

    /**
     * 「此刻可用的远程客户端」不是单例：设置页换地址后要立刻换。
     * 下游（同步/发送/事件）都拿这个 lambda，每次调用现向 [ServerModeRepository] 要。
     */
    @Provides
    @Singleton
    fun provideServerRemoteProvider(mode: ServerModeRepository): ServerRemoteProvider =
        ModeServerRemoteProvider(mode)

    @Provides
    @Singleton
    fun provideServerSyncManager(
        source: ServerStateSource,
        sink: ServerStateSink,
        localStore: ServerLocalStore
    ): ServerSyncManager = ServerSyncManager(source, sink, localStore)

    @Provides
    @Singleton
    fun provideServerChatGateway(
        remoteProvider: ServerRemoteProvider,
        localStore: ServerLocalStore,
        sink: ServerChatSink
    ): ServerChatGateway = ServerChatGateway(remoteProvider, localStore, sink)

    @Provides
    @Singleton
    fun provideServerEventPoller(
        remoteProvider: ServerRemoteProvider,
        localStore: ServerLocalStore,
        sink: ServerEventSink,
        notifier: ServerEventNotifier
    ): ServerEventPoller = ServerEventPoller(remoteProvider, localStore, sink, notifier)
}
