package com.yap.app.data.local

import androidx.room.TypeConverter

class Converters {
    @TypeConverter fun providerFrom(value: String): ProviderType = ProviderType.valueOf(value)
    @TypeConverter fun providerTo(value: ProviderType): String = value.name

    @TypeConverter fun roleFrom(value: String): MessageRole = MessageRole.valueOf(value)
    @TypeConverter fun roleTo(value: MessageRole): String = value.name

    @TypeConverter fun statusFrom(value: String): MessageStatus = MessageStatus.valueOf(value)
    @TypeConverter fun statusTo(value: MessageStatus): String = value.name

    @TypeConverter fun memoryFrom(value: String): MemoryCategory = MemoryCategory.valueOf(value)
    @TypeConverter fun memoryTo(value: MemoryCategory): String = value.name

    @TypeConverter fun intentKindFrom(value: String): AgentIntentKind = AgentIntentKind.valueOf(value)
    @TypeConverter fun intentKindTo(value: AgentIntentKind): String = value.name

    @TypeConverter fun intentStatusFrom(value: String): AgentIntentStatus = AgentIntentStatus.valueOf(value)
    @TypeConverter fun intentStatusTo(value: AgentIntentStatus): String = value.name
}
