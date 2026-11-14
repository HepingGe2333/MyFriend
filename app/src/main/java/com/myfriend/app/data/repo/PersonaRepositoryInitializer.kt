package com.myfriend.app.data.repo

import com.myfriend.app.data.local.PersonaDao
import com.myfriend.app.data.local.SeedPersonas
import com.myfriend.app.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PersonaRepositoryInitializer @Inject constructor(
    private val personaDao: PersonaDao,
    private val settingsRepository: SettingsRepository,
    private val personaRepository: PersonaRepository
) {
    suspend fun seedIfNeeded() {
        if (personaDao.count() > 0) {
            if (!settingsRepository.hasSeededInitialPersonas()) settingsRepository.markInitialPersonasSeeded()
            personaRepository.ensureBaselineRevisions()
            return
        }
        if (settingsRepository.hasSeededInitialPersonas()) return
        val now = System.currentTimeMillis()
        val seeded = SeedPersonas.build(now)
        personaDao.insertAll(seeded)
        val active = settingsRepository.settings.first().activePersonaId
        if (active.isNullOrBlank()) {
            settingsRepository.setActivePersonaId(seeded.first().id)
        }
        settingsRepository.markInitialPersonasSeeded()
        personaRepository.ensureBaselineRevisions()
    }
}
