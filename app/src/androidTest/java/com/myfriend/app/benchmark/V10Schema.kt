package com.myfriend.app.benchmark

/**
 * v10 的表结构（逐字来自 `app/schemas/com.myfriend.app.data.local.MyFriendDatabase/10.json`）。
 *
 * 基准测试要造一个「用户手机上 v10 那一版」的库，才能让 `MIGRATION_10_11` 在**真实形状**的
 * 老库上跑一次。这里不手抄 DDL：文件由 `schemas/10.json` 的 `createSql` 机械生成，
 * 生成脚本见 `docs/performance.md` 的「可重复命令」一节。
 *
 * - `version` = 10，`identityHash` = `e11aeb13e8963ee8f62ff6fecacd96c8`；
 * - 共 53 条语句（17 张表 + 36 个索引），顺序与 Room 建库时一致；
 * - `${TABLE_NAME}` 已替换成真实表名。
 */
internal object V10Schema {

    val statements: List<String> = listOf(
        """
        CREATE TABLE IF NOT EXISTS `personas` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `avatarUri` TEXT, `avatarSeed` INTEGER NOT NULL, `prompt` TEXT NOT NULL, `category` TEXT NOT NULL, `version` INTEGER NOT NULL, `defaultApiConfigId` TEXT, `modelOverride` TEXT, `isDefault` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `personaCardJson` TEXT, `quietSleepMinutes` INTEGER, `quietWakeMinutes` INTEGER, `mood` TEXT, `moodIntensity` INTEGER, `moodReason` TEXT, `moodUpdatedAt` INTEGER, `moodNudgedAt` INTEGER, `traits` TEXT NOT NULL DEFAULT '[]', `likes` TEXT NOT NULL DEFAULT '[]', `behaviors` TEXT NOT NULL DEFAULT '[]', `backstory` TEXT, `quirks` TEXT, `proactiveLevel` INTEGER NOT NULL DEFAULT 3, `memoryRetention` INTEGER NOT NULL DEFAULT 3, `extractionEvery` INTEGER NOT NULL DEFAULT 6, `sleepEnabled` INTEGER NOT NULL DEFAULT 1, `wakeThreshold` INTEGER NOT NULL DEFAULT 6, `dndOnSleep` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_personas_isDefault` ON `personas` (`isDefault`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_personas_updatedAt` ON `personas` (`updatedAt`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `persona_revisions` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `version` INTEGER NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `avatarUri` TEXT, `avatarSeed` INTEGER NOT NULL, `prompt` TEXT NOT NULL, `category` TEXT NOT NULL, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS `index_persona_revisions_personaId_version` ON `persona_revisions` (`personaId`, `version`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `persona_presets` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `prompt` TEXT NOT NULL, `category` TEXT NOT NULL, `avatarUri` TEXT, `avatarSeed` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_persona_presets_updatedAt` ON `persona_presets` (`updatedAt`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_persona_presets_category` ON `persona_presets` (`category`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `api_configs` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `provider` TEXT NOT NULL, `baseUrl` TEXT NOT NULL, `apiKeyCipher` TEXT, `model` TEXT NOT NULL, `customHeadersJson` TEXT NOT NULL, `allowInsecureHttp` INTEGER NOT NULL, `temperature` REAL, `topP` REAL, `maxOutputTokens` INTEGER, `frequencyPenalty` REAL, `presencePenalty` REAL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `webToolsEnabled` INTEGER NOT NULL DEFAULT 0, `webToolsBaseUrl` TEXT NOT NULL DEFAULT '', `webToolsApiKeyCipher` TEXT, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_api_configs_updatedAt` ON `api_configs` (`updatedAt`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `chat_sessions` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `apiConfigId` TEXT, `model` TEXT NOT NULL, `title` TEXT NOT NULL, `lastMessagePreview` TEXT, `lastMessageAt` INTEGER, `unreadCount` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastPersonaVersion` INTEGER NOT NULL, `lastMemoryExtractedCount` INTEGER NOT NULL, `historySummary` TEXT, `historySummarizedUpTo` INTEGER, `historySummaryAt` INTEGER, `memoryRetentionOverride` INTEGER, `extractionEveryOverride` INTEGER, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE UNIQUE INDEX IF NOT EXISTS `index_chat_sessions_personaId_unique` ON `chat_sessions` (`personaId`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_chat_sessions_lastMessageAt` ON `chat_sessions` (`lastMessageAt`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_chat_sessions_pinned` ON `chat_sessions` (`pinned`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `personaId` TEXT NOT NULL, `role` TEXT NOT NULL, `content` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `errorMessage` TEXT, `model` TEXT, `edited` INTEGER NOT NULL, `attachmentsJson` TEXT, `toolTraceJson` TEXT, `readAt` INTEGER, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_messages_sessionId_createdAt` ON `messages` (`sessionId`, `createdAt`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_messages_sessionId_role` ON `messages` (`sessionId`, `role`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_messages_createdAt` ON `messages` (`createdAt`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `memories` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `content` TEXT NOT NULL, `category` TEXT NOT NULL, `importance` INTEGER NOT NULL, `pinned` INTEGER NOT NULL, `sourceSessionId` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, `useCount` INTEGER NOT NULL, `drawer` TEXT, `sourceQuote` TEXT, `status` TEXT NOT NULL, `strength` REAL NOT NULL, `archivedAt` INTEGER, `consolidatedAt` INTEGER, `tags` TEXT, `happenedAt` INTEGER, `originalContent` TEXT, `driftCount` INTEGER NOT NULL, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_memories_personaId` ON `memories` (`personaId`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_memories_personaId_importance` ON `memories` (`personaId`, `importance`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_memories_updatedAt` ON `memories` (`updatedAt`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `memory_echoes` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `drawer` TEXT NOT NULL, `content` TEXT NOT NULL, `importance` INTEGER NOT NULL, `sourceQuote` TEXT NOT NULL, `categoryName` TEXT NOT NULL, `confidence` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_memory_echoes_personaId_drawer` ON `memory_echoes` (`personaId`, `drawer`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_memory_echoes_createdAt` ON `memory_echoes` (`createdAt`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `persona_pending_patches` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `sessionId` TEXT, `patchJson` TEXT NOT NULL, `summary` TEXT NOT NULL, `reason` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_persona_pending_patches_personaId` ON `persona_pending_patches` (`personaId`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_persona_pending_patches_createdAt` ON `persona_pending_patches` (`createdAt`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `proactive_rules` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `sessionId` TEXT, `kind` TEXT NOT NULL, `timeOfDayMinutes` INTEGER, `intervalMinutes` INTEGER, `hint` TEXT NOT NULL, `note` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `pending` INTEGER NOT NULL, `source` TEXT NOT NULL, `nextTriggerAt` INTEGER NOT NULL, `lastTriggeredAt` INTEGER, `lastError` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_proactive_rules_personaId` ON `proactive_rules` (`personaId`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_proactive_rules_nextTriggerAt` ON `proactive_rules` (`nextTriggerAt`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_proactive_rules_enabled` ON `proactive_rules` (`enabled`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_intents` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `sessionId` TEXT, `kind` TEXT NOT NULL, `earliestAt` INTEGER NOT NULL, `latestAt` INTEGER NOT NULL, `note` TEXT NOT NULL, `hint` TEXT NOT NULL, `status` TEXT NOT NULL, `ruleId` TEXT, `createdAt` INTEGER NOT NULL, `sentAt` INTEGER, `lastError` TEXT, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_agent_intents_personaId_status` ON `agent_intents` (`personaId`, `status`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_agent_intents_earliestAt` ON `agent_intents` (`earliestAt`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_agent_intents_latestAt` ON `agent_intents` (`latestAt`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `agent_ticks` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `at` INTEGER NOT NULL, `thought` TEXT NOT NULL, `actionJson` TEXT NOT NULL, `outcome` TEXT NOT NULL, `messageId` TEXT, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_agent_ticks_personaId_at` ON `agent_ticks` (`personaId`, `at`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_agent_ticks_at` ON `agent_ticks` (`at`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `memory_consolidations` (`id` TEXT NOT NULL, `personaId` TEXT NOT NULL, `at` INTEGER NOT NULL, `kept` INTEGER NOT NULL, `merged` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `forgotten` INTEGER NOT NULL, `summary` TEXT NOT NULL, `modelName` TEXT, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_memory_consolidations_personaId_at` ON `memory_consolidations` (`personaId`, `at`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_memory_consolidations_at` ON `memory_consolidations` (`at`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `api_usage` (`id` TEXT NOT NULL, `at` INTEGER NOT NULL, `kind` TEXT NOT NULL, `personaId` TEXT, `sessionId` TEXT, `modelName` TEXT, `promptTokens` INTEGER NOT NULL, `completionTokens` INTEGER NOT NULL, `costMicroUsd` INTEGER NOT NULL, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_api_usage_at` ON `api_usage` (`at`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_api_usage_kind_at` ON `api_usage` (`kind`, `at`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_api_usage_personaId_at` ON `api_usage` (`personaId`, `at`)
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_api_usage_sessionId_at` ON `api_usage` (`sessionId`, `at`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `memory_terms` (`memoryId` TEXT NOT NULL, `term` TEXT NOT NULL, `weight` REAL NOT NULL, PRIMARY KEY(`memoryId`, `term`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_memory_terms_term` ON `memory_terms` (`term`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `memory_versions` (`id` TEXT NOT NULL, `memoryId` TEXT NOT NULL, `at` INTEGER NOT NULL, `content` TEXT NOT NULL, `beforeContent` TEXT, `reason` TEXT, `moodLabel` TEXT, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_memory_versions_memoryId_at` ON `memory_versions` (`memoryId`, `at`)
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `memory_vectors` (`memoryId` TEXT NOT NULL, `provider` TEXT NOT NULL, `dim` INTEGER NOT NULL, `vector` BLOB NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`memoryId`, `provider`))
        """.trimIndent(),
        """
        CREATE INDEX IF NOT EXISTS `index_memory_vectors_provider` ON `memory_vectors` (`provider`)
        """.trimIndent(),
    )
}
