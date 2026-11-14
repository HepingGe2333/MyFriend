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
import com.myfriend.app.data.local.MessageDao
import com.myfriend.app.data.local.AgentIntentDao
import com.myfriend.app.data.local.AgentTickDao
import com.myfriend.app.data.local.MyFriendDatabase
import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.PersonaPendingPatchDao
import com.myfriend.app.data.local.PersonaPresetDao
import com.myfriend.app.data.local.PersonaRevisionDao
import com.myfriend.app.data.local.ProactiveRuleDao
import com.myfriend.app.domain.AppMemoryBudget
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
                MyFriendDatabase.MIGRATION_9_10
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
    @Provides fun memoryDao(db: MyFriendDatabase): MemoryDao = db.memoryDao()
    @Provides fun proactiveRuleDao(db: MyFriendDatabase): ProactiveRuleDao = db.proactiveRuleDao()
    @Provides fun memoryEchoDao(db: MyFriendDatabase): MemoryEchoDao = db.memoryEchoDao()
    @Provides fun memoryConsolidationDao(db: MyFriendDatabase): MemoryConsolidationDao = db.memoryConsolidationDao()
    @Provides fun apiUsageDao(db: MyFriendDatabase): ApiUsageDao = db.apiUsageDao()
    @Provides fun memoryTermDao(db: MyFriendDatabase): MemoryTermDao = db.memoryTermDao()
    @Provides fun memoryVersionDao(db: MyFriendDatabase): MemoryVersionDao = db.memoryVersionDao()
    @Provides fun memoryVectorDao(db: MyFriendDatabase): MemoryVectorDao = db.memoryVectorDao()
    @Provides fun personaPendingPatchDao(db: MyFriendDatabase): PersonaPendingPatchDao = db.personaPendingPatchDao()

    @Provides fun agentIntentDao(db: MyFriendDatabase): AgentIntentDao = db.agentIntentDao()

    @Provides fun agentTickDao(db: MyFriendDatabase): AgentTickDao = db.agentTickDao()

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
}
