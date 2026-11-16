package com.yap.app.data.repo

import android.content.Context
import android.util.Log
import com.yap.app.data.local.PersonaDao
import com.yap.app.data.local.SeedPersonas
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.domain.SleepDefaultsMigration
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonaRepositoryInitializer @Inject constructor(
    private val personaDao: PersonaDao,
    private val settingsRepository: SettingsRepository,
    private val personaRepository: PersonaRepository,
    @ApplicationContext private val context: Context
) {
    suspend fun seedIfNeeded() {
        // 0.12 续 7：先把老数据里「没人配置过的默认睡眠」关掉，再谈播种。
        // 顺序有意义：迁移看的是**已有**人格；播种出来的人本来就是 sleepEnabled=false，
        // 所以放在任何一次 return 之前，老用户升级上来（库里已有人）也会走到。
        migrateSleepDefaults()

        if (personaDao.count() > 0) {
            if (!settingsRepository.hasSeededInitialPersonas()) settingsRepository.markInitialPersonasSeeded()
            personaRepository.ensureBaselineRevisions()
            return
        }
        if (settingsRepository.hasSeededInitialPersonas()) return
        val now = System.currentTimeMillis()
        val seeded = SeedPersonas.build(context, now)
        personaDao.insertAll(seeded)
        val active = settingsRepository.settings.first().activePersonaId
        if (active.isNullOrBlank()) {
            settingsRepository.setActivePersonaId(seeded.first().id)
        }
        settingsRepository.markInitialPersonasSeeded()
        personaRepository.ensureBaselineRevisions()
    }

    /**
     * 一次性把「默认睡眠」关掉（`SleepDefaultsMigration` 有单测，这里只负责读写与日志）。
     *
     * 标记与改动一起提交：先写库、后写标记。中途被杀掉时下次启动会再跑一遍，
     * 条件对已经改过的人不再成立（`sleepEnabled` 已经是 0），所以重跑是幂等的。
     * 日志只说人数，不含任何用户内容。
     */
    private suspend fun migrateSleepDefaults() {
        val plan = runCatching {
            SleepDefaultsMigration.plan(
                alreadyMigrated = settingsRepository.hasMigratedSleepDefaults(),
                personas = personaDao.getAll()
            )
        }.getOrElse { error ->
            // 迁移失败不该挡住启动（更不该挡住播种）：记一笔，下次启动再试。
            Log.w(TAG, "sleep defaults migration skipped: ${error.javaClass.simpleName}")
            return
        } ?: return
        val changed = runCatching {
            if (plan.isEmpty()) 0 else personaDao.disableSleepFor(plan)
        }.getOrElse { error ->
            Log.w(TAG, "sleep defaults migration failed: ${error.javaClass.simpleName}")
            return
        }
        runCatching { settingsRepository.markSleepDefaultsMigrated() }
        Log.d(
            TAG,
            "sleepDefaultsMigratedV1: candidates=${plan.size} changed=$changed " +
                "(default sleep turned off; custom schedules and edited personas untouched)"
        )
    }

    private companion object {
        const val TAG = "PersonaInitializer"
    }
}
