package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.model.ChangeLogCategory
import com.example.data.model.ChangeLogEntryEntity
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChatSessionEntity
import com.example.data.model.ModelConfigEntity
import com.example.data.model.ModelTier
import com.example.data.model.ProviderEntity
import com.example.data.model.QualityPreference
import com.example.data.model.RoutingRuleEntity
import com.example.data.model.TaskType
import com.example.data.model.UsageLogEntity
import com.example.data.repository.OmniRepository
import com.example.engine.ChangeLogManager
import com.example.engine.ModelRouterEngine
import com.example.engine.RoutingDecision
import com.example.network.AiApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class PromptIntentPreview(
    val detectedTask: TaskType,
    val complexityScore: Int,
    val recommendedModelName: String,
    val markers: List<String>
)

data class DuelResponseState(
    val isDuelMode: Boolean = false,
    val modelA: ModelConfigEntity? = null,
    val modelB: ModelConfigEntity? = null,
    val responseA: ChatMessageEntity? = null,
    val responseB: ChatMessageEntity? = null,
    val isLoadingA: Boolean = false,
    val isLoadingB: Boolean = false
)

class OmniViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application, viewModelScope)
    private val repository = OmniRepository(
        providerDao = db.providerDao(),
        modelConfigDao = db.modelConfigDao(),
        routingRuleDao = db.routingRuleDao(),
        chatDao = db.chatDao(),
        usageLogDao = db.usageLogDao(),
        changeLogDao = db.changeLogDao()
    )
    private val apiService = AiApiService()

    // Session State
    private val _currentSessionId = MutableStateFlow("session_default")
    val currentSessionId: StateFlow<String> = _currentSessionId.asStateFlow()

    val currentMessages: StateFlow<List<ChatMessageEntity>> = _currentSessionId
        .combine(repository.allSessions) { id, _ -> id }
        .combine(db.chatDao().getMessagesForSessionFlow(_currentSessionId.value)) { _, msgs -> msgs }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Routing Settings
    private val _qualityPreference = MutableStateFlow(QualityPreference.AUTO)
    val qualityPreference: StateFlow<QualityPreference> = _qualityPreference.asStateFlow()

    private val _manualOverrideModel = MutableStateFlow<ModelConfigEntity?>(null)
    val manualOverrideModel: StateFlow<ModelConfigEntity?> = _manualOverrideModel.asStateFlow()

    // Loading & Generation State
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _currentRoutingDecision = MutableStateFlow<RoutingDecision?>(null)
    val currentRoutingDecision: StateFlow<RoutingDecision?> = _currentRoutingDecision.asStateFlow()

    // Live Intent Preview as user types
    private val _promptIntentPreview = MutableStateFlow<PromptIntentPreview?>(null)
    val promptIntentPreview: StateFlow<PromptIntentPreview?> = _promptIntentPreview.asStateFlow()

    // Duel / Multi-Model comparison state
    private val _duelState = MutableStateFlow(DuelResponseState())
    val duelState: StateFlow<DuelResponseState> = _duelState.asStateFlow()

    // Data streams from Repository
    val providers: StateFlow<List<ProviderEntity>> = repository.allProviders
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val models: StateFlow<List<ModelConfigEntity>> = repository.allModels
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val routingRules: StateFlow<List<RoutingRuleEntity>> = repository.allRoutingRules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentUsageLogs: StateFlow<List<UsageLogEntity>> = repository.recentUsageLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalCost: StateFlow<Double?> = repository.totalCost
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    val totalTokens: StateFlow<Int?> = repository.totalTokens
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val totalRequests: StateFlow<Int> = repository.totalRequests
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val changeLogs: StateFlow<List<ChangeLogEntryEntity>> = repository.allChangeLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Provider Testing State
    private val _testingProviderId = MutableStateFlow<String?>(null)
    val testingProviderId: StateFlow<String?> = _testingProviderId.asStateFlow()

    init {
        // Ensure initial database contents are populated
        viewModelScope.launch {
            if (repository.getProvidersList().isEmpty()) {
                AppDatabase.populateInitialData(db)
            }
        }
    }

    fun onPromptTextChanged(text: String) {
        if (text.isBlank() || text.length < 3) {
            _promptIntentPreview.value = null
            return
        }
        val (task, markers) = ModelRouterEngine.analyzePrompt(text)
        val score = ModelRouterEngine.computeComplexityScore(text, task, markers.size)
        val candidateModel = models.value.find {
            when (task) {
                TaskType.CODING -> it.isDefaultCoding
                TaskType.REASONING -> it.isDefaultReasoning
                TaskType.RESEARCH -> it.isDefaultResearch
                TaskType.FAST_QUERY -> it.isDefaultFast
                TaskType.CASUAL_CHAT -> it.isDefaultChat
                TaskType.CREATIVE_WRITING -> it.isDefaultCreative
            }
        } ?: models.value.firstOrNull()

        _promptIntentPreview.value = PromptIntentPreview(
            detectedTask = task,
            complexityScore = score,
            recommendedModelName = candidateModel?.displayName ?: "Auto Router",
            markers = markers
        )
    }

    fun setQualityPreference(pref: QualityPreference) {
        _qualityPreference.value = pref
        viewModelScope.launch {
            repository.logChange(
                category = ChangeLogCategory.ROUTING_RULE_CHANGE,
                title = "Global Quality Preference Set: ${pref.displayName}",
                description = "Updated system-wide routing strategy to ${pref.name} (${pref.description})."
            )
        }
    }

    fun setManualOverrideModel(model: ModelConfigEntity?) {
        _manualOverrideModel.value = model
    }

    fun toggleDuelMode(enabled: Boolean, modelA: ModelConfigEntity? = null, modelB: ModelConfigEntity? = null) {
        _duelState.value = _duelState.value.copy(
            isDuelMode = enabled,
            modelA = modelA ?: models.value.find { it.tier == ModelTier.FLAGSHIP_FRONTIER } ?: models.value.firstOrNull(),
            modelB = modelB ?: models.value.find { it.tier == ModelTier.FAST_LIGHTWEIGHT } ?: models.value.getOrNull(1)
        )
    }

    fun sendPrompt(promptText: String) {
        val trimmed = promptText.trim()
        if (trimmed.isBlank() || _isGenerating.value) return

        val userMessageId = UUID.randomUUID().toString()
        val userMessage = ChatMessageEntity(
            id = userMessageId,
            sessionId = _currentSessionId.value,
            role = "user",
            content = trimmed,
            timestamp = System.currentTimeMillis()
        )

        viewModelScope.launch {
            repository.insertMessage(userMessage)
            _isGenerating.value = true

            val allAvailableModels = repository.getModelsList()
            val allRules = repository.getRoutingRulesList()
            val allProvidersList = repository.getProvidersList()

            // 1. Resolve Intelligent Route
            val routingDecision = ModelRouterEngine.resolveRoute(
                prompt = trimmed,
                availableModels = allAvailableModels,
                routingRules = allRules,
                globalQualityPreference = _qualityPreference.value,
                manualOverrideModel = _manualOverrideModel.value
            )
            _currentRoutingDecision.value = routingDecision

            val selectedModel = routingDecision.selectedModel
            val provider = allProvidersList.find { it.id == selectedModel.providerId }
                ?: ProviderEntity(
                    id = selectedModel.providerId,
                    name = selectedModel.providerId.replaceFirstChar { it.uppercase() },
                    baseUrl = "https://openrouter.ai/api/v1/"
                )

            // 2. Fetch recent conversation history
            val historyMessages = repository.getMessagesForSession(_currentSessionId.value)
                .map { Pair(it.role, it.content) }

            // 3. Execute AI Request
            val result = apiService.executePrompt(
                prompt = trimmed,
                history = historyMessages,
                model = selectedModel,
                provider = provider,
                taskType = routingDecision.taskType
            )

            // 4. Save Assistant Response
            val assistantMsgId = UUID.randomUUID().toString()
            val assistantMessage = ChatMessageEntity(
                id = assistantMsgId,
                sessionId = _currentSessionId.value,
                role = "assistant",
                content = result.content,
                timestamp = System.currentTimeMillis(),
                routedModelId = selectedModel.id,
                routedModelName = selectedModel.displayName,
                routedProviderId = provider.id,
                taskTypeDetected = routingDecision.taskType,
                routingReason = routingDecision.reasoningText,
                tokensPrompt = result.tokensPrompt,
                tokensCompletion = result.tokensCompletion,
                latencyMs = result.latencyMs,
                costUsd = result.costUsd,
                isError = result.errorMessage != null
            )
            repository.insertMessage(assistantMessage)

            // 5. Record Usage Log
            val usageLog = UsageLogEntity(
                modelId = selectedModel.id,
                modelName = selectedModel.displayName,
                providerId = provider.id,
                taskType = routingDecision.taskType,
                promptTokens = result.tokensPrompt,
                completionTokens = result.tokensCompletion,
                costUsd = result.costUsd,
                latencyMs = result.latencyMs,
                wasAutoRouted = !routingDecision.wasOverridden,
                promptSnippet = trimmed.take(80)
            )
            repository.recordUsage(usageLog)

            _isGenerating.value = false
        }
    }

    fun updateProviderCredentials(providerId: String, apiKey: String, baseUrl: String) {
        viewModelScope.launch {
            repository.updateProviderCredentials(providerId, apiKey, baseUrl)
        }
    }

    fun toggleProviderEnabled(providerId: String, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setProviderEnabled(providerId, isEnabled)
        }
    }

    fun testProviderConnection(provider: ProviderEntity) {
        viewModelScope.launch {
            _testingProviderId.value = provider.id
            val (success, message) = apiService.testConnection(provider)
            val status = if (success) "Online: $message" else "Error: $message"
            repository.updateProviderTestStatus(provider.id, status)
            _testingProviderId.value = null
        }
    }

    fun addCustomProvider(name: String, baseUrl: String, apiKey: String) {
        viewModelScope.launch {
            val id = "custom_${System.currentTimeMillis()}"
            val provider = ProviderEntity(
                id = id,
                name = name,
                baseUrl = baseUrl,
                apiKey = apiKey,
                isEnabled = true,
                isCustom = true,
                statusMessage = "Custom provider added"
            )
            repository.insertProvider(provider)
        }
    }

    fun toggleModelEnabled(modelId: String, isEnabled: Boolean) {
        viewModelScope.launch {
            repository.setModelEnabled(modelId, isEnabled)
        }
    }

    fun addCustomModel(
        id: String,
        displayName: String,
        providerId: String,
        modelIdentifier: String,
        tier: ModelTier,
        capabilities: String,
        contextWindow: Int,
        costPer1MInput: Double,
        costPer1MOutput: Double
    ) {
        viewModelScope.launch {
            val model = ModelConfigEntity(
                id = id.ifBlank { "${providerId}/${modelIdentifier.replace('/', '_')}" },
                providerId = providerId,
                displayName = displayName,
                modelIdentifier = modelIdentifier,
                tier = tier,
                capabilities = capabilities,
                contextWindow = contextWindow,
                costPer1MInput = costPer1MInput,
                costPer1MOutput = costPer1MOutput,
                isEnabled = true
            )
            repository.insertModel(model)
        }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            repository.deleteModel(modelId)
        }
    }

    fun updateRoutingRule(rule: RoutingRuleEntity) {
        viewModelScope.launch {
            repository.updateRoutingRule(rule)
        }
    }

    fun clearChat() {
        viewModelScope.launch {
            repository.deleteSession(_currentSessionId.value)
            val newSession = ChatSessionEntity(
                id = "session_${System.currentTimeMillis()}",
                title = "New Chat"
            )
            repository.insertSession(newSession)
            _currentSessionId.value = newSession.id
        }
    }

    fun clearAnalytics() {
        viewModelScope.launch {
            repository.clearUsageLogs()
        }
    }

    fun addCustomChangeLog(title: String, description: String, category: ChangeLogCategory) {
        viewModelScope.launch {
            repository.logChange(
                category = category,
                title = title,
                description = description,
                author = "User"
            )
        }
    }

    fun getExportableMarkdownLog(): String {
        return ChangeLogManager.generateMarkdown(changeLogs.value)
    }
}
