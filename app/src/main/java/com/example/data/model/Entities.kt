package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String, // e.g. "openrouter", "openai", "anthropic", "google", "ollama", "custom"
    val name: String,
    val apiKey: String = "",
    val baseUrl: String = "",
    val isEnabled: Boolean = true,
    val isCustom: Boolean = false,
    val lastTestedAt: Long = 0L,
    val statusMessage: String = "Ready"
)

@Entity(tableName = "models")
data class ModelConfigEntity(
    @PrimaryKey val id: String, // e.g. "anthropic/claude-3-7-sonnet"
    val providerId: String,
    val displayName: String,
    val modelIdentifier: String, // wire name passed to API e.g. "anthropic/claude-3.7-sonnet" or "gpt-4o"
    val tier: ModelTier,
    val capabilities: String, // comma separated: "Coding,Reasoning,Research,Chat"
    val contextWindow: Int = 128000,
    val costPer1MInput: Double = 3.0,
    val costPer1MOutput: Double = 15.0,
    val isDefaultCoding: Boolean = false,
    val isDefaultReasoning: Boolean = false,
    val isDefaultResearch: Boolean = false,
    val isDefaultFast: Boolean = false,
    val isDefaultChat: Boolean = false,
    val isDefaultCreative: Boolean = false,
    val isEnabled: Boolean = true
)

@Entity(tableName = "routing_rules")
data class RoutingRuleEntity(
    @PrimaryKey val taskType: TaskType,
    val primaryModelId: String,
    val fallbackModelId: String = "",
    val minComplexityThreshold: Int = 5, // 1 to 10
    val preferredQuality: QualityPreference = QualityPreference.AUTO,
    val customNotes: String = ""
)

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val totalTokens: Int = 0,
    val totalCostUsd: Double = 0.0
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String, // "user", "assistant", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val routedModelId: String = "",
    val routedModelName: String = "",
    val routedProviderId: String = "",
    val taskTypeDetected: TaskType? = null,
    val routingReason: String = "",
    val tokensPrompt: Int = 0,
    val tokensCompletion: Int = 0,
    val latencyMs: Long = 0L,
    val costUsd: Double = 0.0,
    val isError: Boolean = false
)

@Entity(tableName = "usage_logs")
data class UsageLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val modelId: String,
    val modelName: String,
    val providerId: String,
    val taskType: TaskType,
    val promptTokens: Int,
    val completionTokens: Int,
    val costUsd: Double,
    val latencyMs: Long,
    val wasAutoRouted: Boolean = true,
    val promptSnippet: String = ""
)

@Entity(tableName = "change_logs")
data class ChangeLogEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val timestamp: Long = System.currentTimeMillis(),
    val category: ChangeLogCategory,
    val title: String,
    val description: String,
    val versionTag: String = "v1.0.0",
    val author: String = "System"
)
