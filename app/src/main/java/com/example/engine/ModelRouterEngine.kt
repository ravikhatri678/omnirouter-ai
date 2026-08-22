package com.example.engine

import com.example.data.model.ModelConfigEntity
import com.example.data.model.ModelTier
import com.example.data.model.QualityPreference
import com.example.data.model.RoutingRuleEntity
import com.example.data.model.TaskType

data class RoutingDecision(
    val taskType: TaskType,
    val complexityScore: Int, // 1 to 10
    val selectedModel: ModelConfigEntity,
    val reasoningText: String,
    val matchedMarkers: List<String>,
    val wasOverridden: Boolean = false
)

object ModelRouterEngine {

    private val codingKeywords = setOf(
        "code", "coding", "program", "programming", "python", "kotlin", "java", "javascript",
        "typescript", "c++", "rust", "golang", "swift", "sql", "html", "css", "api", "bug",
        "fix", "error", "exception", "function", "class", "method", "refactor", "git", "regex",
        "json", "compiler", "algorithm", "data structure", "async", "coroutine", "database",
        "react", "compose", "gradle", "dependency", "sdk", "backend", "frontend"
    )

    private val codingCodeSymbols = listOf(
        "{", "}", "->", "=>", "fun ", "def ", "class ", "import ", "val ", "var ",
        "const ", "let ", "SELECT ", "public static", "void ", "const ", "return "
    )

    private val reasoningKeywords = setOf(
        "calculate", "prove", "proof", "deduce", "deduction", "step-by-step", "logic",
        "logical", "riddle", "puzzle", "math", "mathematics", "formula", "equation",
        "theorem", "probability", "derive", "paradox", "solve", "hypothesis", "premise",
        "algebra", "geometry", "calculus", "multi-step"
    )

    private val researchKeywords = setOf(
        "research", "literature", "analyze", "analysis", "paper", "summary", "summarize",
        "comprehensive", "compare and contrast", "history", "deep dive", "overview",
        "pros and cons", "study", "evidence", "findings", "implications", "citation",
        "explain in detail", "breakdown"
    )

    private val fastQueryStarters = listOf(
        "what is", "who was", "who is", "define", "definition of", "meaning of",
        "translate", "synonym", "antonym", "convert", "capital of", "when was",
        "where is", "spell", "how tall", "how far", "population of"
    )

    private val creativeKeywords = setOf(
        "story", "poem", "poetry", "essay", "narrative", "dialogue", "script",
        "song", "rhyme", "metaphor", "creative", "fiction", "character", "plot",
        "prose", "lyrics", "marketing copy", "slogan"
    )

    fun analyzePrompt(prompt: String): Pair<TaskType, List<String>> {
        val lower = prompt.lowercase().trim()
        val detectedMarkers = mutableListOf<String>()

        // 1. Check Coding
        var codingMatches = 0
        codingCodeSymbols.forEach { symbol ->
            if (prompt.contains(symbol)) {
                codingMatches += 2
                detectedMarkers.add("Code syntax: '$symbol'")
            }
        }
        val words = lower.split(Regex("[^a-zA-Z0-9_+#]")).filter { it.isNotBlank() }
        words.forEach { word ->
            if (codingKeywords.contains(word)) {
                codingMatches++
                detectedMarkers.add("Tech keyword: '$word'")
            }
        }
        if (codingMatches >= 2 || prompt.contains("```") || prompt.contains("fun ") || prompt.contains("def ")) {
            return Pair(TaskType.CODING, detectedMarkers)
        }

        // 2. Check Reasoning & Math
        var reasoningMatches = 0
        words.forEach { word ->
            if (reasoningKeywords.contains(word)) {
                reasoningMatches++
                detectedMarkers.add("Reasoning trigger: '$word'")
            }
        }
        if (reasoningMatches >= 2 || lower.contains("step by step") || lower.contains("solve for")) {
            return Pair(TaskType.REASONING, detectedMarkers)
        }

        // 3. Check Fast Query
        fastQueryStarters.forEach { starter ->
            if (lower.startsWith(starter) && lower.length < 80) {
                detectedMarkers.add("Fast lookup pattern: '$starter'")
                return Pair(TaskType.FAST_QUERY, detectedMarkers)
            }
        }

        // 4. Check Research & In-depth Analysis
        var researchMatches = 0
        words.forEach { word ->
            if (researchKeywords.contains(word)) {
                researchMatches++
                detectedMarkers.add("Research term: '$word'")
            }
        }
        if (researchMatches >= 2 || (lower.contains("compare") && lower.contains("and"))) {
            return Pair(TaskType.RESEARCH, detectedMarkers)
        }

        // 5. Check Creative Writing
        var creativeMatches = 0
        words.forEach { word ->
            if (creativeKeywords.contains(word)) {
                creativeMatches++
                detectedMarkers.add("Creative term: '$word'")
            }
        }
        if (creativeMatches >= 1 && (lower.contains("write a") || lower.contains("compose"))) {
            return Pair(TaskType.CREATIVE_WRITING, detectedMarkers)
        }

        // 6. Default to Casual Chat / General Dialogue
        detectedMarkers.add("General conversational dialogue")
        return Pair(TaskType.CASUAL_CHAT, detectedMarkers)
    }

    fun computeComplexityScore(prompt: String, taskType: TaskType, markersCount: Int): Int {
        var score = 3
        val charCount = prompt.trim().length

        when {
            charCount > 600 -> score += 3
            charCount > 250 -> score += 2
            charCount > 80 -> score += 1
            charCount < 40 -> score -= 1
        }

        when (taskType) {
            TaskType.CODING -> score += 3
            TaskType.REASONING -> score += 3
            TaskType.RESEARCH -> score += 2
            TaskType.CREATIVE_WRITING -> score += 1
            TaskType.FAST_QUERY -> score = (score - 2).coerceAtLeast(1)
            TaskType.CASUAL_CHAT -> score = (score - 1).coerceAtLeast(1)
        }

        if (markersCount >= 4) score += 1

        return score.coerceIn(1, 10)
    }

    fun resolveRoute(
        prompt: String,
        availableModels: List<ModelConfigEntity>,
        routingRules: List<RoutingRuleEntity>,
        globalQualityPreference: QualityPreference = QualityPreference.AUTO,
        manualOverrideModel: ModelConfigEntity? = null
    ): RoutingDecision {
        val (detectedTask, markers) = analyzePrompt(prompt)
        val complexity = computeComplexityScore(prompt, detectedTask, markers.size)

        // Handle manual override
        if (manualOverrideModel != null) {
            return RoutingDecision(
                taskType = detectedTask,
                complexityScore = complexity,
                selectedModel = manualOverrideModel,
                reasoningText = "User manually overrode automatic routing to select ${manualOverrideModel.displayName}.",
                matchedMarkers = markers,
                wasOverridden = true
            )
        }

        val rule = routingRules.find { it.taskType == detectedTask }
        val effectiveQuality = if (globalQualityPreference != QualityPreference.AUTO) {
            globalQualityPreference
        } else {
            rule?.preferredQuality ?: QualityPreference.AUTO
        }

        // Filter active models
        val activeModels = availableModels.filter { it.isEnabled }
        if (activeModels.isEmpty()) {
            val fallback = availableModels.firstOrNull() ?: ModelConfigEntity(
                id = "google/gemini-2.5-flash",
                providerId = "google",
                displayName = "Gemini 2.5 Flash",
                modelIdentifier = "gemini-2.5-flash",
                tier = ModelTier.FAST_LIGHTWEIGHT,
                capabilities = "Fast Search",
                isEnabled = true
            )
            return RoutingDecision(
                taskType = detectedTask,
                complexityScore = complexity,
                selectedModel = fallback,
                reasoningText = "Default fallback applied as no custom models were active.",
                matchedMarkers = markers
            )
        }

        // Select model based on effective quality & task
        val selectedModel = when (effectiveQuality) {
            QualityPreference.LOCAL_ONLY -> {
                activeModels.find { it.tier == ModelTier.LOCAL_OFFLINE }
                    ?: activeModels.find { it.providerId == "ollama" }
                    ?: activeModels.first()
            }
            QualityPreference.COST_SAVER -> {
                activeModels.find { it.tier == ModelTier.FAST_LIGHTWEIGHT }
                    ?: activeModels.minByOrNull { it.costPer1MInput }
                    ?: activeModels.first()
            }
            QualityPreference.HIGH_QUALITY -> {
                // Look for rule primary model or top flagship
                rule?.primaryModelId?.let { id -> activeModels.find { it.id == id } }
                    ?: activeModels.find { it.tier == ModelTier.FLAGSHIP_FRONTIER }
                    ?: activeModels.first()
            }
            QualityPreference.BALANCED -> {
                rule?.primaryModelId?.let { id -> activeModels.find { it.id == id } }
                    ?: activeModels.find { it.tier == ModelTier.BALANCED }
                    ?: activeModels.find { it.tier == ModelTier.FLAGSHIP_FRONTIER }
                    ?: activeModels.first()
            }
            QualityPreference.AUTO -> {
                if (complexity >= 6 || detectedTask == TaskType.CODING || detectedTask == TaskType.REASONING) {
                    // Route to High-Quality model (e.g. Claude 3.7 / GPT-5 / DeepSeek R1)
                    rule?.primaryModelId?.let { id -> activeModels.find { it.id == id } }
                        ?: activeModels.find { it.tier == ModelTier.FLAGSHIP_FRONTIER }
                        ?: activeModels.first()
                } else if (complexity <= 3 || detectedTask == TaskType.FAST_QUERY) {
                    // Route to Fast & Low Cost model (e.g. Gemini 2.5 Flash)
                    activeModels.find { it.isDefaultFast }
                        ?: activeModels.find { it.tier == ModelTier.FAST_LIGHTWEIGHT }
                        ?: activeModels.first()
                } else {
                    // Balanced / Default rule
                    rule?.primaryModelId?.let { id -> activeModels.find { it.id == id } }
                        ?: activeModels.first()
                }
            }
        }

        val reasoningExplanation = buildString {
            append("Classified as [${detectedTask.displayName}] with Complexity Score $complexity/10. ")
            if (markers.isNotEmpty()) {
                append("Markers: ${markers.take(3).joinToString(", ")}. ")
            }
            when {
                detectedTask == TaskType.CODING -> {
                    append("Prioritized High-Quality frontier model (${selectedModel.displayName}) for strict syntax precision, architectural depth, and zero-hallucination code generation.")
                }
                detectedTask == TaskType.REASONING -> {
                    append("Routed to Deep Reasoning model (${selectedModel.displayName}) to provide multi-step deductive chain-of-thought analysis.")
                }
                detectedTask == TaskType.RESEARCH -> {
                    append("Allocated high-context model (${selectedModel.displayName}) for comprehensive source synthesis and deep document understanding.")
                }
                detectedTask == TaskType.FAST_QUERY -> {
                    append("Routed to Ultra-Fast Low-Cost tier (${selectedModel.displayName}) for sub-second latency and zero cost overhead.")
                }
                else -> {
                    append("Routed to ${selectedModel.displayName} based on ${effectiveQuality.displayName} optimization policy.")
                }
            }
        }

        return RoutingDecision(
            taskType = detectedTask,
            complexityScore = complexity,
            selectedModel = selectedModel,
            reasoningText = reasoningExplanation,
            matchedMarkers = markers
        )
    }
}
