package com.example.processB

import android.os.Process
import com.example.data.SafetyTier
import com.example.data.ToolCallRequest
import com.example.engine.DeferredToolRegistry
import com.example.engine.SkillCatalog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Isolated LiteRT-LM Runtime Wrapper hosted inside Process B (:llm).
 * - Uses `com.google.ai.edge.litertlm.Engine` and `Conversation` strictly on a single-thread
 *   background dispatcher.
 * - Supports GPU with CPU fallback, multimodal `Content.ImageBytes` vision input,
 *   and per-turn `ThinkingConfig(enableThinking = requiresThinking)`.
 * - Refuses to run inference when no `.litertlm` model file is present.
 */
class LiteRtLmRuntimeWrapper {

    enum class EngineState { MODEL_MISSING, WARMING_UP, READY, BUSY, ERROR }

    data class LlmTurnOutput(
        val selectedSkillId: String,
        val thinkingEnabled: Boolean,
        val toolCalls: List<ToolCallRequest>,
        val assistantReply: String,
        val inferenceMs: Long,
        val pegRetried: Boolean,
        val processPid: Int
    )

    private val engineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val sessionMutex = Mutex()
    private val bargeInCancelled = AtomicBoolean(false)

    val state = AtomicReference(EngineState.MODEL_MISSING)
    val lastError = AtomicReference<String?>(null)
    val loadedModelPath = AtomicReference<String?>(null)
    val activeBackend = AtomicReference("UNLOADED")

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var conversationIsConversational: Boolean = false

    suspend fun warmup(
        modelPath: String?,
        cacheDir: String?,
        nativeLibDir: String? = null,
        preferNpu: Boolean = false
    ): Boolean = withContext(engineDispatcher) {
        sessionMutex.withLock {
            if (modelPath.isNullOrBlank()) {
                releaseInternal()
                state.set(EngineState.MODEL_MISSING)
                activeBackend.set("UNLOADED")
                lastError.set("No .litertlm model downloaded or imported.")
                return@withLock false
            }

            val modelFile = File(modelPath)
            if (!modelFile.exists() || !modelFile.isFile || modelFile.length() <= 1024L) {
                releaseInternal()
                state.set(EngineState.MODEL_MISSING)
                activeBackend.set("UNLOADED")
                lastError.set("Model file missing or invalid at $modelPath")
                return@withLock false
            }

            if (state.get() == EngineState.READY && loadedModelPath.get() == modelPath && engine != null) {
                return@withLock true
            }

            releaseInternal()
            state.set(EngineState.WARMING_UP)
            lastError.set(null)

            return@withLock try {
                Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
                val (initializedEngine, backendLabel) = initializeWithFallbackBackend(
                    modelPath = modelFile.absolutePath,
                    cacheDir = cacheDir,
                    nativeLibDir = nativeLibDir,
                    preferNpu = preferNpu
                )
                engine = initializedEngine
                activeBackend.set(backendLabel)
                conversationIsConversational = false
                conversation = createFreshConversation(initializedEngine, conversational = false)
                loadedModelPath.set(modelFile.absolutePath)
                state.set(EngineState.READY)
                true
            } catch (ex: Exception) {
                releaseInternal()
                state.set(EngineState.ERROR)
                activeBackend.set("UNLOADED")
                lastError.set(ex.message ?: "Failed to initialize LiteRT-LM engine")
                false
            }
        }
    }

    private fun createFreshConversation(eng: Engine, conversational: Boolean): Conversation =
        eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(
                    if (conversational) CONVERSATIONAL_SYSTEM_PROMPT else SYSTEM_PROMPT
                ),
                samplerConfig = if (conversational) {
                    SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
                } else {
                    SamplerConfig(topK = 40, topP = 0.95, temperature = 0.35)
                },
                thinkingConfig = ThinkingConfig(enableThinking = false)
            )
        )

    private fun initializeWithFallbackBackend(
        modelPath: String,
        cacheDir: String?,
        nativeLibDir: String?,
        preferNpu: Boolean
    ): Pair<Engine, String> {
        val supportsVision = isLikelyVisionModel(modelPath)
        val candidates = buildList {
            if (preferNpu && !nativeLibDir.isNullOrBlank()) {
                if (supportsVision) {
                    add(Triple(Backend.NPU(nativeLibDir), Backend.GPU(), "NPU"))
                }
                add(Triple(Backend.NPU(nativeLibDir), null, "NPU"))
            }
            if (supportsVision) {
                add(Triple(Backend.GPU(), Backend.GPU(), "GPU"))
            }
            add(Triple(Backend.GPU(), null, "GPU"))
            if (supportsVision) {
                add(Triple(Backend.CPU(), Backend.CPU(), "CPU"))
            }
            add(Triple(Backend.CPU(), null, "CPU"))
        }

        var lastException: Exception? = null
        for ((mainBackend, visionBackend, label) in candidates) {
            // Never reuse or call close() on an Engine whose initialize() threw
            val candidate = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = mainBackend,
                    visionBackend = visionBackend,
                    maxNumTokens = DEFAULT_MAX_NUM_TOKENS,
                    cacheDir = cacheDir
                )
            )
            try {
                candidate.initialize()
                return candidate to label
            } catch (ex: Exception) {
                lastException = ex
            }
        }
        throw lastException ?: IllegalStateException("All LiteRT-LM backends failed to initialize")
    }

    fun preemptBargeIn() {
        bargeInCancelled.set(true)
        runCatching { conversation?.cancelProcess() }
    }

    suspend fun unload() = withContext(engineDispatcher) {
        sessionMutex.withLock {
            releaseInternal()
            state.set(EngineState.MODEL_MISSING)
        }
    }

    suspend fun executeTurn(
        query: String,
        candidateSkillIds: List<String>,
        fewShotHints: List<String>,
        imageBytes: ByteArray? = null,
        uiTreeContext: String? = null,
        installedAppsContext: String? = null,
        recordedAccount: String? = null,
        memoryContext: String? = null,
        personalityProfile: String? = null,
        resetConversation: Boolean = false,
        allowThinking: Boolean = true
    ): LlmTurnOutput = withContext(engineDispatcher) {
        bargeInCancelled.set(false)
        val startMs = System.currentTimeMillis()

        sessionMutex.withLock {
            if (bargeInCancelled.get()) {
                throw CancellationException("Barge-in aborted turn before lock")
            }

            val currentEngine = engine
            if (state.get() != EngineState.READY || currentEngine == null) {
                throw IllegalStateException(
                    "LLM model is not loaded. Open Settings to download or import a Gemma 4 .litertlm model."
                )
            }

            val disclosedSkills = candidateSkillIds
                .mapNotNull { SkillCatalog.findById(it) }
                .filter { it.enabled }
            val primarySkill = disclosedSkills.firstOrNull() ?: SkillCatalog.enabledSkills.first()
            val conversationalMode = isPureConversationalTurn(
                query = query,
                candidateSkillIds = candidateSkillIds,
                uiTreeContext = uiTreeContext,
                hasImage = imageBytes != null && imageBytes.isNotEmpty()
            )

            if (resetConversation || conversation == null || conversationIsConversational != conversationalMode) {
                runCatching { conversation?.close() }
                conversationIsConversational = conversationalMode
                conversation = createFreshConversation(currentEngine, conversational = conversationalMode)
            }
            val activeConv = conversation
                ?: throw IllegalStateException("Unable to open LLM conversation session.")

            state.set(EngineState.BUSY)
            try {
                val requiresThinking = !conversationalMode && allowThinking && (
                    disclosedSkills.any { it.requiresThinking } || !uiTreeContext.isNullOrBlank()
                    )

                val promptText = if (conversationalMode) {
                    buildConversationalPrompt(
                        query = query,
                        memoryContext = memoryContext,
                        personalityProfile = personalityProfile
                    )
                } else {
                    buildProgressivePrompt(
                        query = query,
                        disclosedSkills = disclosedSkills,
                        fewShotHints = fewShotHints,
                        uiTreeContext = uiTreeContext,
                        installedAppsContext = installedAppsContext,
                        recordedAccount = recordedAccount,
                        memoryContext = memoryContext,
                        personalityProfile = personalityProfile
                    )
                }
                val contents = if (imageBytes != null && imageBytes.isNotEmpty()) {
                    Contents.of(Content.ImageBytes(imageBytes), Content.Text(promptText))
                } else {
                    Contents.of(Content.Text(promptText))
                }

                val firstRaw = collectReply(activeConv, contents, requiresThinking)
                if (bargeInCancelled.get()) throw CancellationException("Barge-in interrupted :llm decode")

                if (conversationalMode) {
                    val parsedIfJson = if (looksLikeJsonPayload(firstRaw)) parseStructuredOutput(firstRaw) else null
                    val replyText = sanitizeAssistantReply(
                        parsedReply = parsedIfJson?.second ?: cleanProseReply(firstRaw),
                        rawFallback = firstRaw,
                        hasToolCalls = false
                    )
                    val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                    return@withLock LlmTurnOutput(
                        selectedSkillId = primarySkill.id,
                        thinkingEnabled = false,
                        toolCalls = emptyList(),
                        assistantReply = replyText,
                        inferenceMs = elapsed,
                        pegRetried = false,
                        processPid = Process.myPid()
                    )
                }

                var pegRetried = false
                var latestRaw = firstRaw
                var parsed = parseStructuredOutput(firstRaw)

                // Only trigger PEG JSON repair if the model tried structured/broken output and failed.
                // If it already replied in clean plain English, preserve it without a noisy repair loop.
                if (parsed == null && !looksLikeCleanProseReply(firstRaw)) {
                    pegRetried = true
                    val repairPrompt =
                        "Output ONLY valid double-quoted JSON with tool calls or a plain reply for \"$query\". " +
                            "Format: {\"calls\":[{\"tool\":\"tool_name\",\"args\":{\"key\":\"value\"}}],\"reply\":\"\"}"
                    val retryRaw = collectReply(activeConv, Contents.of(repairPrompt), enableThinking = false)
                    latestRaw = retryRaw
                    parsed = parseStructuredOutput(retryRaw)
                    if (parsed == null && looksLikeCleanProseReply(retryRaw)) {
                        parsed = emptyList<ToolCallRequest>() to cleanProseReply(retryRaw)
                    }
                } else if (parsed == null && looksLikeCleanProseReply(firstRaw)) {
                    parsed = emptyList<ToolCallRequest>() to cleanProseReply(firstRaw)
                }

                val calls = parsed?.first.orEmpty()
                val safeReply = sanitizeAssistantReply(
                    parsedReply = parsed?.second,
                    rawFallback = latestRaw,
                    hasToolCalls = calls.isNotEmpty()
                )

                val elapsed = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                LlmTurnOutput(
                    selectedSkillId = primarySkill.id,
                    thinkingEnabled = requiresThinking,
                    toolCalls = calls,
                    assistantReply = safeReply,
                    inferenceMs = elapsed,
                    pegRetried = pegRetried,
                    processPid = Process.myPid()
                )
            } finally {
                if (state.get() == EngineState.BUSY) {
                    state.set(EngineState.READY)
                }
            }
        }
    }

    private suspend fun collectReply(
        conv: Conversation,
        contents: Contents,
        enableThinking: Boolean
    ): String {
        val sb = StringBuilder()
        val thinkingCfg = ThinkingConfig(
            enableThinking = enableThinking,
            thinkingTokenBudget = if (enableThinking) 512 else 0
        )
        try {
            withTimeout(DECODE_TIMEOUT_MS) {
                conv.sendMessageAsync(contents, thinkingConfig = thinkingCfg).collect { chunk ->
                    if (bargeInCancelled.get()) {
                        runCatching { conv.cancelProcess() }
                        throw CancellationException("Barge-in interrupted stream")
                    }
                    val piece = extractChunkText(chunk)
                    if (piece.isNotEmpty()) {
                        sb.append(piece)
                    }
                }
            }
        } catch (ex: TimeoutCancellationException) {
            runCatching { conv.cancelProcess() }
            if (sb.isNotEmpty()) {
                return sb.toString()
            }
            throw IllegalStateException("Local AI took too long to respond. Please try again.")
        }
        return sb.toString()
    }

    private fun extractChunkText(chunk: com.google.ai.edge.litertlm.Message): String {
        val fromContents = runCatching {
            chunk.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }
        }.getOrNull()
        val raw = if (!fromContents.isNullOrEmpty()) fromContents else chunk.toString()
        return raw.replace('\u2581', ' ')
    }

    private fun buildProgressivePrompt(
        query: String,
        disclosedSkills: List<com.example.data.SkillSpec>,
        fewShotHints: List<String>,
        uiTreeContext: String?,
        installedAppsContext: String?,
        recordedAccount: String?,
        memoryContext: String?,
        personalityProfile: String?
    ): String = Companion.buildProgressivePrompt(
        query = query,
        disclosedSkills = disclosedSkills,
        fewShotHints = fewShotHints,
        uiTreeContext = uiTreeContext,
        installedAppsContext = installedAppsContext,
        recordedAccount = recordedAccount,
        memoryContext = memoryContext,
        personalityProfile = personalityProfile
    )

    private fun parseStructuredOutput(raw: String): Pair<List<ToolCallRequest>, String>? =
        parseModelTurnText(raw)

    private fun releaseInternal() {
        runCatching { conversation?.cancelProcess() }
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
        loadedModelPath.set(null)
        activeBackend.set("UNLOADED")
    }

    fun shutdown() {
        releaseInternal()
        engineDispatcher.close()
    }

    companion object {
        private const val DECODE_TIMEOUT_MS = 40_000L
        private const val DEFAULT_MAX_NUM_TOKENS = 4096

        fun isLikelyVisionModel(modelPath: String): Boolean {
            val lower = modelPath.lowercase()
            return lower.contains("gemma-4") ||
                lower.contains("gemma4") ||
                lower.contains("gemma-3n") ||
                lower.contains("gemma3n") ||
                lower.contains("-vl") ||
                lower.contains("_vl") ||
                lower.contains("vision") ||
                lower.contains("multimodal")
        }

        fun isPureConversationalTurn(
            query: String,
            candidateSkillIds: List<String>,
            uiTreeContext: String? = null,
            hasImage: Boolean = false
        ): Boolean {
            if (hasImage || !uiTreeContext.isNullOrBlank()) return false
            val clean = query.trim()
            if (clean.isEmpty()) return false
            val actionVerbPrefix = Regex(
                "^(?:open|launch|start|close|turn|toggle|set|play|pause|stop|skip|call|dial|text|send|message|email|remind|schedule|search|find|navigate|drive|take|capture|scan|read\\s+my|check\\s+my|list\\s+my|run)\\b",
                RegexOption.IGNORE_CASE
            )
            if (actionVerbPrefix.containsMatchIn(clean)) return false
            val isChat = com.example.engine.WebKnowledgeResolver.isConversationalOrPersonalChat(clean)
            if (isChat) return true
            return candidateSkillIds.firstOrNull() == "general-knowledge-qa"
        }

        fun buildConversationalPrompt(
            query: String,
            memoryContext: String? = null,
            personalityProfile: String? = null
        ): String {
            val personaLine = personalityProfile?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { "\nTone: $it" } ?: ""
            val memLine = memoryContext?.trim()?.takeIf { it.isNotEmpty() }
                ?.take(360)
                ?.let { "\nContext:\n$it" } ?: ""
            return "User: $query$personaLine$memLine\nReply naturally in 1 to 3 complete plain-English sentences (no JSON, no markdown, no underscores)."
        }

        private const val CONVERSATIONAL_SYSTEM_PROMPT =
            "You are Nova, a warm, witty, helpful on-device voice assistant. " +
                "Answer conversationally in 1 to 3 complete, natural spoken English sentences. " +
                "Always finish your thought completely (including punchlines for jokes). " +
                "If asked personal questions like your favorite food or hobbies, give a playful, charming in-character answer. " +
                "Never output JSON, markdown formatting, URLs, or underscores between words."

        private const val SYSTEM_PROMPT =
            """You are Nova, a hands-free on-device Android voice assistant with full control over phone settings, installed apps, media playback, search, calls, messages, and Accessibility screen UI automation.

CRITICAL ACCESSIBILITY CONTEXT:
The user is completely hands-free/eyes-free and has NO access to the screen or touch controls — they cannot see, scroll, or tap the phone screen themselves. You are their eyes and hands.

Rules:
1. Respond ONLY with a single valid double-quoted JSON object: {"calls":[{"tool":"...","args":{...}}],"reply":"..."}
2. Because the user cannot touch or see the screen, NEVER tell the user to tap, click, scroll, type, or look at their screen. ALWAYS perform the touch/navigation steps yourself via tool calls (launch_app, screen_control_app, ui_open_and_scan, ui_find_and_tap, ui_act, capture_current_screen).
3. When taking an action on the phone or in an app, populate "calls" using ONLY the tools provided in the prompt and set "reply" to "".
4. For multi-stage app tasks (e.g. open an app then search, tap, scroll, or play), output an ordered multi-stage "calls" list: launch_app via Intent first, then screen_control_app (UI automation) or ui_find_and_tap for follow-up actions.
5. Whenever a search, screen, menu, or list presents options for the user to choose from (results, songs, videos, contacts, notifications, apps, buttons), NEVER stop at just "I searched" — extract and read clear numbered choices ("First, ... Second, ... Third, ...") in "reply" without any URLs, domain breadcrumbs, button/widget types, or technical labels, and ask "Which one would you like?" so the user can choose by voice.
6. For general knowledge or factual questions (e.g., geography, history, science, definitions, trivia), NEVER launch Google or Chrome — answer directly and concisely in 1-2 plain-English sentences in "reply" with "calls": [] (or call answer_web_question if live lookup is needed). Never output URLs, Markdown links, widget names, or technical jargon.
7. Correct minor user spelling typos in app names automatically (e.g., "youitube music" -> "YouTube Music", "spotfy" -> "Spotify").
8. Handle intermediate pop-ups, permissions, app choosers, and single/recorded account prompts autonomously; only pause for real payments, purchases, or deletions.

Examples:
User: "what is the highest mountain in the philippines"
Output: {"calls":[],"reply":"The highest mountain in the Philippines is Mount Apo, rising to about 2,954 meters above sea level in Mindanao."}
User: "open youtube and find a christmas song"
Output: {"calls":[{"tool":"launch_app","args":{"app_name":"YouTube"}},{"tool":"screen_control_app","args":{"app_name":"YouTube","goal":"search_on_screen","query_text":"christmas song"}}],"reply":""}
User: "open youitube music and play a song"
Output: {"calls":[{"tool":"play_in_app","args":{"app_name":"YouTube Music","query":"song","media_type":"song"}}],"reply":""}
User: "turn on bluetooth"
Output: {"calls":[{"tool":"set_hardware_state","args":{"feature":"bluetooth","state":"on"}}],"reply":""}"""

        fun buildProgressivePrompt(
            query: String,
            disclosedSkills: List<com.example.data.SkillSpec>,
            fewShotHints: List<String>,
            uiTreeContext: String? = null,
            installedAppsContext: String? = null,
            recordedAccount: String? = null,
            memoryContext: String? = null,
            personalityProfile: String? = null
        ): String {
            val activeTools = DeferredToolRegistry.getToolsForSkills(disclosedSkills.map { it.id })
                .ifEmpty { disclosedSkills.flatMap { it.tools }.distinctBy { it.name } }
            val toolsSchema = activeTools.joinToString("\n") { t ->
                val params = t.parameters.joinToString(", ") { "${it.name}:${it.type}" }
                "- ${t.name}($params): ${t.description}"
            }
            val guidanceLines = disclosedSkills
                .filter { it.guidance.isNotBlank() }
                .joinToString("\n") { "- [${it.title}] ${it.guidance}" }
            val guidanceSection = if (guidanceLines.isNotBlank()) {
                "\nSkill Guidance:\n$guidanceLines"
            } else {
                ""
            }
            val balancedExamples = buildList {
                disclosedSkills.forEach { skill ->
                    addAll(skill.fewShotExamples.take(2))
                }
                addAll(fewShotHints)
            }.distinct().take(6).joinToString("\n")
            val accountLine = if (!recordedAccount.isNullOrBlank()) {
                "\nActive Device Account: $recordedAccount (use automatically for account prompts)."
            } else {
                "\nActive Device Account: Not saved yet (if an account prompt shows an account, select and confirm it automatically)."
            }
            val personalitySection = if (!personalityProfile.isNullOrBlank()) {
                "\n[Personality & Tone]\n$personalityProfile"
            } else {
                ""
            }
            val memorySection = if (!memoryContext.isNullOrBlank()) {
                "\n[Agentic Memory & Learned Processes]\n$memoryContext"
            } else {
                ""
            }
            val appsSection = if (!installedAppsContext.isNullOrBlank()) {
                "\nInstalled Phone Apps & Capabilities (tolerate typos in app names):\n$installedAppsContext"
            } else {
                ""
            }
            val screenSection = if (!uiTreeContext.isNullOrBlank()) {
                "\nActive Screen Elements (Remember: the user has NO access to the screen or touch — perform taps/scrolls yourself via ui_act(CLICK)/ui_find_and_tap, auto-dismiss pop-ups/accounts, OR read the clickable options aloud as First, Second, Third and ask Which one would you like?):\n$uiTreeContext"
            } else {
                ""
            }
            return """
                User request: "$query"$accountLine$personalitySection$memorySection
                Available tools (each takes <= 3 string/int/bool args):
                $toolsSchema$guidanceSection
                JSON Examples:
                $balancedExamples$appsSection$screenSection
                Rules:
                - CRITICAL ACCESSIBILITY CONTEXT: The user has NO access to the screen or touch controls — YOU are the user's hands and eyes. NEVER ask the user to tap, scroll, or look at their screen. Do every required UI tap/search/play yourself with tools, and whenever multiple options/results exist on screen, read them aloud as First, Second, Third and ask "Which one would you like?".
                - Adapt your spoken reply tone to [Personality & Tone] and apply any learned preferences, user corrections, or learned processes from [Agentic Memory & Learned Processes].
                - For general knowledge or factual questions (like "what is...", "who is...", "capital of..."), NEVER open Chrome or Google Search — answer directly in 1-2 crisp spoken sentences with "calls":[] (or call answer_web_question).
                - NEVER include URLs (https://...), domain breadcrumbs, synthetic widget names ("Button (...)", "Toggle (...)"), UI chrome ("Skip to main content", "Feedback"), or raw node IDs in your reply.
                - Prefer app-based tools (launch_app, screen_control_app, play_in_app, search_in_app, navigate_in_maps) ONLY when the request names or implies an installed app, correcting minor typos (e.g. "youitube" -> "YouTube").
                - For multi-stage requests ("open <app> and find/search/tap ..."), output ALL sequential stages in order inside "calls" (e.g. launch_app first, then screen_control_app with goal="search_on_screen" and query_text="...") and keep "reply" empty.
                - NEVER reply in prose saying you are searching or opening something without putting the actual tool calls inside "calls".
                - If a search returns multiple choices, summarize them as First, Second, Third and ask "Which one would you like?" without technical terms.
                - Respond ONLY with valid double-quoted JSON on one line: {"calls":[{"tool":"<name>","args":{...}}],"reply":"<plain-English message or empty string>"}
            """.trimIndent()
        }

        private val THOUGHT_TAG_REGEX = Regex("<(thought|thinking|reasoning)>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
        private val FENCE_REGEX = Regex("```(?:json|tool_code|python)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        private val DOUBLE_QUOTED_REPLY_REGEX = Regex("\"(?:reply|response|answer|message)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)(?:\"|$)")
        private val SINGLE_QUOTED_REPLY_REGEX = Regex("'(?:reply|response|answer|message)'\\s*:\\s*'((?:[^'\\\\]|\\\\.)*)(?:'|$)")
        private val JSON_ARTIFACT_REGEX = Regex("\"calls\"\\s*:|\"tool\"\\s*:|\"args\"\\s*:|'calls'\\s*:|'tool'\\s*:", RegexOption.IGNORE_CASE)
        private val FUNC_CALL_REGEX = Regex("\\b([a-z][a-z0-9_]{2,})\\s*\\(([^)]*)\\)")
        private val NAMED_ARG_REGEX = Regex("([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^,\\s)]+))")
        private val SINGLE_QUOTE_KEY_REGEX = Regex("([{,\\s])'([a-zA-Z0-9_]+)'\\s*:")
        private val SINGLE_QUOTE_VAL_REGEX = Regex(":\\s*'([^']*)'")
        private val SNAKE_PROSE_RUN_REGEX = Regex("\\b([a-zA-Z]{2,}(?:_[a-zA-Z]{2,}){2,})\\b")
        private val MARKDOWN_BOLD_REGEX = Regex("\\*\\*([^*]+)\\*\\*")

        private fun extractRecoveredReplyField(cleaned: String): String? {
            val raw = DOUBLE_QUOTED_REPLY_REGEX.find(cleaned)?.groupValues?.getOrNull(1)
                ?: SINGLE_QUOTED_REPLY_REGEX.find(cleaned)?.groupValues?.getOrNull(1)
                ?: return null
            return raw
                .replace("\\n", "\n")
                .replace("\\\"", "\"")
                .replace("\\'", "'")
                .trim()
                .takeIf { it.isNotEmpty() }
        }

        fun parseModelTurnText(raw: String): Pair<List<ToolCallRequest>, String>? {
            val cleaned = stripThoughtsAndFences(raw)
            val candidates = extractBalancedJsonObjects(cleaned)

            for (candidate in candidates.asReversed()) {
                val parsed = parseJsonCandidate(candidate)
                    ?: parseJsonCandidate(normalizeSingleQuotedJson(candidate))
                if (parsed != null) return parsed
            }

            // Fallback 1: Pythonic tool call syntax e.g. play_in_app(app_name='YouTube Music', query='song')
            val pythonicCalls = parsePythonicToolCalls(cleaned)
            if (pythonicCalls.isNotEmpty()) {
                return pythonicCalls to ""
            }

            // Fallback 2: Partial / malformed JSON recovery preserving plain-English "reply" (including contractions)
            val recoveredReply = extractRecoveredReplyField(cleaned)
            if (recoveredReply != null) {
                return emptyList<ToolCallRequest>() to recoveredReply
            }

            // Fallback 3: Model returned pure conversational plain English without JSON
            if (looksLikeCleanProseReply(raw)) {
                return emptyList<ToolCallRequest>() to cleanProseReply(raw)
            }

            return null
        }

        private fun parseJsonCandidate(candidate: String): Pair<List<ToolCallRequest>, String>? {
            return runCatching {
                val root = JSONObject(candidate)
                val arr = root.optJSONArray("calls") ?: root.optJSONArray("tool_calls")
                val calls = mutableListOf<ToolCallRequest>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        parseSingleToolItem(item, calls.size + 1)?.let { calls.add(it) }
                    }
                } else if (root.has("tool") || root.has("name") || root.has("function")) {
                    parseSingleToolItem(root, 1)?.let { calls.add(it) }
                }

                val reply = listOf("reply", "response", "answer", "message", "text")
                    .firstNotNullOfOrNull { key ->
                        if (root.has(key) && !root.isNull(key)) {
                            root.optString(key, "").takeUnless { it.equals("null", ignoreCase = true) }
                        } else null
                    }.orEmpty().trim()

                if (arr != null || root.has("tool") || root.has("name") || root.has("function") || reply.isNotEmpty()) {
                    calls to reply
                } else {
                    null
                }
            }.getOrNull()
        }

        private fun normalizeSingleQuotedJson(input: String): String {
            if (!input.contains("'")) return input
            var res = SINGLE_QUOTE_KEY_REGEX.replace(input) { m -> "${m.groupValues[1]}\"${m.groupValues[2]}\":" }
            res = SINGLE_QUOTE_VAL_REGEX.replace(res) { m ->
                val escaped = m.groupValues[1].replace("\"", "\\\"")
                ": \"$escaped\""
            }
            return res
        }

        private fun parsePythonicToolCalls(text: String): List<ToolCallRequest> {
            val registered = DeferredToolRegistry.getAllRegisteredTools().associateBy { it.name }
            val calls = mutableListOf<ToolCallRequest>()
            for (match in FUNC_CALL_REGEX.findAll(text)) {
                val toolName = match.groupValues[1].trim()
                val spec = registered[toolName] ?: continue
                val argsText = match.groupValues[2].trim()
                val argsMap = linkedMapOf<String, String>()

                for (argMatch in NAMED_ARG_REGEX.findAll(argsText)) {
                    if (argsMap.size >= 3) break
                    val key = argMatch.groupValues[1].trim()
                    val value = argMatch.groupValues[2].ifEmpty {
                        argMatch.groupValues[3].ifEmpty { argMatch.groupValues[4] }
                    }.trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        argsMap[key] = value
                    }
                }

                // Support single positional argument like launch_app('Spotify')
                if (argsMap.isEmpty() && argsText.isNotEmpty() && spec.parameters.isNotEmpty()) {
                    val unquoted = argsText.trim().trim('\'', '"').trim()
                    if (unquoted.isNotEmpty() && !unquoted.contains("=")) {
                        argsMap[spec.parameters.first().name] = unquoted
                    }
                }

                runCatching {
                    DeferredToolRegistry.validateToolCall(
                        ToolCallRequest(
                            toolName = toolName,
                            args = argsMap,
                            shortRefId = "t0${calls.size + 1}",
                            safetyTier = SafetyTier.GREEN
                        )
                    )
                }.getOrNull()?.let { calls.add(it) }
            }
            return calls
        }

        private fun parseSingleToolItem(item: JSONObject, refIndex: Int): ToolCallRequest? {
            return runCatching {
                val toolName = listOf("tool", "name", "function")
                    .firstNotNullOfOrNull { k -> item.optString(k, "").trim().takeIf { it.isNotEmpty() } }
                    ?: return null
                val argsObj = item.optJSONObject("args") ?: item.optJSONObject("arguments") ?: JSONObject()
                val argsMap = linkedMapOf<String, String>()
                val keys = argsObj.keys()
                while (keys.hasNext() && argsMap.size < 3) {
                    val k = keys.next()
                    if (!argsObj.isNull(k)) {
                        argsMap[k] = argsObj.get(k).toString()
                    }
                }
                DeferredToolRegistry.validateToolCall(
                    ToolCallRequest(
                        toolName = toolName,
                        args = argsMap,
                        shortRefId = "t0$refIndex",
                        safetyTier = SafetyTier.GREEN
                    )
                )
            }.getOrNull()
        }

        fun looksLikeCleanProseReply(raw: String): Boolean {
            val cleaned = cleanProseReply(raw)
            if (cleaned.isBlank()) return false
            if (cleaned.contains("{") || cleaned.contains("}") || cleaned.contains("```")) return false
            if (looksLikeJsonPayload(cleaned)) return false
            val registeredNames = DeferredToolRegistry.getAllRegisteredTools().map { it.name }.toSet()
            val hasToolSyntax = FUNC_CALL_REGEX.findAll(cleaned).any { it.groupValues[1] in registeredNames }
            return !hasToolSyntax
        }

        fun cleanProseReply(raw: String): String =
            THOUGHT_TAG_REGEX.replace(raw, "")
                .replace("```json", "")
                .replace("```tool_code", "")
                .replace("```", "")
                .trim()

        private fun isTruncatedSingleFragment(text: String): Boolean {
            val clean = text.trim().trimEnd('.', ',', '!', '?', ':', ';')
            if (clean.isEmpty()) return true
            if (clean.length <= 2) {
                val lower = clean.lowercase()
                return lower !in setOf("ok", "no", "hi", "go", "on") && !clean.all { it.isDigit() }
            }
            return false
        }

        fun cleanMalformedTokenArtifacts(text: String): String {
            var res = text.replace('\u2581', ' ')
            res = MARKDOWN_BOLD_REGEX.replace(res) { it.groupValues[1] }
            // Fix accidental underscore-joined natural English phrases (e.g. "scarecrow_fail_at_his_job")
            res = SNAKE_PROSE_RUN_REGEX.replace(res) { m ->
                m.groupValues[1].replace('_', ' ')
            }
            res = res.replace(Regex("(?<=\\w)_(?=\\w)"), " ")
            res = res.replace(Regex("\\s+([.,!?;:])"), "$1")
            res = res.replace(Regex("\\s+"), " ").trim()
            return res
        }

        fun sanitizeAssistantReply(
            parsedReply: String?,
            rawFallback: String,
            hasToolCalls: Boolean
        ): String {
            fun polish(candidate: String): String {
                val deGlitched = cleanMalformedTokenArtifacts(candidate)
                return com.example.engine.ScreenTextSanitizer.cleanUiLabelForSpeech(deGlitched)
                    .ifBlank { deGlitched }
            }

            val direct = parsedReply?.trim().orEmpty()
            if (direct.isNotEmpty() && !looksLikeJsonPayload(direct) && !isTruncatedSingleFragment(direct)) {
                return polish(direct)
            }
            if (hasToolCalls) {
                return ""
            }

            val stripped = stripThoughtsAndFences(rawFallback).trim()
            extractRecoveredReplyField(stripped)
                ?.takeIf { !looksLikeJsonPayload(it) && !isTruncatedSingleFragment(it) }
                ?.let { recovered -> return polish(recovered) }

            val withoutJsonBlocks = stripJsonFragments(stripped)
            if (withoutJsonBlocks.isNotEmpty() &&
                !looksLikeJsonPayload(withoutJsonBlocks) &&
                !isTruncatedSingleFragment(withoutJsonBlocks)
            ) {
                return polish(withoutJsonBlocks)
            }

            return "I couldn't complete that request clearly. Please try rephrasing."
        }

        fun looksLikeJsonPayload(text: String): Boolean {
            val t = text.trim()
            if (t.isEmpty()) return false
            if ((t.startsWith("{") && t.contains(":")) || (t.startsWith("[") && t.contains("{"))) return true
            if (t.startsWith("```")) return true
            return JSON_ARTIFACT_REGEX.containsMatchIn(t)
        }

        private fun stripThoughtsAndFences(raw: String): String {
            var res = THOUGHT_TAG_REGEX.replace(raw, "").trim()
            val fenceMatch = FENCE_REGEX.find(res)
            if (fenceMatch != null) {
                res = fenceMatch.groupValues[1].trim()
            } else {
                res = res.replace("```json", "")
                    .replace("```tool_code", "")
                    .replace("```python", "")
                    .replace("```", "")
                    .trim()
            }
            return res
        }

        private fun stripJsonFragments(text: String): String {
            val objs = extractBalancedJsonObjects(text)
            var cleaned = text
            objs.forEach { obj -> cleaned = cleaned.replace(obj, "") }
            return cleaned
                .replace("```json", "")
                .replace("```tool_code", "")
                .replace("```", "")
                .trim()
        }

        private fun extractBalancedJsonObjects(text: String): List<String> {
            val results = mutableListOf<String>()
            var depth = 0
            var startIdx = -1
            var inString = false
            var escaped = false

            for (i in text.indices) {
                val ch = text[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (ch == '\\' && inString) {
                    escaped = true
                    continue
                }
                if (ch == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (ch == '{') {
                        if (depth == 0) startIdx = i
                        depth++
                    } else if (ch == '}' && depth > 0) {
                        depth--
                        if (depth == 0 && startIdx >= 0) {
                            results.add(text.substring(startIdx, i + 1))
                            startIdx = -1
                        }
                    }
                }
            }
            return results
        }
    }
}
