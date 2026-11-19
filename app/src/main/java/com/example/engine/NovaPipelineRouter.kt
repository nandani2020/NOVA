package com.example.engine

import com.example.data.DeviceAccountHelper
import com.example.data.DeviceActionExecutor
import com.example.data.LearnedProcessRecord
import com.example.data.LocalIndexDocument
import com.example.data.OrbVisualMode
import com.example.data.PipelineStepPill
import com.example.data.RoutingTier
import com.example.data.SafetyTier
import com.example.data.StepStatus
import com.example.data.TierRedConfirmation
import com.example.data.ToolCallRequest
import com.example.data.TurnExecutionOutcome
import com.example.data.UiNodeSnapshot
import com.example.processA.EmbeddingGemmaRuntime
import com.example.processA.LlmModelMissingException
import com.example.processA.LlmProcessBridge
import com.example.processA.NovaAccessibilityService
import java.util.UUID

/**
 * Coordinates the 4-Tier Request & Autonomy Pipeline:
 * Tier 0 (Input Normalizer) -> Follow-Up Choice/Process Resolver -> Tier 1 (Regex Fast-Path <5ms) ->
 * Tier 1.5 (EmbeddingGemma 2 Semantic Lookup) -> Tier 2 (Process B :llm Gemma 4 E2B + Agentic Memory/Personality) ->
 * Tier 3 (Closed-loop Tool & Autonomous UI Loop + Post-Turn Agentic Habit Learning).
 */
class NovaPipelineRouter(
    private val llmBridge: LlmProcessBridge,
    private val executor: DeviceActionExecutor,
    private val embeddingRuntime: EmbeddingGemmaRuntime? = null,
    private val accountHelper: DeviceAccountHelper? = null,
    private val agenticMemoryEngine: AgenticMemoryEngine? = null,
    private val onAccountRecorded: (String) -> Unit = {}
) {

    private data class ConversationTurn(
        val userQuery: String,
        val assistantResponse: String,
        val topic: String?
    )

    @Volatile
    private var pendingChoices: List<LocalIndexDocument> = emptyList()

    @Volatile
    private var pendingSuggestedProcess: LearnedProcessRecord? = null

    @Volatile
    private var lastTopicSubject: String? = null

    @Volatile
    private var lastAssistantResponse: String? = null

    private val recentTurns = ArrayDeque<ConversationTurn>()

    fun getPendingChoices(): List<LocalIndexDocument> = pendingChoices
    internal fun getLastTopicForTest(): String? = lastTopicSubject

    private fun recordTurnContext(
        userQuery: String,
        responseText: String,
        topicHint: String? = null
    ) {
        val extracted = topicHint?.takeIf { it.isNotBlank() }
            ?: extractTopicFromQueryOrResponse(userQuery, responseText, lastTopicSubject)
        synchronized(recentTurns) {
            if (recentTurns.size >= MAX_CONVERSATION_TURNS) {
                recentTurns.removeFirst()
            }
            recentTurns.addLast(ConversationTurn(userQuery, responseText.take(280), extracted))
        }
        if (!extracted.isNullOrBlank()) {
            lastTopicSubject = extracted
        }
        lastAssistantResponse = responseText
    }

    private fun buildRecentConversationContext(): String? = synchronized(recentTurns) {
        if (recentTurns.isEmpty() && lastTopicSubject.isNullOrBlank()) return@synchronized null
        buildString {
            lastTopicSubject?.takeIf { it.isNotBlank() }?.let {
                appendLine("Current conversation topic: $it")
            }
            if (recentTurns.isNotEmpty()) {
                appendLine("Recent conversation turns:")
                recentTurns.forEach { turn ->
                    appendLine("User: ${turn.userQuery}")
                    appendLine("Nova: ${turn.assistantResponse}")
                }
            }
        }.trim()
    }

    suspend fun routeAndExecute(
        rawQuery: String,
        thinkingAllowed: Boolean = true,
        onStepUpdate: (List<PipelineStepPill>, OrbVisualMode, String) -> Unit
    ): TurnExecutionOutcome {
        val totalStart = System.currentTimeMillis()
        val steps = mutableListOf<PipelineStepPill>()

        // TIER 0: Normalize contractions, strip self-corrections, split compound commands
        val normalized = InputNormalizer.normalize(rawQuery)
        steps.add(
            PipelineStepPill(
                index = 1,
                label = if (normalized.strippedCorrection) "Cleaned request" else "Understood request",
                status = StepStatus.DONE,
                latencyMs = 1L
            )
        )
        onStepUpdate(steps.toList(), OrbVisualMode.LISTENING, "LISTENING...")

        if (normalized.isWakeWordOnly) {
            val totalMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
            val reply = "I'm listening. Tell me what you'd like to do, like playing a song or turning on Bluetooth."
            onStepUpdate(steps.toList(), OrbVisualMode.LISTENING, "LISTENING FOR COMMAND...")
            return TurnExecutionOutcome(
                normalizedQuery = "",
                routingTier = RoutingTier.TIER_1_REGEX,
                selectedSkills = listOf("app-knowledge-controller"),
                latencyMs = totalMs,
                thinkingEnabled = false,
                steps = steps.toList(),
                responseText = reply,
                orbMode = OrbVisualMode.LISTENING
            )
        }

        // Follow-up disambiguation: if previous turn offered choices (single confirmation or multi-choice)
        if (pendingChoices.isNotEmpty()) {
            val lowerChoiceReply = normalized.cleanedFull.trim().lowercase()
            if (pendingChoices.all { it.category == "messages" } &&
                lowerChoiceReply in setOf("mark all read", "mark them read", "mark all as read", "mark as read", "mark read")
            ) {
                pendingChoices = emptyList()
                val report = executor.execute(
                    ToolCallRequest(
                        toolName = "read_recent_messages",
                        args = mapOf("action" to "mark_all_read"),
                        safetyTier = SafetyTier.GREEN
                    )
                )
                steps.add(
                    PipelineStepPill(
                        index = steps.size + 1,
                        label = "Marked notifications read",
                        status = if (report.success) StepStatus.DONE else StepStatus.BLOCKED,
                        latencyMs = 2L
                    )
                )
                val formattedSummary = formatTier1Responses(listOf(report.summary))
                recordTurnContext(rawQuery, formattedSummary)
                val totalMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "DONE")
                return TurnExecutionOutcome(
                    normalizedQuery = normalized.cleanedFull,
                    routingTier = RoutingTier.TIER_1_REGEX,
                    selectedSkills = listOf("notification-listener-agent"),
                    latencyMs = totalMs,
                    thinkingEnabled = false,
                    steps = steps.toList(),
                    responseText = formattedSummary,
                    orbMode = OrbVisualMode.FAST_PATH_FLASH
                )
            }

            if (lowerChoiceReply in setOf(
                    "no", "nope", "none", "neither", "no thanks", "not now",
                    "never mind", "nevermind", "cancel", "skip", "nothing", "dismiss"
                )
            ) {
                pendingChoices = emptyList()
                val reply = "Okay, let me know if you need anything else."
                recordTurnContext(rawQuery, reply)
                val totalMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "DONE")
                return TurnExecutionOutcome(
                    normalizedQuery = normalized.cleanedFull,
                    routingTier = RoutingTier.TIER_1_REGEX,
                    selectedSkills = emptyList(),
                    latencyMs = totalMs,
                    thinkingEnabled = false,
                    steps = steps.toList(),
                    responseText = reply,
                    orbMode = OrbVisualMode.FAST_PATH_FLASH
                )
            }

            val matchedChoice = matchPendingChoice(normalized.cleanedFull, pendingChoices)
            if (matchedChoice != null) {
                pendingChoices = emptyList()
                val report = executeChoiceSelection(matchedChoice)
                steps.add(
                    PipelineStepPill(
                        index = steps.size + 1,
                        label = "Selected ${matchedChoice.title.removePrefix("Memory: ")}",
                        status = if (report.success) StepStatus.DONE else StepStatus.BLOCKED,
                        latencyMs = 2L
                    )
                )
                val formattedSummary = formatTier1Responses(listOf(report.summary))
                recordTurnContext(
                    rawQuery,
                    formattedSummary,
                    topicHint = matchedChoice.title.removePrefix("Memory: ").takeIf { it.isNotBlank() }
                )
                val totalMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "DONE")
                return TurnExecutionOutcome(
                    normalizedQuery = normalized.cleanedFull,
                    routingTier = RoutingTier.TIER_1_REGEX,
                    selectedSkills = listOf("app-knowledge-controller"),
                    latencyMs = totalMs,
                    thinkingEnabled = false,
                    steps = steps.toList(),
                    responseText = formattedSummary,
                    orbMode = OrbVisualMode.FAST_PATH_FLASH
                )
            } else {
                // User moved on to a fresh command
                pendingChoices = emptyList()
            }
        }

        // Follow-up confirmation for a learned workflow offered during a briefing
        val suggestedProc = pendingSuggestedProcess
        if (suggestedProc != null) {
            pendingSuggestedProcess = null
            val lowerReply = normalized.cleanedFull.trim().lowercase()
            val accepted = lowerReply in setOf(
                "yes", "yeah", "yep", "sure", "ok", "okay", "please", "go ahead",
                "run it", "do it", "run that", "do that", "start it", "yes please",
                "yes run it", "yes do it", "run the process", "kick it off"
            )
            val declined = lowerReply in setOf(
                "no", "nope", "not now", "skip", "cancel", "no thanks", "maybe later", "later", "don't"
            )
            if (accepted) {
                return executeLearnedProcessSteps(
                    process = suggestedProc,
                    normalizedQuery = normalized.cleanedFull,
                    steps = steps,
                    totalStart = totalStart,
                    onStepUpdate = onStepUpdate
                )
            } else if (declined) {
                val totalMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                val declineMsg = "Okay, skipping '${suggestedProc.name}' for now."
                recordTurnContext(rawQuery, declineMsg)
                onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "DONE")
                return TurnExecutionOutcome(
                    normalizedQuery = normalized.cleanedFull,
                    routingTier = RoutingTier.TIER_1_REGEX,
                    selectedSkills = listOf("personal-memory-vault"),
                    latencyMs = totalMs,
                    thinkingEnabled = false,
                    steps = steps.toList(),
                    responseText = declineMsg,
                    orbMode = OrbVisualMode.FAST_PATH_FLASH
                )
            }
        }

        // Multi-turn Follow-Up Resolution (e.g., "tell me more", "who sang it?", "how tall is it?", "play that")
        val topicSubject = lastTopicSubject
        if (topicSubject != null && isElaborationFollowUp(normalized.cleanedFull)) {
            val online = WebKnowledgeResolver.isInternetAvailable(executor.appContext)
            if (!online && !llmBridge.state.value.modelPresent) {
                val elapsed = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                val offlineMsg = "I'd love to share more about $topicSubject, but there's no internet connection right now to check further details."
                onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "OFFLINE")
                return TurnExecutionOutcome(
                    normalizedQuery = normalized.cleanedFull,
                    routingTier = RoutingTier.TIER_1_5_SEMANTIC,
                    selectedSkills = listOf("general-knowledge-qa"),
                    latencyMs = elapsed,
                    thinkingEnabled = false,
                    steps = steps.toList(),
                    responseText = offlineMsg,
                    orbMode = OrbVisualMode.FAST_PATH_FLASH
                )
            }
            if (online) {
                val deeperAnswer = WebKnowledgeResolver.fetchCleanAnswer(topicSubject, detailed = true)
                if (!deeperAnswer.isNullOrBlank()) {
                    val elapsed = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                    steps.add(
                        PipelineStepPill(
                            index = steps.size + 1,
                            label = "Expanded answer",
                            status = StepStatus.DONE,
                            latencyMs = elapsed
                        )
                    )
                    recordTurnContext(rawQuery, deeperAnswer, topicSubject)
                    onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "DONE")
                    return TurnExecutionOutcome(
                        normalizedQuery = normalized.cleanedFull,
                        routingTier = RoutingTier.TIER_1_5_SEMANTIC,
                        selectedSkills = listOf("general-knowledge-qa"),
                        latencyMs = elapsed,
                        thinkingEnabled = false,
                        steps = steps.toList(),
                        responseText = deeperAnswer,
                        orbMode = OrbVisualMode.FAST_PATH_FLASH
                    )
                }
            }
        }

        val installedAppsForFollowUp = executor.discoverInstalledApps()
        val rewrittenQuery = if (topicSubject != null &&
            RegexFastPathEngine.match(normalized.cleanedFull, installedAppsForFollowUp) == null
        ) {
            rewriteFollowUpWithTopic(normalized.cleanedFull, topicSubject)
        } else {
            null
        }
        val effectiveNormalized = if (rewrittenQuery != null) {
            InputNormalizer.normalize(rewrittenQuery)
        } else {
            normalized
        }

        // TIER 1A: Global Multi-Stage Action Planner (Finalizes Intent + UI Control action list before running)
        val multiStagePlan = if (!effectiveNormalized.hasConditionalLogic) {
            MultiStageActionPlanner.plan(
                normalized = effectiveNormalized,
                installedApps = installedAppsForFollowUp,
                hasLaunchIntent = { pkg -> executor.hasLaunchIntent(pkg) }
            )
        } else null

        if (multiStagePlan != null) {
            val baseOffset = steps.size
            multiStagePlan.stages.forEachIndexed { idx, stage ->
                steps.add(
                    PipelineStepPill(
                        index = baseOffset + idx + 1,
                        label = stage.title,
                        status = StepStatus.PENDING
                    )
                )
            }
            onStepUpdate(
                steps.toList(),
                OrbVisualMode.UI_NAVIGATING,
                "PLAN FINALIZED • ${multiStagePlan.stages.size} STAGES"
            )

            val stageReports = mutableListOf<String>()
            val executedStages = mutableListOf<ToolCallRequest>()
            var anyStageSucceeded = false
            var pendingRedFromPlan: TierRedConfirmation? = null
            var llmLoopHandoffNodes: List<UiNodeSnapshot> = emptyList()

            for ((idx, stage) in multiStagePlan.stages.withIndex()) {
                val pillIdx = baseOffset + idx
                if (stage.toolCall.safetyTier == SafetyTier.RED) {
                    val targetLabel = stage.toolCall.args["target_label"]
                        ?: stage.toolCall.args["goal"]
                        ?: "Confirm Action"
                    val appLabel = stage.targetAppName ?: stage.toolCall.args["app_name"] ?: "Current Screen"
                    val liveSvc = NovaAccessibilityService.instance
                    val liveNodes = liveSvc?.scanActiveScreenNodes().orEmpty()
                    val liveShot = liveSvc?.captureScreenshotJpeg()
                    pendingRedFromPlan = TierRedConfirmation(
                        id = UUID.randomUUID().toString().take(8),
                        title = "Confirm Sensitive Action",
                        summary = "Just to confirm: should I perform '$targetLabel' in $appLabel?",
                        targetApp = appLabel,
                        targetNodeId = "n01",
                        riskTag = "CONFIRMATION REQUIRED",
                        pipScreenNodes = liveNodes,
                        pendingToolCall = stage.toolCall,
                        pipScreenshotBytes = liveShot
                    )
                    steps[pillIdx] = steps[pillIdx].copy(status = StepStatus.BLOCKED)
                    for (remIdx in (idx + 1) until multiStagePlan.stages.size) {
                        val remPill = baseOffset + remIdx
                        steps[remPill] = steps[remPill].copy(status = StepStatus.BLOCKED)
                    }
                    break
                }

                steps[pillIdx] = steps[pillIdx].copy(status = StepStatus.ACTIVE)
                onStepUpdate(
                    steps.toList(),
                    OrbVisualMode.UI_NAVIGATING,
                    "STEP ${idx + 1}/${multiStagePlan.stages.size}: ${stage.kind.badge.uppercase()}"
                )

                val stageStart = System.currentTimeMillis()
                val report = executor.execute(stage.toolCall)
                val stageElapsed = (System.currentTimeMillis() - stageStart).coerceAtLeast(1L)
                executedStages.add(stage.toolCall)
                if (report.success) anyStageSucceeded = true

                if (!report.success && report.summary.startsWith("TIER_RED_BLOCKED:")) {
                    val parts = report.summary.removePrefix("TIER_RED_BLOCKED:").split(":", limit = 2)
                    val nodeId = parts.getOrNull(0).orEmpty().ifBlank { "n01" }
                    val label = parts.getOrNull(1).orEmpty().ifBlank { "Confirm Action" }
                    val liveShot = NovaAccessibilityService.instance?.captureScreenshotJpeg()
                    pendingRedFromPlan = TierRedConfirmation(
                        id = UUID.randomUUID().toString().take(8),
                        title = "Confirm Sensitive Action",
                        summary = "Just to confirm: should I tap '$label'?",
                        targetApp = stage.targetAppName ?: "Current Screen",
                        targetNodeId = nodeId,
                        riskTag = "CONFIRMATION REQUIRED",
                        pipScreenNodes = report.nodesScanned,
                        pendingToolCall = ToolCallRequest(
                            toolName = "ui_act",
                            args = mapOf("action" to "CLICK", "node_id" to nodeId),
                            safetyTier = SafetyTier.RED
                        ),
                        pipScreenshotBytes = liveShot
                    )
                    steps[pillIdx] = steps[pillIdx].copy(status = StepStatus.BLOCKED, latencyMs = stageElapsed)
                    for (remIdx in (idx + 1) until multiStagePlan.stages.size) {
                        steps[baseOffset + remIdx] = steps[baseOffset + remIdx].copy(status = StepStatus.BLOCKED)
                    }
                    break
                }

                steps[pillIdx] = steps[pillIdx].copy(
                    status = if (report.success) StepStatus.DONE else StepStatus.BLOCKED,
                    latencyMs = stageElapsed
                )
                if (report.ftsResults.size > 1 ||
                    (report.ftsResults.size == 1 && report.summary.contains("Would you like", ignoreCase = true))
                ) {
                    pendingChoices = report.ftsResults
                }
                if (report.summary.startsWith("NEEDS_LLM_LOOP:") && report.nodesScanned.isNotEmpty()) {
                    llmLoopHandoffNodes = report.nodesScanned
                }
                stageReports.add(report.summary.removePrefix("NEEDS_LLM_LOOP:"))
                onStepUpdate(
                    steps.toList(),
                    OrbVisualMode.UI_NAVIGATING,
                    if (report.success) "DONE STAGE ${idx + 1}/${multiStagePlan.stages.size}" else "STAGE ${idx + 1} BLOCKED"
                )

                if (stage.stopOnFailure && !report.success) {
                    for (remIdx in (idx + 1) until multiStagePlan.stages.size) {
                        steps[baseOffset + remIdx] = steps[baseOffset + remIdx].copy(status = StepStatus.BLOCKED)
                    }
                    break
                }
            }

            if (pendingRedFromPlan != null) {
                val totalMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                onStepUpdate(steps.toList(), OrbVisualMode.CONFIRMATION_PAUSED, "CONFIRMATION REQUIRED")
                return TurnExecutionOutcome(
                    normalizedQuery = effectiveNormalized.cleanedFull,
                    routingTier = RoutingTier.TIER_1_REGEX,
                    selectedSkills = multiStagePlan.stages.map { it.skillId }.distinct(),
                    latencyMs = totalMs,
                    thinkingEnabled = false,
                    steps = steps.toList(),
                    responseText = pendingRedFromPlan.summary,
                    toolCalls = executedStages,
                    pendingConfirmation = pendingRedFromPlan,
                    orbMode = OrbVisualMode.CONFIRMATION_PAUSED
                )
            }

            // Optional Tier 3 UI-loop handoff if a search stage saw the screen but needed visual reasoning
            if (llmLoopHandoffNodes.isEmpty() || !llmBridge.state.value.modelPresent) {
                val totalMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                val hadUi = multiStagePlan.stages.any { it.kind == MultiStageActionPlanner.StageExecutionKind.UI_AUTOMATION }
                val finalTier = if (hadUi) RoutingTier.TIER_3_TOOL_LOOP else RoutingTier.TIER_1_REGEX
                val combinedResponse = formatTier1Responses(stageReports)
                val needsAcc = NovaAccessibilityService.instance == null &&
                    combinedResponse.contains("Accessibility", ignoreCase = true)
                val topicFromPlan = executedStages.firstOrNull {
                    !it.args["query"].isNullOrBlank() || !it.args["query_text"].isNullOrBlank()
                }?.let { it.args["query"] ?: it.args["query_text"] }
                recordTurnContext(effectiveNormalized.cleanedFull, combinedResponse, topicFromPlan)
                agenticMemoryEngine?.learnFromTurn(
                    rawQuery = rawQuery,
                    normalizedQuery = effectiveNormalized.cleanedFull,
                    subCommands = effectiveNormalized.subCommands,
                    toolCalls = executedStages,
                    responseText = combinedResponse,
                    success = anyStageSucceeded
                )
                onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "DONE")
                return TurnExecutionOutcome(
                    normalizedQuery = effectiveNormalized.cleanedFull,
                    routingTier = finalTier,
                    selectedSkills = multiStagePlan.stages.map { it.skillId }.distinct(),
                    latencyMs = totalMs,
                    thinkingEnabled = false,
                    steps = steps.toList(),
                    responseText = combinedResponse,
                    toolCalls = executedStages,
                    orbMode = OrbVisualMode.FAST_PATH_FLASH,
                    requiresAccessibilitySetup = needsAcc
                )
            }
        }

        // TIER 1B: Deterministic Regex Fast-Path (< 5 ms, Process A)
        if (!effectiveNormalized.hasConditionalLogic) {
            val installedApps = installedAppsForFollowUp
            val wholeMatch = RegexFastPathEngine.match(effectiveNormalized.cleanedFull, installedApps)
            val fastMatches = if (wholeMatch != null) {
                listOf(wholeMatch)
            } else {
                val perPart = effectiveNormalized.subCommands.mapNotNull { cmd -> RegexFastPathEngine.match(cmd, installedApps) }
                if (perPart.size == effectiveNormalized.subCommands.size) perPart else emptyList()
            }
            if (fastMatches.isNotEmpty()) {
                if (fastMatches.size == 1 &&
                    fastMatches[0].toolCall.toolName == "manage_agentic_memory" &&
                    fastMatches[0].toolCall.args["action"] == "run_process"
                ) {
                    val target = fastMatches[0].toolCall.args["target"].orEmpty()
                    val matchedProc = agenticMemoryEngine?.findLearnedProcess(target)
                    if (matchedProc != null) {
                        return executeLearnedProcessSteps(
                            process = matchedProc,
                            normalizedQuery = normalized.cleanedFull,
                            steps = steps,
                            totalStart = totalStart,
                            onStepUpdate = onStepUpdate
                        )
                    }
                }

                val reports = mutableListOf<String>()
                var anyFastSucceeded = false
                var pendingRedFromFastPath: TierRedConfirmation? = null
                fastMatches.forEachIndexed { idx, match ->
                    if (match.toolCall.safetyTier == SafetyTier.RED) {
                        val targetLabel = match.toolCall.args["target_label"]
                            ?: match.toolCall.args["goal"]
                            ?: "Confirm Action"
                        val appLabel = match.toolCall.args["app_name"] ?: "Current Screen"
                        val liveSvc = NovaAccessibilityService.instance
                        val liveNodes = liveSvc?.scanActiveScreenNodes().orEmpty()
                        val liveShot = liveSvc?.captureScreenshotJpeg()
                        pendingRedFromFastPath = TierRedConfirmation(
                            id = UUID.randomUUID().toString().take(8),
                            title = "Confirm Sensitive Action",
                            summary = "Just to confirm: should I perform '$targetLabel' in $appLabel?",
                            targetApp = appLabel,
                            targetNodeId = "n01",
                            riskTag = "CONFIRMATION REQUIRED",
                            pipScreenNodes = liveNodes,
                            pendingToolCall = match.toolCall,
                            pipScreenshotBytes = liveShot
                        )
                        steps.add(
                            PipelineStepPill(
                                index = steps.size + 1,
                                label = "Needs approval: $targetLabel",
                                status = StepStatus.BLOCKED
                            )
                        )
                        return@forEachIndexed
                    }

                    val report = executor.execute(match.toolCall)
                    if (report.success) anyFastSucceeded = true
                    if (!report.success && report.summary.startsWith("TIER_RED_BLOCKED:")) {
                        val parts = report.summary.removePrefix("TIER_RED_BLOCKED:").split(":", limit = 2)
                        val nodeId = parts.getOrNull(0).orEmpty().ifBlank { "n01" }
                        val label = parts.getOrNull(1).orEmpty().ifBlank { "Confirm Action" }
                        val liveShot = NovaAccessibilityService.instance?.captureScreenshotJpeg()
                        pendingRedFromFastPath = TierRedConfirmation(
                            id = UUID.randomUUID().toString().take(8),
                            title = "Confirm Sensitive Action",
                            summary = "Just to confirm: should I tap '$label'?",
                            targetApp = "Current Screen",
                            targetNodeId = nodeId,
                            riskTag = "CONFIRMATION REQUIRED",
                            pipScreenNodes = report.nodesScanned,
                            pendingToolCall = ToolCallRequest(
                                toolName = "ui_act",
                                args = mapOf("action" to "CLICK", "node_id" to nodeId),
                                safetyTier = SafetyTier.RED
                            ),
                            pipScreenshotBytes = liveShot
                        )
                        steps.add(
                            PipelineStepPill(
                                index = steps.size + 1,
                                label = "Needs approval: $label",
                                status = StepStatus.BLOCKED
                            )
                        )
                        return@forEachIndexed
                    }

                    if (report.ftsResults.size > 1 ||
                        (report.ftsResults.size == 1 && report.summary.contains("Would you like", ignoreCase = true))
                    ) {
                        pendingChoices = report.ftsResults
                    }
                    reports.add(report.summary.removePrefix("NEEDS_LLM_LOOP:"))
                    steps.add(
                        PipelineStepPill(
                            index = steps.size + 1,
                            label = match.summary,
                            status = if (report.success) StepStatus.DONE else StepStatus.BLOCKED,
                            latencyMs = 3L + idx
                        )
                    )
                }
                val totalMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                val activeRed = pendingRedFromFastPath
                if (activeRed != null) {
                    onStepUpdate(steps.toList(), OrbVisualMode.CONFIRMATION_PAUSED, "CONFIRMATION REQUIRED")
                    return TurnExecutionOutcome(
                        normalizedQuery = effectiveNormalized.cleanedFull,
                        routingTier = RoutingTier.TIER_1_REGEX,
                        selectedSkills = fastMatches.map { it.skillId }.distinct(),
                        latencyMs = totalMs,
                        thinkingEnabled = false,
                        steps = steps.toList(),
                        responseText = activeRed.summary,
                        toolCalls = fastMatches.map { it.toolCall },
                        pendingConfirmation = activeRed,
                        orbMode = OrbVisualMode.CONFIRMATION_PAUSED
                    )
                }
                val fastCombined = formatTier1Responses(reports)
                val fastCalls = fastMatches.map { it.toolCall }
                val offeredProcess = fastCalls.any {
                    it.toolName == "get_device_briefing" ||
                        (it.toolName == "get_routine_steps" && it.args["routine_name"] == "morning")
                } && (fastCombined.contains("Would you like me to run", ignoreCase = true) ||
                    fastCombined.contains("Want me to kick it off", ignoreCase = true) ||
                    fastCombined.contains("Run it now?", ignoreCase = true) ||
                    fastCombined.contains("Run '", ignoreCase = true))
                if (offeredProcess) {
                    pendingSuggestedProcess = agenticMemoryEngine?.findTopSuggestedProcessForBriefing()
                }
                val fastTopicHint = fastCalls.firstOrNull {
                    !it.args["query"].isNullOrBlank() || !it.args["target"].isNullOrBlank()
                }?.let { it.args["query"] ?: it.args["target"] }
                recordTurnContext(effectiveNormalized.cleanedFull, fastCombined, fastTopicHint)
                agenticMemoryEngine?.learnFromTurn(
                    rawQuery = rawQuery,
                    normalizedQuery = effectiveNormalized.cleanedFull,
                    subCommands = effectiveNormalized.subCommands,
                    toolCalls = fastCalls,
                    responseText = fastCombined,
                    success = anyFastSucceeded
                )
                val needsAcc = NovaAccessibilityService.instance == null &&
                    fastCombined.contains("Accessibility", ignoreCase = true)
                onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "DONE")
                return TurnExecutionOutcome(
                    normalizedQuery = effectiveNormalized.cleanedFull,
                    routingTier = RoutingTier.TIER_1_REGEX,
                    selectedSkills = fastMatches.map { it.skillId }.distinct(),
                    latencyMs = totalMs,
                    thinkingEnabled = false,
                    steps = steps.toList(),
                    responseText = fastCombined,
                    toolCalls = fastCalls,
                    orbMode = OrbVisualMode.FAST_PATH_FLASH,
                    requiresAccessibilitySetup = needsAcc
                )
            }
        }

        // Safety net 1: any conversational notification-reading request should NEVER fall through to briefing or LLM
        val notifSafetyCall = buildNotificationSafetyNetCall(effectiveNormalized.cleanedFull)
        if (notifSafetyCall != null) {
            val report = executor.execute(notifSafetyCall)
            if (report.ftsResults.size > 1 ||
                (report.ftsResults.size == 1 && report.summary.contains("Would you like", ignoreCase = true))
            ) {
                pendingChoices = report.ftsResults
            }
            val formatted = formatTier1Responses(listOf(report.summary))
            steps.add(
                PipelineStepPill(
                    index = steps.size + 1,
                    label = "Checked notifications",
                    status = if (report.success) StepStatus.DONE else StepStatus.BLOCKED,
                    latencyMs = 4L
                )
            )
            recordTurnContext(effectiveNormalized.cleanedFull, formatted)
            val totalMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
            onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "DONE")
            return TurnExecutionOutcome(
                normalizedQuery = effectiveNormalized.cleanedFull,
                routingTier = RoutingTier.TIER_1_REGEX,
                selectedSkills = listOf("messaging-omni-send"),
                latencyMs = totalMs,
                thinkingEnabled = false,
                steps = steps.toList(),
                responseText = formatted,
                toolCalls = listOf(notifSafetyCall),
                orbMode = OrbVisualMode.FAST_PATH_FLASH
            )
        }

        // Safety net 1b: any question asking about available routines or step-by-step routine details resolves instantly
        val routineSafetyCall = buildRoutineInfoSafetyNetCall(effectiveNormalized.cleanedFull)
        if (routineSafetyCall != null) {
            val report = executor.execute(routineSafetyCall)
            if (report.ftsResults.size > 1) {
                pendingChoices = report.ftsResults
            }
            val formatted = formatTier1Responses(listOf(report.summary))
            steps.add(
                PipelineStepPill(
                    index = steps.size + 1,
                    label = if (routineSafetyCall.args["action"] == "list_steps") "Listed routine steps" else "Listed available routines",
                    status = if (report.success) StepStatus.DONE else StepStatus.BLOCKED,
                    latencyMs = 3L
                )
            )
            recordTurnContext(effectiveNormalized.cleanedFull, formatted, topicHint = "routines")
            val totalMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
            onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "DONE")
            return TurnExecutionOutcome(
                normalizedQuery = effectiveNormalized.cleanedFull,
                routingTier = RoutingTier.TIER_1_REGEX,
                selectedSkills = listOf("compound-routine-orchestrator"),
                latencyMs = totalMs,
                thinkingEnabled = false,
                steps = steps.toList(),
                responseText = formatted,
                toolCalls = listOf(routineSafetyCall),
                orbMode = OrbVisualMode.FAST_PATH_FLASH
            )
        }

        // Safety net 2: any conversational briefing/recap request should NEVER fall through to YouTube or search
        if (isBriefingSafetyNetQuery(effectiveNormalized.cleanedFull)) {
            val lowerB = effectiveNormalized.cleanedFull.lowercase()
            val topic = when {
                lowerB.contains("week") -> "weekly"
                lowerB.contains("tonight") || lowerB.contains("evening") || lowerB.contains("night") -> "evening"
                lowerB.contains("afternoon") -> "afternoon"
                else -> "morning"
            }
            val call = ToolCallRequest(
                toolName = "get_device_briefing",
                args = mapOf("topic" to topic),
                safetyTier = SafetyTier.GREEN
            )
            val report = executor.execute(call)
            val formatted = formatTier1Responses(listOf(report.summary))
            steps.add(
                PipelineStepPill(
                    index = steps.size + 1,
                    label = "Prepared $topic briefing",
                    status = if (report.success) StepStatus.DONE else StepStatus.BLOCKED,
                    latencyMs = 4L
                )
            )
            recordTurnContext(effectiveNormalized.cleanedFull, formatted)
            val totalMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
            onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "DONE")
            return TurnExecutionOutcome(
                normalizedQuery = effectiveNormalized.cleanedFull,
                routingTier = RoutingTier.TIER_1_REGEX,
                selectedSkills = listOf("alexa-utility-briefings"),
                latencyMs = totalMs,
                thinkingEnabled = false,
                steps = steps.toList(),
                responseText = formatted,
                toolCalls = listOf(call),
                orbMode = OrbVisualMode.FAST_PATH_FLASH
            )
        }

        // TIER 1.5: Semantic Lookup in Process A (EmbeddingGemma 2 when present)
        val semanticStart = System.currentTimeMillis()
        val semantic = SemanticLookupEngine.lookupWithRuntime(effectiveNormalized.cleanedFull, embeddingRuntime)
        val semanticMs = (System.currentTimeMillis() - semanticStart).coerceAtLeast(2L)
        val candidateIds = semantic.candidateSkills.map { it.id }

        steps.add(
            PipelineStepPill(
                index = steps.size + 1,
                label = "Matched ${semantic.candidateSkills.firstOrNull()?.title ?: "assistant"}",
                status = StepStatus.DONE,
                latencyMs = semanticMs
            )
        )

        val internetAvailable = WebKnowledgeResolver.isInternetAvailable(executor.appContext)
        val isConversationalChat = WebKnowledgeResolver.isConversationalOrPersonalChat(effectiveNormalized.cleanedFull)
        val isFactualWebQuery = !isConversationalChat &&
            WebKnowledgeResolver.isGeneralKnowledgeQuestion(effectiveNormalized.cleanedFull)
        val isBroadWebCandidate = !isConversationalChat && (
            isFactualWebQuery ||
                WebKnowledgeResolver.isInformationalWebFallbackCandidate(effectiveNormalized.cleanedFull)
            )

        // When online, answer general-knowledge/song/unknown informational questions directly from live web
        // (and when offline or model is absent, handle gracefully instead of falsely claiming model missing)
        if (isBroadWebCandidate && (isFactualWebQuery || !llmBridge.state.value.modelPresent)) {
            if (internetAvailable) {
                val directAnswer = WebKnowledgeResolver.fetchCleanAnswer(effectiveNormalized.cleanedFull)
                if (!directAnswer.isNullOrBlank()) {
                    val elapsed = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                    steps.add(
                        PipelineStepPill(
                            index = steps.size + 1,
                            label = "Answered from web",
                            status = StepStatus.DONE,
                            latencyMs = elapsed
                        )
                    )
                    val resolvedTopic = WebKnowledgeResolver.extractSearchTopic(effectiveNormalized.cleanedFull)
                    recordTurnContext(effectiveNormalized.cleanedFull, directAnswer, resolvedTopic)
                    agenticMemoryEngine?.learnFromTurn(
                        rawQuery = rawQuery,
                        normalizedQuery = effectiveNormalized.cleanedFull,
                        subCommands = effectiveNormalized.subCommands,
                        toolCalls = listOf(
                            ToolCallRequest(
                                toolName = "answer_web_question",
                                args = mapOf("query" to effectiveNormalized.cleanedFull.take(140)),
                                safetyTier = SafetyTier.GREEN
                            )
                        ),
                        responseText = directAnswer,
                        success = true
                    )
                    onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "DONE")
                    return TurnExecutionOutcome(
                        normalizedQuery = effectiveNormalized.cleanedFull,
                        routingTier = RoutingTier.TIER_1_5_SEMANTIC,
                        selectedSkills = listOf("general-knowledge-qa"),
                        latencyMs = elapsed,
                        thinkingEnabled = false,
                        steps = steps.toList(),
                        responseText = directAnswer,
                        orbMode = OrbVisualMode.FAST_PATH_FLASH
                    )
                } else if (isFactualWebQuery && !llmBridge.state.value.modelPresent) {
                    val elapsed = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                    val topic = WebKnowledgeResolver.extractSearchTopic(effectiveNormalized.cleanedFull)
                    val notFoundMsg = "I checked online for \"$topic\", but couldn't find a clear answer. Could you rephrase or give me a little more context?"
                    recordTurnContext(effectiveNormalized.cleanedFull, notFoundMsg, topic)
                    onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "NOT FOUND ONLINE")
                    return TurnExecutionOutcome(
                        normalizedQuery = effectiveNormalized.cleanedFull,
                        routingTier = RoutingTier.TIER_1_5_SEMANTIC,
                        selectedSkills = listOf("general-knowledge-qa"),
                        latencyMs = elapsed,
                        thinkingEnabled = false,
                        steps = steps.toList(),
                        responseText = notFoundMsg,
                        orbMode = OrbVisualMode.FAST_PATH_FLASH,
                        requiresModelSetup = false
                    )
                }
            } else if (!llmBridge.state.value.modelPresent && isFactualWebQuery) {
                val elapsed = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                val topic = WebKnowledgeResolver.extractSearchTopic(effectiveNormalized.cleanedFull)
                val offlineMsg = "I don't have details on \"$topic\" stored offline, and there's no internet connection right now to check online. Try again once you're back online."
                recordTurnContext(effectiveNormalized.cleanedFull, offlineMsg, topic)
                steps.add(
                    PipelineStepPill(
                        index = steps.size + 1,
                        label = "Offline (no internet)",
                        status = StepStatus.BLOCKED,
                        latencyMs = elapsed
                    )
                )
                onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "OFFLINE")
                return TurnExecutionOutcome(
                    normalizedQuery = effectiveNormalized.cleanedFull,
                    routingTier = RoutingTier.TIER_1_5_SEMANTIC,
                    selectedSkills = listOf("general-knowledge-qa"),
                    latencyMs = elapsed,
                    thinkingEnabled = false,
                    steps = steps.toList(),
                    responseText = offlineMsg,
                    orbMode = OrbVisualMode.FAST_PATH_FLASH,
                    requiresModelSetup = false
                )
            }
        }

        // Check real LLM model presence BEFORE proceeding to Tier 2/3
        if (!llmBridge.state.value.modelPresent) {
            val elapsed = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
            steps.add(
                PipelineStepPill(
                    index = steps.size + 1,
                    label = "AI model needed",
                    status = StepStatus.BLOCKED
                )
            )
            onStepUpdate(steps.toList(), OrbVisualMode.ERROR_ABORTED, "AI MODEL NEEDED")
            return TurnExecutionOutcome(
                normalizedQuery = effectiveNormalized.cleanedFull,
                routingTier = RoutingTier.TIER_1_5_SEMANTIC,
                selectedSkills = candidateIds,
                latencyMs = elapsed,
                thinkingEnabled = false,
                steps = steps.toList(),
                responseText = "Please download or import a local AI model (Gemma 4, Gemma 3n, LFM2.5, or Qwen) in Settings first.",
                orbMode = OrbVisualMode.ERROR_ABORTED,
                requiresModelSetup = true
            )
        }

        val usesThinking = !isConversationalChat && thinkingAllowed && semantic.candidateSkills.any { it.requiresThinking }
        onStepUpdate(
            steps.toList(),
            if (usesThinking) OrbVisualMode.THINKING else OrbVisualMode.LISTENING,
            if (usesThinking) "THINKING..." else "WORKING..."
        )

        val currentAccount = resolveAndSyncAccount()
        val installedAppsCtx = if (isConversationalChat) null else executor.buildInstalledAppsContext(effectiveNormalized.cleanedFull)
        val conversationCtx = buildRecentConversationContext()
        val queryLowerForRoutineCtx = effectiveNormalized.cleanedFull.lowercase()
        val wantsRoutineDetail = !isConversationalChat && (
            queryLowerForRoutineCtx.contains("routine") ||
                queryLowerForRoutineCtx.contains("workflow") ||
                queryLowerForRoutineCtx.contains("steps") ||
                queryLowerForRoutineCtx.contains("automate")
            )
        val routineKnowledgeCtx = if (wantsRoutineDetail) {
            executor.buildRoutineKnowledgeSummary().takeIf { it.isNotBlank() }
        } else {
            null
        }
        val memoryCtx = listOfNotNull(
            conversationCtx,
            routineKnowledgeCtx,
            agenticMemoryEngine?.buildPromptMemoryContext(effectiveNormalized.cleanedFull)
        ).joinToString("\n\n").ifBlank { null }
        val personalityCtx = agenticMemoryEngine?.buildPersonalityProfileBlock()

        // TIER 2: Process B (:llm) Inference (conversational fast mode or progressive tool disclosure)
        var llmResult = try {
            llmBridge.inferTurn(
                query = effectiveNormalized.cleanedFull,
                candidateSkills = candidateIds,
                fewShots = semantic.topFewShotExamples,
                installedAppsContext = installedAppsCtx,
                recordedAccount = currentAccount,
                memoryContext = memoryCtx,
                personalityProfile = personalityCtx,
                resetConversation = true,
                allowThinking = thinkingAllowed
            )
        } catch (ex: LlmModelMissingException) {
            val elapsed = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
            steps.add(PipelineStepPill(steps.size + 1, "AI model unavailable", StepStatus.BLOCKED))
            onStepUpdate(steps.toList(), OrbVisualMode.ERROR_ABORTED, "AI MODEL NEEDED")
            return TurnExecutionOutcome(
                normalizedQuery = normalized.cleanedFull,
                routingTier = RoutingTier.TIER_2_SKILL_DISCLOSURE,
                selectedSkills = candidateIds,
                latencyMs = elapsed,
                thinkingEnabled = false,
                steps = steps.toList(),
                responseText = ex.message ?: "Please download the local AI model in Settings first.",
                orbMode = OrbVisualMode.ERROR_ABORTED,
                requiresModelSetup = true
            )
        } catch (ex: kotlinx.coroutines.CancellationException) {
            throw ex
        } catch (ex: Exception) {
            val elapsed = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
            val fallbackMsg = ex.message?.takeIf { it.isNotBlank() }
                ?: "Sorry, the local AI hit a snag on that request. Please try rephrasing."
            steps.add(PipelineStepPill(steps.size + 1, "AI request timed out", StepStatus.BLOCKED, latencyMs = elapsed))
            onStepUpdate(steps.toList(), OrbVisualMode.ERROR_ABORTED, "TRY AGAIN")
            return TurnExecutionOutcome(
                normalizedQuery = effectiveNormalized.cleanedFull,
                routingTier = RoutingTier.TIER_2_SKILL_DISCLOSURE,
                selectedSkills = candidateIds,
                latencyMs = elapsed,
                thinkingEnabled = false,
                steps = steps.toList(),
                responseText = fallbackMsg,
                orbMode = OrbVisualMode.ERROR_ABORTED
            )
        }

        val planLabel = if (llmResult.toolCalls.size > 1) {
            "Plan finalized (${llmResult.toolCalls.size} stages)"
        } else {
            "Planned action"
        }
        steps.add(
            PipelineStepPill(
                index = steps.size + 1,
                label = planLabel,
                status = StepStatus.DONE,
                latencyMs = llmResult.inferenceMs
            )
        )
        val hasPrePlanned = llmResult.toolCalls.size > 1
        val prePlannedBase = steps.size
        if (hasPrePlanned) {
            llmResult.toolCalls.forEachIndexed { idx, plannedCall ->
                steps.add(
                    PipelineStepPill(
                        index = prePlannedBase + idx + 1,
                        label = friendlyToolLabel(plannedCall),
                        status = StepStatus.PENDING
                    )
                )
            }
            onStepUpdate(
                steps.toList(),
                OrbVisualMode.UI_NAVIGATING,
                "PLAN FINALIZED • ${llmResult.toolCalls.size} STAGES"
            )
        }

        // TIER 3: Closed-Loop Tool, Autonomous Dialog Resolution & UI Loop
        val outputs = mutableListOf<String>()
        val executedCalls = mutableListOf<ToolCallRequest>()
        var lastScannedNodes: List<UiNodeSnapshot> = emptyList()
        var lastScreenshotBytes: ByteArray? = null
        var pendingRedConfirmation: TierRedConfirmation? = null

        var currentCalls = llmResult.toolCalls
        var iteration = 0
        while (currentCalls.isNotEmpty() && iteration < MAX_UI_LOOP_STEPS) {
            iteration++
            var needNextUiLoopTurn = false
            var nextUiContext: String? = null
            var nextVisionBytes: ByteArray? = null

            fun updateOrAddStep(callIdx: Int, label: String, status: StepStatus) {
                if (iteration == 1 && hasPrePlanned && (prePlannedBase + callIdx) in steps.indices) {
                    steps[prePlannedBase + callIdx] = steps[prePlannedBase + callIdx].copy(
                        label = label,
                        status = status
                    )
                } else {
                    steps.add(PipelineStepPill(steps.size + 1, label, status))
                }
            }

            for ((callIdx, call) in currentCalls.withIndex()) {
                executedCalls.add(call)
                if (iteration == 1 && hasPrePlanned && (prePlannedBase + callIdx) in steps.indices) {
                    steps[prePlannedBase + callIdx] = steps[prePlannedBase + callIdx].copy(status = StepStatus.ACTIVE)
                    onStepUpdate(steps.toList(), OrbVisualMode.UI_NAVIGATING, "STEP ${callIdx + 1}/${currentCalls.size}...")
                }

                when (call.toolName) {
                    "ui_open_and_scan" -> {
                        val appName = call.args["app_name"] ?: "App"
                        onStepUpdate(
                            steps.toList(),
                            OrbVisualMode.UI_NAVIGATING,
                            "OPENING ${appName.uppercase()}..."
                        )
                        val report = executor.execute(call)
                        lastScannedNodes = autoHandleIntermediateDialogs(
                            appHint = appName,
                            steps = steps,
                            initialNodes = report.nodesScanned
                        )
                        if (report.capturedScreenshotBytes != null) lastScreenshotBytes = report.capturedScreenshotBytes
                        outputs.add(report.summary)
                        updateOrAddStep(
                            callIdx,
                            if (report.success) "Opened $appName" else "Screen control off",
                            if (report.success) StepStatus.DONE else StepStatus.BLOCKED
                        )
                        if (report.success && (lastScannedNodes.isNotEmpty() || report.capturedScreenshotBytes != null)) {
                            needNextUiLoopTurn = true
                            nextUiContext = formatUiNodes(lastScannedNodes)
                            if (lastScannedNodes.size <= 2) {
                                nextVisionBytes = report.capturedScreenshotBytes
                            }
                        }
                    }

                    "ui_vision_fallback", "capture_current_screen" -> {
                        val report = executor.execute(call)
                        lastScannedNodes = autoHandleIntermediateDialogs(
                            appHint = null,
                            steps = steps,
                            initialNodes = report.nodesScanned
                        )
                        if (report.capturedScreenshotBytes != null) {
                            lastScreenshotBytes = report.capturedScreenshotBytes
                            nextVisionBytes = report.capturedScreenshotBytes
                        }
                        if (report.success && (lastScannedNodes.isNotEmpty() || report.capturedScreenshotBytes != null)) {
                            needNextUiLoopTurn = true
                            nextUiContext = formatUiNodes(lastScannedNodes)
                        }
                        outputs.add(report.summary)
                        updateOrAddStep(
                            callIdx,
                            "Read screen",
                            if (report.success) StepStatus.DONE else StepStatus.BLOCKED
                        )
                    }

                    else -> {
                        // Safety Tier Red check ONLY for truly risky irreversible actions
                        val dynamicTier = evaluateDynamicRedTier(call, lastScannedNodes)
                        if (dynamicTier == SafetyTier.RED) {
                            val targetId = call.args["node_id"] ?: "n01"
                            val targetNode = lastScannedNodes.firstOrNull { it.id == targetId }
                            val targetLabel = targetNode?.label ?: call.args["text"] ?: "Confirm Action"
                            val appLabel = executedCalls
                                .firstOrNull { it.toolName == "ui_open_and_scan" || it.toolName == "launch_app" }
                                ?.args?.get("app_name") ?: "Current App"

                            val liveShot = lastScreenshotBytes ?: NovaAccessibilityService.instance?.captureScreenshotJpeg()

                            updateOrAddStep(callIdx, "Needs approval: $targetLabel", StepStatus.BLOCKED)
                            onStepUpdate(steps.toList(), OrbVisualMode.CONFIRMATION_PAUSED, "CONFIRMATION REQUIRED")

                            pendingRedConfirmation = TierRedConfirmation(
                                id = UUID.randomUUID().toString().take(8),
                                title = "Confirm Sensitive Action",
                                summary = "Just to confirm: should I tap '$targetLabel' in $appLabel?",
                                targetApp = appLabel,
                                targetNodeId = targetId,
                                riskTag = "CONFIRMATION REQUIRED",
                                pipScreenNodes = lastScannedNodes,
                                pendingToolCall = call.copy(safetyTier = SafetyTier.RED),
                                pipScreenshotBytes = liveShot
                            )
                            needNextUiLoopTurn = false
                            break
                        }

                        val report = executor.execute(call)
                        if (report.ftsResults.size > 1 ||
                            (report.ftsResults.size == 1 && report.summary.contains("Would you like", ignoreCase = true))
                        ) {
                            pendingChoices = report.ftsResults
                        }
                        if ((call.toolName == "screen_control_app" || call.toolName == "search_in_app") &&
                            report.summary.startsWith("NEEDS_LLM_LOOP:")
                        ) {
                            val cleanMsg = report.summary.removePrefix("NEEDS_LLM_LOOP:")
                            outputs.add(cleanMsg)
                            lastScannedNodes = report.nodesScanned
                            if (lastScannedNodes.isNotEmpty()) {
                                needNextUiLoopTurn = true
                                nextUiContext = formatUiNodes(lastScannedNodes)
                            }
                        } else {
                            outputs.add(report.summary.removePrefix("NEEDS_LLM_LOOP:"))
                        }
                        if (call.toolName == "ui_act" || call.toolName == "ui_find_and_tap") {
                            lastScannedNodes = autoHandleIntermediateDialogs(
                                appHint = executedCalls.firstOrNull { it.toolName == "ui_open_and_scan" || it.toolName == "screen_control_app" }?.args?.get("app_name"),
                                steps = steps,
                                initialNodes = report.nodesScanned
                            )
                            if (call.toolName == "ui_act" && (report.success || lastScannedNodes.isNotEmpty())) {
                                needNextUiLoopTurn = true
                                nextUiContext = formatUiNodes(lastScannedNodes)
                                if (lastScannedNodes.size <= 2) {
                                    nextVisionBytes = NovaAccessibilityService.instance?.captureScreenshotJpeg()
                                }
                            }
                        }
                        updateOrAddStep(
                            callIdx,
                            friendlyToolLabel(call),
                            if (report.success) StepStatus.DONE else StepStatus.BLOCKED
                        )
                        if (!report.success && call.toolName == "launch_app" && iteration == 1 && hasPrePlanned) {
                            for (rem in (callIdx + 1) until currentCalls.size) {
                                if ((prePlannedBase + rem) in steps.indices) {
                                    steps[prePlannedBase + rem] = steps[prePlannedBase + rem].copy(status = StepStatus.BLOCKED)
                                }
                            }
                            break
                        }
                    }
                }
            }

            if (pendingRedConfirmation != null || !needNextUiLoopTurn) break

            onStepUpdate(steps.toList(), OrbVisualMode.UI_NAVIGATING, "CHECKING SCREEN...")
            try {
                llmResult = llmBridge.inferTurn(
                    query = effectiveNormalized.cleanedFull,
                    candidateSkills = candidateIds,
                    fewShots = semantic.topFewShotExamples,
                    imageBytes = nextVisionBytes,
                    uiTreeContext = nextUiContext,
                    installedAppsContext = installedAppsCtx,
                    recordedAccount = resolveAndSyncAccount(),
                    memoryContext = memoryCtx,
                    personalityProfile = personalityCtx,
                    resetConversation = false,
                    allowThinking = thinkingAllowed
                )
                currentCalls = llmResult.toolCalls
            } catch (ex: kotlinx.coroutines.CancellationException) {
                throw ex
            } catch (ex: Exception) {
                steps.add(PipelineStepPill(steps.size + 1, "Screen step timed out", StepStatus.BLOCKED))
                if (outputs.isEmpty()) {
                    outputs.add(ex.message?.takeIf { it.isNotBlank() } ?: "Screen step timed out. Please try again.")
                }
                break
            }
        }

        val totalMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
        val finalTier = if (executedCalls.any { it.toolName.startsWith("ui_") }) {
            RoutingTier.TIER_3_TOOL_LOOP
        } else {
            RoutingTier.TIER_2_SKILL_DISCLOSURE
        }

        val safeLlmNote = com.example.processB.LiteRtLmRuntimeWrapper.sanitizeAssistantReply(
            parsedReply = llmResult.note,
            rawFallback = llmResult.note,
            hasToolCalls = outputs.isNotEmpty()
        )

        val responseText = when {
            pendingRedConfirmation != null -> pendingRedConfirmation.summary
            pendingChoices.size > 1 && outputs.isNotEmpty() -> outputs.last()
            safeLlmNote.isNotBlank() && outputs.isNotEmpty() -> "$safeLlmNote\n\n${outputs.last()}"
            outputs.isNotEmpty() -> outputs.joinToString("\n\n")
            safeLlmNote.isNotBlank() -> safeLlmNote
            else -> "Done."
        }

        val finalOrb = if (pendingRedConfirmation != null) {
            OrbVisualMode.CONFIRMATION_PAUSED
        } else {
            OrbVisualMode.FAST_PATH_FLASH
        }

        val finalNeedsAcc = NovaAccessibilityService.instance == null &&
            responseText.contains("Accessibility", ignoreCase = true)

        if (pendingRedConfirmation == null) {
            recordTurnContext(effectiveNormalized.cleanedFull, responseText)
            agenticMemoryEngine?.learnFromTurn(
                rawQuery = rawQuery,
                normalizedQuery = effectiveNormalized.cleanedFull,
                subCommands = effectiveNormalized.subCommands,
                toolCalls = executedCalls,
                responseText = responseText,
                success = true
            )
        }

        return TurnExecutionOutcome(
            normalizedQuery = effectiveNormalized.cleanedFull,
            routingTier = finalTier,
            selectedSkills = listOf(llmResult.selectedSkillId),
            latencyMs = totalMs,
            thinkingEnabled = llmResult.thinkingEnabled,
            steps = steps.toList(),
            responseText = responseText,
            toolCalls = executedCalls,
            pendingConfirmation = pendingRedConfirmation,
            orbMode = finalOrb,
            requiresAccessibilitySetup = finalNeedsAcc
        )
    }

    suspend fun executeLearnedProcessSteps(
        process: LearnedProcessRecord,
        normalizedQuery: String = process.name,
        steps: MutableList<PipelineStepPill> = mutableListOf(),
        totalStart: Long = System.currentTimeMillis(),
        onStepUpdate: (List<PipelineStepPill>, OrbVisualMode, String) -> Unit = { _, _, _ -> }
    ): TurnExecutionOutcome {
        agenticMemoryEngine?.recordProcessExecution(process.processId)
        val installedApps = executor.discoverInstalledApps()
        val summaries = mutableListOf<String>()
        val ranCalls = mutableListOf<ToolCallRequest>()
        var lastOpenedApp: String? = process.preferredApp.takeIf { it.isNotBlank() }

        process.steps.forEachIndexed { idx, rawStepCmd ->
            val pillIdx = steps.size
            steps.add(
                PipelineStepPill(
                    index = pillIdx + 1,
                    label = rawStepCmd.take(36),
                    status = StepStatus.ACTIVE
                )
            )
            onStepUpdate(
                steps.toList(),
                OrbVisualMode.UI_NAVIGATING,
                "PROCESS ${idx + 1}/${process.steps.size}: ${process.name.uppercase()}"
            )

            val contextualStep = if (lastOpenedApp != null &&
                Regex("^(?:search|find|look up)\\s+.+$", RegexOption.IGNORE_CASE).matches(rawStepCmd.trim()) &&
                !Regex("\\b(?:on|in|inside)\\b", RegexOption.IGNORE_CASE).containsMatchIn(rawStepCmd)
            ) {
                "$rawStepCmd in $lastOpenedApp"
            } else {
                rawStepCmd
            }

            val stepNorm = InputNormalizer.normalize(contextualStep)
            val planned = MultiStageActionPlanner.plan(
                normalized = stepNorm,
                installedApps = installedApps,
                hasLaunchIntent = { pkg -> executor.hasLaunchIntent(pkg) }
            )
            var stepOk = false
            if (planned != null) {
                val subReports = mutableListOf<String>()
                for (st in planned.stages) {
                    if (st.toolCall.safetyTier == SafetyTier.RED) continue
                    val rep = executor.execute(st.toolCall)
                    ranCalls.add(st.toolCall)
                    if (st.toolCall.toolName == "launch_app") {
                        lastOpenedApp = st.toolCall.args["app_name"] ?: lastOpenedApp
                    }
                    if (rep.ftsResults.size > 1) {
                        pendingChoices = rep.ftsResults
                    }
                    subReports.add(rep.summary)
                    stepOk = stepOk || rep.success
                }
                if (subReports.isNotEmpty()) summaries.add(formatTier1Responses(subReports))
            } else {
                val fast = RegexFastPathEngine.match(stepNorm.cleanedFull, installedApps)
                if (fast != null && fast.toolCall.safetyTier != SafetyTier.RED) {
                    val rep = executor.execute(fast.toolCall)
                    ranCalls.add(fast.toolCall)
                    if (fast.toolCall.toolName == "launch_app") {
                        lastOpenedApp = fast.toolCall.args["app_name"] ?: lastOpenedApp
                    }
                    if (rep.ftsResults.size > 1) {
                        pendingChoices = rep.ftsResults
                    }
                    summaries.add(rep.summary)
                    stepOk = rep.success
                } else {
                    summaries.add("Prepared '$rawStepCmd'")
                    stepOk = true
                }
            }
            steps[pillIdx] = steps[pillIdx].copy(
                status = if (stepOk) StepStatus.DONE else StepStatus.BLOCKED,
                latencyMs = 5L
            )
        }

        val totalMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
        val formattedSummary = formatTier1Responses(summaries)
        val reply = "Ran '${process.name}': $formattedSummary"
        onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "DONE")
        return TurnExecutionOutcome(
            normalizedQuery = normalizedQuery,
            routingTier = RoutingTier.TIER_1_REGEX,
            selectedSkills = listOf("personal-memory-vault"),
            latencyMs = totalMs,
            thinkingEnabled = false,
            steps = steps.toList(),
            responseText = reply,
            toolCalls = ranCalls,
            orbMode = OrbVisualMode.FAST_PATH_FLASH
        )
    }

    /**
     * Automatically clears non-blocking pop-ups, permission dialogs, app choosers ("Open with"),
     * and single/recorded account selections without pausing for the user.
     */
    private suspend fun autoHandleIntermediateDialogs(
        appHint: String?,
        steps: MutableList<PipelineStepPill>,
        initialNodes: List<UiNodeSnapshot>
    ): List<UiNodeSnapshot> {
        val svc = NovaAccessibilityService.instance ?: return initialNodes
        var latestNodes = initialNodes
        repeat(2) {
            val resolved = svc.tryAutoResolveNonBlockingDialog(
                recordedAccount = resolveAndSyncAccount(),
                appHint = appHint
            ) { detectedAccount ->
                val saved = accountHelper?.recordAccount(detectedAccount)
                if (saved != null) onAccountRecorded(saved)
            } ?: return latestNodes

            steps.add(
                PipelineStepPill(
                    index = steps.size + 1,
                    label = resolved.stepSummary,
                    status = StepStatus.DONE
                )
            )
            latestNodes = svc.scanActiveScreenNodes()
        }
        return latestNodes
    }

    private fun resolveAndSyncAccount(): String? {
        val prev = accountHelper?.getRecordedAccount()
        val acct = accountHelper?.getOrDetectSystemAccount()
        if (acct != null && acct != prev) {
            onAccountRecorded(acct)
        }
        return acct
    }

    private suspend fun executeChoiceSelection(choice: LocalIndexDocument): DeviceActionExecutor.ExecutionReport {
        return when (choice.category) {
            "screen_choice" -> {
                val svc = NovaAccessibilityService.instance
                    ?: return DeviceActionExecutor.ExecutionReport(
                        false,
                        "Accessibility Service is needed to tap '${choice.title}' on screen."
                    )
                val clicked = svc.performUiAct("CLICK", choice.docId)
                if (clicked.success) {
                    DeviceActionExecutor.ExecutionReport(true, "Opened '${choice.title}'.", nodesScanned = clicked.updatedNodes)
                } else {
                    val fallback = svc.performFindAndAct(choice.title, "CLICK")
                    DeviceActionExecutor.ExecutionReport(
                        fallback.success,
                        if (fallback.success) "Opened '${choice.title}'." else fallback.message,
                        nodesScanned = fallback.updatedNodes
                    )
                }
            }
            "apps" -> executor.execute(
                ToolCallRequest(
                    toolName = "launch_app",
                    args = mapOf("app_name" to choice.title),
                    safetyTier = SafetyTier.GREEN
                )
            )
            "contacts" -> {
                val number = choice.body.trim()
                if (number.isNotBlank()) {
                    executor.execute(
                        ToolCallRequest(
                            toolName = "place_call",
                            args = mapOf("contact_id" to number, "speakerphone" to "false"),
                            safetyTier = SafetyTier.GREEN
                        )
                    )
                } else {
                    DeviceActionExecutor.ExecutionReport(true, "Selected ${choice.title}.")
                }
            }
            "messages" -> {
                val activeAlert = com.example.processA.NovaNotificationListenerService
                    .getCurrentActiveNotificationsSnapshot(includeGeneralActive = true)
                    .firstOrNull { it.id == choice.docId }
                    ?: com.example.processA.NovaNotificationListenerService
                        .getImportantNotificationsSnapshot()
                        .firstOrNull { it.id == choice.docId }
                val packageFromUri = choice.uriOrRef
                    ?.takeIf { it.startsWith("notif://") }
                    ?.removePrefix("notif://")
                    ?.substringBefore('/')
                    ?.takeIf { it.isNotBlank() }
                val targetPackage = activeAlert?.packageName ?: packageFromUri
                val strippedHeader = choice.title
                    .replace(Regex("^\\[[^\\]]+\\]\\s*"), "")
                    .trim()
                val parsedParts = strippedHeader.split(" • ", limit = 2)
                val displayApp = activeAlert?.appLabel?.takeIf { it.isNotBlank() }
                    ?: parsedParts.getOrNull(0)?.trim().orEmpty().ifBlank { "the app" }
                val displayTitle = activeAlert?.title?.takeIf { it.isNotBlank() }
                    ?: parsedParts.getOrNull(1)?.trim().orEmpty()

                val openedDirect = com.example.processA.NovaNotificationListenerService
                    .openNotificationByIdOrPackage(
                        context = executor.appContext,
                        alertId = choice.docId,
                        packageName = targetPackage
                    )
                val openedViaLauncher = if (!openedDirect && displayApp != "the app") {
                    executor.execute(
                        ToolCallRequest(
                            toolName = "launch_app",
                            args = mapOf("app_name" to displayApp),
                            safetyTier = SafetyTier.GREEN
                        )
                    ).success
                } else {
                    false
                }

                if (openedDirect || openedViaLauncher) {
                    val label = if (displayTitle.isNotBlank() && !displayTitle.equals(displayApp, ignoreCase = true)) {
                        "Opening $displayApp for $displayTitle."
                    } else {
                        "Opening $displayApp."
                    }
                    DeviceActionExecutor.ExecutionReport(true, label)
                } else {
                    val cleanBody = choice.body.replace(" • ", ", ").trim()
                    DeviceActionExecutor.ExecutionReport(
                        false,
                        "I couldn't open $displayApp directly. Here are the details: ${displayTitle.ifBlank { displayApp }} — $cleanBody"
                    )
                }
            }
            "routines" -> {
                executor.execute(
                    ToolCallRequest(
                        toolName = "control_routine_recorder",
                        args = mapOf("action" to "list_steps", "routine_name" to choice.title),
                        safetyTier = SafetyTier.GREEN
                    )
                )
            }
            else -> DeviceActionExecutor.ExecutionReport(
                true,
                "${DeviceActionExecutor.cleanDocumentForSpeech(choice)}."
            )
        }
    }

    private fun evaluateDynamicRedTier(
        call: ToolCallRequest,
        nodes: List<UiNodeSnapshot>
    ): SafetyTier {
        if (call.safetyTier == SafetyTier.RED) return SafetyTier.RED
        if (call.toolName == "ui_act") {
            val action = call.args["action"]?.uppercase()
            if (action == "CLICK") {
                val targetId = call.args["node_id"]
                val label = nodes.firstOrNull { it.id == targetId }?.label.orEmpty()
                if (DeferredToolRegistry.isRedButtonLabel(label)) return SafetyTier.RED
            }
        }
        return call.safetyTier
    }

    private fun formatUiNodes(nodes: List<UiNodeSnapshot>): String =
        nodes.take(30).joinToString("\n") { n ->
            "[${n.id}] ${n.label} (${n.nodeType}, clickable=${n.clickable}, editable=${n.editable})"
        }

    private fun friendlyToolLabel(call: ToolCallRequest): String = when (call.toolName) {
        "local_search", "recall_memory_note" -> "Searched your phone"
        "search_contacts" -> "Checked contacts"
        "read_recent_messages" -> "Checked messages"
        "query_calendar_events" -> "Checked calendar"
        "list_installed_apps" -> "Checked installed apps"
        "launch_app" -> "Opened ${call.args["app_name"] ?: "app"}"
        "get_app_skills" -> "Checked ${call.args["app_name"] ?: "app"} skills"
        "play_in_app" -> "Playing in ${call.args["app_name"] ?: "app"}"
        "search_in_app" -> "Searching in ${call.args["app_name"] ?: "app"}"
        "navigate_in_maps" -> "Starting directions"
        "manage_agentic_memory" -> "Updated memory & habits"
        "get_device_briefing" -> "Prepared personalized briefing"
        "answer_web_question" -> "Answered question"
        "screen_control_app" -> "Controlling ${call.args["app_name"] ?: "app"}"
        "ui_find_and_tap" -> "Screen control: ${call.args["target_label"] ?: "action"}"
        "ui_act" -> when (call.args["action"]?.uppercase()) {
            "TYPE" -> "Typed text"
            "SCROLL_DOWN", "SCROLL_UP" -> "Scrolled screen"
            else -> "Completed screen action"
        }
        else -> "Done"
    }

    suspend fun confirmTierRedAction(confirmation: TierRedConfirmation): String {
        val report = executor.execute(confirmation.pendingToolCall)
        return if (report.success) {
            "Done: ${report.summary}"
        } else {
            "Couldn't finish that: ${report.summary}"
        }
    }

    companion object {
        private const val MAX_UI_LOOP_STEPS = 5
        private const val MAX_CONVERSATION_TURNS = 4

        private val elaborationFollowUpRegex = Regex(
            "^(?:tell\\s+me\\s+more(?:\\s+about\\s+(?:it|that|this))?|more\\s+(?:details|info|information)(?:\\s+on\\s+(?:it|that))?|explain\\s+(?:more|that|it)|elaborate(?:\\s+on\\s+(?:it|that))?|go\\s+on|continue|what\\s+else(?:\\s+about\\s+(?:it|that))?)$",
            RegexOption.IGNORE_CASE
        )

        private val followUpPronounRegex = Regex(
            "\\b(?:that\\s+song|that\\s+track|that\\s+mountain|that\\s+place|that\\s+person|the\\s+song|the\\s+track|it|that|this|they|them|he|she|him|her)\\b",
            RegexOption.IGNORE_CASE
        )

        private val nonTopicHardwareRegex = Regex(
            "\\b(?:bluetooth|wifi|wi-fi|flashlight|torch|volume|brightness|dnd|do\\s+not\\s+disturb|alarm|timer|settings|battery|screen|notifications?)\\b",
            RegexOption.IGNORE_CASE
        )

        private val affirmativeFollowUpSet = setOf(
            "yes", "yeah", "yep", "sure", "ok", "okay", "please", "go ahead",
            "open it", "play it", "read it", "do it", "run it", "open that", "play that", "yes please"
        )

        fun isElaborationFollowUp(query: String): Boolean =
            elaborationFollowUpRegex.matches(query.trim().trimEnd('.', '!', '?'))

        private fun buildNotificationSafetyNetCall(query: String): ToolCallRequest? {
            val lower = query.trim().lowercase()
            if (lower.isEmpty()) return null
            if (!Regex("\\bnotifications?\\b").containsMatchIn(lower)) return null
            // Exclude settings/muting/briefing/opening-shade commands
            if (Regex("\\b(?:briefing|recap|settings?|permission|mute|silence|disable|turn\\s+off|turn\\s+on|enable|shade|panel|pull\\s+down|open\\s+notification)\\b")
                    .containsMatchIn(lower)
            ) {
                return null
            }
            if (Regex("\\b(?:mark|clear)\\b.*\\bread\\b").containsMatchIn(lower)) {
                return ToolCallRequest(
                    toolName = "read_recent_messages",
                    args = mapOf("contact_id" to "all", "action" to "mark_all_read"),
                    safetyTier = SafetyTier.GREEN
                )
            }
            val asksCurrent = lower.contains("current") || lower.contains("active") || lower.contains("live") ||
                lower.contains("right now") || lower.contains("not dismissed") || lower.contains("undismissed") ||
                lower.contains("on my phone") || lower.contains("on the phone")
            val asksMostImportant = lower.contains("most important") || lower.contains("most urgent") ||
                lower.contains("highest priority") || lower.contains("top important")
            val isSingular = Regex("\\bnotification\\b").containsMatchIn(lower) &&
                !Regex("\\bnotifications\\b").containsMatchIn(lower)
            val scope = when {
                asksCurrent && (asksMostImportant || lower.contains("important") || lower.contains("urgent")) ->
                    "current_important"
                asksCurrent -> "current"
                asksMostImportant && !lower.contains("week") && !lower.contains("history") && !lower.contains("old") ->
                    "current_important"
                lower.contains("unread") -> "unread"
                lower.contains("24") -> "24h"
                lower.contains("week") -> "week"
                lower.contains("old") || lower.contains("history") || lower.contains("archive") -> "archive"
                lower.contains("important") && !lower.contains("today") -> "important"
                lower.contains("today") -> "today"
                else -> "current_or_today"
            }
            val limit = if (isSingular && asksMostImportant) "1" else "6"
            return ToolCallRequest(
                toolName = "read_recent_messages",
                args = mapOf("contact_id" to "all", "scope" to scope, "limit" to limit),
                safetyTier = SafetyTier.GREEN
            )
        }

        private fun buildRoutineInfoSafetyNetCall(query: String): ToolCallRequest? {
            val lower = query.trim().lowercase()
            if (lower.isEmpty()) return null
            if (!Regex("\\broutines?\\b").containsMatchIn(lower)) return null
            // Exclude recording/running/deleting/stopping/floating-control commands
            if (Regex("\\b(?:record|recording|stop|start|run|execute|trigger|play|delete|remove|floating|overlay|bar|control)\\b")
                    .containsMatchIn(lower)
            ) {
                return null
            }
            val asksSteps = Regex("\\b(?:steps?|step[-\\s]by[-\\s]step|breakdown|details|detail|inside|does\\s+.*do|explain|describe)\\b")
                .containsMatchIn(lower)
            val specificTarget = Regex("\\b(morning|focus|bedtime|sync|phone\\s+info\\s+sync)\\b")
                .find(lower)?.groupValues?.getOrNull(1)
            val action = if (asksSteps || specificTarget != null) "list_steps" else "list"
            val args = buildMap {
                put("action", action)
                if (!specificTarget.isNullOrBlank()) put("routine_name", specificTarget)
            }
            return ToolCallRequest(
                toolName = "control_routine_recorder",
                args = args,
                safetyTier = SafetyTier.GREEN
            )
        }

        private fun isBriefingSafetyNetQuery(query: String): Boolean {
            val lower = query.trim().lowercase()
            if (lower.isEmpty()) return false
            val explicitlySearchOrPlay = lower.startsWith("search ") ||
                lower.startsWith("play ") ||
                lower.startsWith("open youtube") ||
                lower.contains("on youtube") ||
                lower.contains("in youtube")
            if (explicitlySearchOrPlay) return false
            return Regex("\\b(?:briefing|daily\\s+recap|evening\\s+recap|tonight'?s?\\s+(?:briefing|recap|summary))\\b")
                .containsMatchIn(lower)
        }

        fun rewriteFollowUpWithTopic(query: String, topic: String?): String? {
            if (topic.isNullOrBlank()) return null
            val clean = query.trim()
            if (clean.isEmpty() || clean.length > 90) return null
            if (nonTopicHardwareRegex.containsMatchIn(clean)) return null
            val wordCount = clean.split(Regex("\\s+")).count { it.isNotBlank() }
            if (wordCount > 10) return null
            if (!followUpPronounRegex.containsMatchIn(clean)) return null
            val replaced = followUpPronounRegex.replaceFirst(clean, topic).trim()
            return replaced.takeIf { !it.equals(clean, ignoreCase = true) }
        }

        internal fun extractTopicFromQueryOrResponse(
            userQuery: String,
            responseText: String,
            previousTopic: String?
        ): String? {
            val cleanQuery = userQuery.trim().trimEnd('?', '.', '!')
            // Do not overwrite good topic with pronoun follow-up or pure device toggle
            if (cleanQuery.isBlank() || followUpPronounRegex.containsMatchIn(cleanQuery)) {
                return previousTopic
            }
            if (nonTopicHardwareRegex.containsMatchIn(cleanQuery)) {
                return previousTopic
            }
            if (Regex("^(?:play|listen\\s+to|search\\s+for)\\s+(.+)$", RegexOption.IGNORE_CASE).find(cleanQuery) != null) {
                val target = cleanQuery
                    .replace(Regex("^(?:play|listen\\s+to|search\\s+for)\\s+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s+(?:on|in)\\s+(?:youtube(?:\\s+music)?|spotify|browser|chrome)$", RegexOption.IGNORE_CASE), "")
                    .trim()
                if (target.length >= 2) return target
            }
            if (WebKnowledgeResolver.isGeneralKnowledgeQuestion(cleanQuery)) {
                // Extract clean subject noun phrase from response start if available (e.g. "Mount Apo is...")
                val responseSubject = Regex("^([A-Z][\\w'’-]+(?:\\s+[A-Z0-9][\\w'’-]+){0,4})\\s+(?:is|are|was|were)\\b")
                    .find(responseText.trim())
                    ?.groupValues?.getOrNull(1)
                    ?.trim()
                if (!responseSubject.isNullOrBlank() && responseSubject.length in 3..60) {
                    return responseSubject
                }
                return WebKnowledgeResolver.extractSearchTopic(cleanQuery)
            }
            return previousTopic
        }

        /**
         * Formats Tier-1 regex, multi-stage planner, and learned-process tool summaries into
         * clean, punctuated natural language suitable for both UI display and TTS speech.
         */
        fun formatTier1Responses(parts: List<String>): String {
            val cleaned = parts.mapNotNull { raw ->
                val normalized = raw
                    .removePrefix("NEEDS_LLM_LOOP:")
                    .removePrefix("TIER_RED_BLOCKED:")
                    .replace("\r\n", "\n")
                    .replace(" • ", ", ")
                    .replace(" -> ", " to ")
                    .lines()
                    .joinToString("\n") { line ->
                        line.replace(Regex("[ \\t]+"), " ")
                            .replace(Regex("(?<!\\.)\\.\\.(?!\\.)"), ".")
                            .replace("?.", "?")
                            .replace("!.", "!")
                            .trim()
                    }
                    .replace(Regex("\\n{3,}"), "\n\n")
                    .trim()
                if (normalized.isEmpty()) return@mapNotNull null
                val lastChar = normalized.last()
                if (lastChar == '.' || lastChar == '!' || lastChar == '?' || lastChar == ':') {
                    normalized
                } else {
                    "$normalized."
                }
            }
            val separator = if (cleaned.any { it.contains("\n") }) "\n\n" else " "
            return cleaned.joinToString(separator).ifBlank { "Done." }
        }

        fun shouldAwaitFollowUp(
            outcome: TurnExecutionOutcome,
            hasPendingChoices: Boolean = false
        ): Boolean {
            if (outcome.requiresModelSetup || outcome.requiresAccessibilitySetup) return false
            if (outcome.pendingConfirmation != null || hasPendingChoices) return true
            if (outcome.orbMode == OrbVisualMode.LISTENING) return true

            val text = outcome.responseText.trim()
            if (text.isEmpty()) return false
            if (text.endsWith("?")) return true
            val lastLine = text.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (lastLine.contains("?")) return true

            val lower = text.lowercase()
            return lower.contains("which one would you like") ||
                lower.contains("would you like me to") ||
                lower.contains("do you want me to") ||
                lower.contains("please tell me") ||
                lower.contains("tell me what you'd like") ||
                lower.contains("tell me which ") ||
                lower.contains("who would you like") ||
                lower.contains("what would you like")
        }

        fun matchPendingChoice(query: String, choices: List<LocalIndexDocument>): LocalIndexDocument? {
            if (choices.isEmpty()) return null
            val q = query.trim().lowercase().trimEnd('.', '!', '?')
            if (choices.size == 1 && q in affirmativeFollowUpSet) {
                return choices.first()
            }
            val cleanOrdinal = q
                .replace(Regex("^(?:please\\s+)?(?:open|play|read|show|check|select|choose|pick|tap|click|go\\s+with|do|number|option)\\s+"), "")
                .replace(Regex("^(?:the\\s+|number\\s+|option\\s+)"), "")
                .replace(Regex("\\s+(?:one|option|result|choice|song|video|item|notification|alert|message|email)$"), "")
                .trim()
            val index = when {
                q in setOf("1", "first", "one", "the first", "the first one", "number 1", "number one", "option 1") ||
                    cleanOrdinal in setOf("1", "1st", "first", "one") ||
                    Regex("\\b(?:1st|first)\\b").containsMatchIn(q) -> 0
                q in setOf("2", "second", "two", "the second", "the second one", "number 2", "number two", "option 2") ||
                    cleanOrdinal in setOf("2", "2nd", "second", "two") ||
                    Regex("\\b(?:2nd|second)\\b").containsMatchIn(q) -> 1
                q in setOf("3", "third", "three", "the third", "the third one", "number 3", "number three", "option 3") ||
                    cleanOrdinal in setOf("3", "3rd", "third", "three") ||
                    Regex("\\b(?:3rd|third)\\b").containsMatchIn(q) -> 2
                q in setOf("4", "fourth", "four", "the fourth", "the fourth one", "number 4", "number four", "option 4") ||
                    cleanOrdinal in setOf("4", "4th", "fourth", "four") ||
                    Regex("\\b(?:4th|fourth)\\b").containsMatchIn(q) -> 3
                q in setOf("5", "fifth", "five", "the fifth", "the fifth one", "number 5", "number five", "option 5") ||
                    cleanOrdinal in setOf("5", "5th", "fifth", "five") ||
                    Regex("\\b(?:5th|fifth)\\b").containsMatchIn(q) -> 4
                q in setOf("6", "sixth", "six", "the sixth", "the sixth one", "number 6", "number six", "option 6") ||
                    cleanOrdinal in setOf("6", "6th", "sixth", "six") ||
                    Regex("\\b(?:6th|sixth)\\b").containsMatchIn(q) -> 5
                cleanOrdinal == "last" || Regex("\\bthe\\s+last(?:\\s+one)?\\b").containsMatchIn(q) ->
                    choices.lastIndex
                else -> -1
            }
            if (index in choices.indices) return choices[index]

            val isFreshUnrelatedCommand = Regex("^(?:what|who|where|when|why|how|turn|set|remind|search|call|send|play|tell\\s+me)\\b")
                .containsMatchIn(q)
            if (!isFreshUnrelatedCommand) {
                val cleanTarget = q
                    .replace(Regex("^(?:please\\s+)?(?:open|show|read|select|choose|pick|tap|click|launch|check|view|go\\s+to|go\\s+with)\\s+"), "")
                    .replace(Regex("^(?:the|my|that|this)\\s+"), "")
                    .replace(Regex("\\s+(?:one|notification|alert|message|email|mail|item|app|option|choice)$"), "")
                    .trim()
                if (cleanTarget.length >= 2 && cleanTarget.length <= 45) {
                    choices.firstOrNull {
                        it.title.removePrefix("Memory: ").lowercase().contains(cleanTarget)
                    }?.let { return it }
                    choices.firstOrNull {
                        it.body.lowercase().contains(cleanTarget)
                    }?.let { return it }
                    val tokens = cleanTarget.split(Regex("\\s+")).filter { it.length >= 2 }
                    if (tokens.size in 2..4) {
                        choices.firstOrNull { doc ->
                            val hay = "${doc.title} ${doc.body}".lowercase()
                            tokens.all { tok -> hay.contains(tok) }
                        }?.let { return it }
                    }
                }
            }
            return null
        }
    }
}
