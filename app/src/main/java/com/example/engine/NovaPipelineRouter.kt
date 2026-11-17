package com.example.engine

import com.example.data.DeviceAccountHelper
import com.example.data.DeviceActionExecutor
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
 * Tier 0 (Input Normalizer) -> Follow-Up Choice Resolver -> Tier 1 (Regex Fast-Path <5ms) ->
 * Tier 1.5 (EmbeddingGemma 2 Semantic Lookup) -> Tier 2 (Process B :llm Gemma 4 E2B) ->
 * Tier 3 (Closed-loop Tool & Autonomous UI Loop with Pop-Up/Account/Dispatch Auto-Resolution & Tier Red Confirmation).
 */
class NovaPipelineRouter(
    private val llmBridge: LlmProcessBridge,
    private val executor: DeviceActionExecutor,
    private val embeddingRuntime: EmbeddingGemmaRuntime? = null,
    private val accountHelper: DeviceAccountHelper? = null,
    private val onAccountRecorded: (String) -> Unit = {}
) {

    @Volatile
    private var pendingChoices: List<LocalIndexDocument> = emptyList()

    fun getPendingChoices(): List<LocalIndexDocument> = pendingChoices

    suspend fun routeAndExecute(
        rawQuery: String,
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

        // Follow-up disambiguation: if previous search offered choices and user picks one ("first", "2", or title)
        if (pendingChoices.isNotEmpty()) {
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
                val totalMs = (System.currentTimeMillis() - totalStart).coerceAtLeast(1L)
                onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "DONE")
                return TurnExecutionOutcome(
                    normalizedQuery = normalized.cleanedFull,
                    routingTier = RoutingTier.TIER_1_REGEX,
                    selectedSkills = listOf("app-knowledge-controller"),
                    latencyMs = totalMs,
                    thinkingEnabled = false,
                    steps = steps.toList(),
                    responseText = report.summary,
                    orbMode = OrbVisualMode.FAST_PATH_FLASH
                )
            } else {
                // User moved on to a fresh command
                pendingChoices = emptyList()
            }
        }

        // TIER 1: Deterministic Regex Fast-Path (< 5 ms, Process A)
        if (!normalized.hasConditionalLogic) {
            val wholeMatch = RegexFastPathEngine.match(normalized.cleanedFull)
            val fastMatches = if (wholeMatch != null) {
                listOf(wholeMatch)
            } else {
                val perPart = normalized.subCommands.mapNotNull { cmd -> RegexFastPathEngine.match(cmd) }
                if (perPart.size == normalized.subCommands.size) perPart else emptyList()
            }
            if (fastMatches.isNotEmpty()) {
                val reports = mutableListOf<String>()
                fastMatches.forEachIndexed { idx, match ->
                    val report = executor.execute(match.toolCall)
                    if (report.ftsResults.size > 1) {
                        pendingChoices = report.ftsResults
                    }
                    reports.add(report.summary)
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
                onStepUpdate(steps.toList(), OrbVisualMode.FAST_PATH_FLASH, "DONE")
                return TurnExecutionOutcome(
                    normalizedQuery = normalized.cleanedFull,
                    routingTier = RoutingTier.TIER_1_REGEX,
                    selectedSkills = fastMatches.map { it.skillId }.distinct(),
                    latencyMs = totalMs,
                    thinkingEnabled = false,
                    steps = steps.toList(),
                    responseText = reports.joinToString(" • "),
                    toolCalls = fastMatches.map { it.toolCall },
                    orbMode = OrbVisualMode.FAST_PATH_FLASH
                )
            }
        }

        // TIER 1.5: Semantic Lookup in Process A (EmbeddingGemma 2 when present)
        val semanticStart = System.currentTimeMillis()
        val semantic = SemanticLookupEngine.lookupWithRuntime(normalized.cleanedFull, embeddingRuntime)
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
                normalizedQuery = normalized.cleanedFull,
                routingTier = RoutingTier.TIER_1_5_SEMANTIC,
                selectedSkills = candidateIds,
                latencyMs = elapsed,
                thinkingEnabled = false,
                steps = steps.toList(),
                responseText = "Please download or import the local AI model in Settings before using AI reasoning.",
                orbMode = OrbVisualMode.ERROR_ABORTED,
                requiresModelSetup = true
            )
        }

        val usesThinking = semantic.candidateSkills.any { it.requiresThinking }
        onStepUpdate(
            steps.toList(),
            if (usesThinking) OrbVisualMode.THINKING else OrbVisualMode.LISTENING,
            if (usesThinking) "THINKING..." else "WORKING..."
        )

        val currentAccount = resolveAndSyncAccount()
        val installedAppsCtx = executor.buildInstalledAppsContext(normalized.cleanedFull)

        // TIER 2: Process B (:llm) Gemma 4 E2B Inference (fresh conversation per top-level user request)
        var llmResult = try {
            llmBridge.inferTurn(
                query = normalized.cleanedFull,
                candidateSkills = candidateIds,
                fewShots = semantic.topFewShotExamples,
                installedAppsContext = installedAppsCtx,
                recordedAccount = currentAccount,
                resetConversation = true
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
        }

        steps.add(
            PipelineStepPill(
                index = steps.size + 1,
                label = "Planned action",
                status = StepStatus.DONE,
                latencyMs = llmResult.inferenceMs
            )
        )

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

            for (call in currentCalls) {
                executedCalls.add(call)
                when (call.toolName) {
                    "ui_open_and_scan" -> {
                        val appName = call.args["app_name"] ?: "App"
                        onStepUpdate(
                            steps + PipelineStepPill(steps.size + 1, "Opening $appName...", StepStatus.ACTIVE),
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
                        steps.add(
                            PipelineStepPill(
                                index = steps.size + 1,
                                label = if (report.success) "Opened $appName" else "Screen control off",
                                status = if (report.success) StepStatus.DONE else StepStatus.BLOCKED
                            )
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
                        steps.add(
                            PipelineStepPill(
                                index = steps.size + 1,
                                label = "Read screen",
                                status = if (report.success) StepStatus.DONE else StepStatus.BLOCKED
                            )
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
                                .firstOrNull { it.toolName == "ui_open_and_scan" }
                                ?.args?.get("app_name") ?: "Current App"

                            val liveShot = lastScreenshotBytes ?: NovaAccessibilityService.instance?.captureScreenshotJpeg()

                            steps.add(
                                PipelineStepPill(
                                    index = steps.size + 1,
                                    label = "Needs approval: $targetLabel",
                                    status = StepStatus.BLOCKED
                                )
                            )
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
                        if (report.ftsResults.size > 1) {
                            pendingChoices = report.ftsResults
                        }
                        outputs.add(report.summary)
                        if (call.toolName == "ui_act") {
                            lastScannedNodes = autoHandleIntermediateDialogs(
                                appHint = executedCalls.firstOrNull { it.toolName == "ui_open_and_scan" }?.args?.get("app_name"),
                                steps = steps,
                                initialNodes = report.nodesScanned
                            )
                            if (report.success || lastScannedNodes.isNotEmpty()) {
                                needNextUiLoopTurn = true
                                nextUiContext = formatUiNodes(lastScannedNodes)
                                if (lastScannedNodes.size <= 2) {
                                    nextVisionBytes = NovaAccessibilityService.instance?.captureScreenshotJpeg()
                                }
                            }
                        }
                        steps.add(
                            PipelineStepPill(
                                index = steps.size + 1,
                                label = friendlyToolLabel(call),
                                status = if (report.success) StepStatus.DONE else StepStatus.BLOCKED
                            )
                        )
                    }
                }
            }

            if (pendingRedConfirmation != null || !needNextUiLoopTurn) break

            onStepUpdate(steps.toList(), OrbVisualMode.UI_NAVIGATING, "CHECKING SCREEN...")
            llmResult = llmBridge.inferTurn(
                query = normalized.cleanedFull,
                candidateSkills = candidateIds,
                fewShots = semantic.topFewShotExamples,
                imageBytes = nextVisionBytes,
                uiTreeContext = nextUiContext,
                installedAppsContext = installedAppsCtx,
                recordedAccount = resolveAndSyncAccount(),
                resetConversation = false
            )
            currentCalls = llmResult.toolCalls
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

        return TurnExecutionOutcome(
            normalizedQuery = normalized.cleanedFull,
            routingTier = finalTier,
            selectedSkills = listOf(llmResult.selectedSkillId),
            latencyMs = totalMs,
            thinkingEnabled = llmResult.thinkingEnabled,
            steps = steps.toList(),
            responseText = responseText,
            toolCalls = executedCalls,
            pendingConfirmation = pendingRedConfirmation,
            orbMode = finalOrb
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
            "messages" -> DeviceActionExecutor.ExecutionReport(
                true,
                "Message from ${choice.title}: ${choice.body}"
            )
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
        nodes.take(20).joinToString("\n") { n ->
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

        fun matchPendingChoice(query: String, choices: List<LocalIndexDocument>): LocalIndexDocument? {
            if (choices.isEmpty()) return null
            val q = query.trim().lowercase()
            val index = when {
                q in setOf("1", "first", "one", "the first", "the first one", "number 1", "number one", "option 1") -> 0
                q in setOf("2", "second", "two", "the second", "the second one", "number 2", "number two", "option 2") -> 1
                q in setOf("3", "third", "three", "the third", "the third one", "number 3", "number three", "option 3") -> 2
                q in setOf("4", "fourth", "four", "the fourth", "the fourth one", "number 4", "number four", "option 4") -> 3
                else -> -1
            }
            if (index in choices.indices) return choices[index]

            if (q.length >= 2) {
                choices.firstOrNull { it.title.removePrefix("Memory: ").lowercase().contains(q) }?.let { return it }
            }
            return null
        }
    }
}
