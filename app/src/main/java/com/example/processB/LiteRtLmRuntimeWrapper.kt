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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
                conversation = createFreshConversation(initializedEngine)
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

    private fun createFreshConversation(eng: Engine): Conversation =
        eng.createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(SYSTEM_PROMPT),
                samplerConfig = SamplerConfig(topK = 10, topP = 0.95, temperature = 0.2),
                thinkingConfig = ThinkingConfig(enableThinking = false)
            )
        )

    private fun initializeWithFallbackBackend(
        modelPath: String,
        cacheDir: String?,
        nativeLibDir: String?,
        preferNpu: Boolean
    ): Pair<Engine, String> {
        val candidates = buildList {
            if (preferNpu && !nativeLibDir.isNullOrBlank()) {
                add(Triple(Backend.NPU(nativeLibDir), Backend.GPU(), "NPU"))
            }
            add(Triple(Backend.GPU(), Backend.GPU(), "GPU"))
            add(Triple(Backend.CPU(), Backend.CPU(), "CPU"))
        }

        var lastException: Exception? = null
        for ((mainBackend, visionBackend, label) in candidates) {
            // Never reuse or call close() on an Engine whose initialize() threw
            val candidate = Engine(
                EngineConfig(
                    modelPath = modelPath,
                    backend = mainBackend,
                    visionBackend = visionBackend,
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
        resetConversation: Boolean = false
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

            if (resetConversation || conversation == null) {
                runCatching { conversation?.close() }
                conversation = createFreshConversation(currentEngine)
            }
            val activeConv = conversation
                ?: throw IllegalStateException("Unable to open LLM conversation session.")

            state.set(EngineState.BUSY)
            try {
                val disclosedSkills = candidateSkillIds
                    .mapNotNull { SkillCatalog.findById(it) }
                    .filter { it.enabled }
                val primarySkill = disclosedSkills.firstOrNull() ?: SkillCatalog.enabledSkills.first()
                val requiresThinking = disclosedSkills.any { it.requiresThinking } || !uiTreeContext.isNullOrBlank()

                val promptText = buildProgressivePrompt(
                    query = query,
                    disclosedSkills = disclosedSkills,
                    fewShotHints = fewShotHints,
                    uiTreeContext = uiTreeContext,
                    installedAppsContext = installedAppsContext,
                    recordedAccount = recordedAccount
                )
                val contents = if (imageBytes != null && imageBytes.isNotEmpty()) {
                    Contents.of(Content.ImageBytes(imageBytes), Content.Text(promptText))
                } else {
                    Contents.of(Content.Text(promptText))
                }

                val firstRaw = collectReply(activeConv, contents, requiresThinking)
                if (bargeInCancelled.get()) throw CancellationException("Barge-in interrupted :llm decode")

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
        conv.sendMessageAsync(contents, thinkingConfig = thinkingCfg).collect { chunk ->
            if (bargeInCancelled.get()) {
                conv.cancelProcess()
                throw CancellationException("Barge-in interrupted stream")
            }
            sb.append(chunk.toString())
        }
        return sb.toString()
    }

    private fun buildProgressivePrompt(
        query: String,
        disclosedSkills: List<com.example.data.SkillSpec>,
        fewShotHints: List<String>,
        uiTreeContext: String?,
        installedAppsContext: String?,
        recordedAccount: String?
    ): String {
        val toolsSchema = disclosedSkills.flatMap { it.tools }.joinToString("\n") { t ->
            val params = t.parameters.joinToString(", ") { "${it.name}:${it.type}" }
            "- ${t.name}($params): ${t.description}"
        }
        val examples = fewShotHints.take(4).joinToString("\n")
        val accountLine = if (!recordedAccount.isNullOrBlank()) {
            "\nActive Device Account: $recordedAccount (use automatically for account prompts)."
        } else {
            "\nActive Device Account: Not saved yet (if an account prompt shows an account, select and confirm it automatically)."
        }
        val appsSection = if (!installedAppsContext.isNullOrBlank()) {
            "\nInstalled Phone Apps & Capabilities (tolerate typos in app names):\n$installedAppsContext"
        } else {
            ""
        }
        val screenSection = if (!uiTreeContext.isNullOrBlank()) {
            "\nActive Screen Elements:\n$uiTreeContext\nProceed autonomously through pop-ups, permissions, app choosers, and account prompts via ui_act(CLICK). If task is done, return empty calls [] and a friendly plain-English reply without mentioning internal IDs."
        } else {
            ""
        }
        return """
            User request: "$query"$accountLine
            Available tools (each takes <= 3 string/int/bool args):
            $toolsSchema
            JSON Examples:
            $examples$appsSection$screenSection
            Rules:
            - Prefer app-based tools (play_in_app, search_in_app, navigate_in_maps, launch_app, get_app_skills) when the request names or implies an installed app, correcting obvious spelling typos (e.g. "youitube music" -> "YouTube Music").
            - If a search returns multiple choices, summarize them as First, Second, Third and ask which one without technical terms.
            - Respond ONLY with valid double-quoted JSON on one line: {"calls":[{"tool":"<name>","args":{...}}],"reply":"<plain-English message or empty string>"}
        """.trimIndent()
    }

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
        private const val SYSTEM_PROMPT =
            """You are Nova, an on-device Android personal assistant with full control over phone settings, installed apps, media playback, search, calls, messages, and screen UI automation.

Rules:
1. Respond ONLY with a single valid double-quoted JSON object: {"calls":[{"tool":"...","args":{...}}],"reply":"..."}
2. When taking an action on the phone or in an app, populate "calls" using ONLY the tools provided in the prompt and set "reply" to "".
3. When answering conversationally without calling a tool, set "calls" to [] and write a friendly plain-English answer in "reply" with zero JSON or technical jargon.
4. Correct minor user spelling typos in app names automatically (e.g., "youitube music" -> "YouTube Music", "spotfy" -> "Spotify"). Use play_in_app to open an app and play media, search_in_app to search inside an app, navigate_in_maps for directions, launch_app to open an app, and ui_open_and_scan/ui_act for screen interactions.
5. Handle intermediate pop-ups, permissions, app choosers, and single/recorded account prompts autonomously; only pause for real payments, purchases, or deletions.

Examples:
User: "open youitube music and play a song"
Output: {"calls":[{"tool":"play_in_app","args":{"app_name":"YouTube Music","query":"song","media_type":"song"}}],"reply":""}
User: "turn on bluetooth"
Output: {"calls":[{"tool":"set_hardware_state","args":{"feature":"bluetooth","state":"on"}}],"reply":""}"""

        private val THOUGHT_TAG_REGEX = Regex("<(thought|thinking|reasoning)>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE)
        private val FENCE_REGEX = Regex("```(?:json|tool_code|python)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
        private val REPLY_FIELD_REGEX = Regex("[\"'](?:reply|response|answer|message)[\"']\\s*:\\s*[\"']((?:[^\"'\\\\]|\\\\.)*)(?:[\"']|$)")
        private val JSON_ARTIFACT_REGEX = Regex("\"calls\"\\s*:|\"tool\"\\s*:|\"args\"\\s*:|'calls'\\s*:|'tool'\\s*:", RegexOption.IGNORE_CASE)
        private val FUNC_CALL_REGEX = Regex("\\b([a-z][a-z0-9_]{2,})\\s*\\(([^)]*)\\)")
        private val NAMED_ARG_REGEX = Regex("([a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^,\\s)]+))")
        private val SINGLE_QUOTE_KEY_REGEX = Regex("([{,\\s])'([a-zA-Z0-9_]+)'\\s*:")
        private val SINGLE_QUOTE_VAL_REGEX = Regex(":\\s*'([^']*)'")

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

            // Fallback 2: Partial / malformed JSON recovery preserving plain-English "reply"
            val recoveredReply = REPLY_FIELD_REGEX.find(cleaned)?.groupValues?.getOrNull(1)
                ?.replace("\\n", "\n")
                ?.replace("\\\"", "\"")
                ?.replace("\\'", "'")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

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

        fun sanitizeAssistantReply(
            parsedReply: String?,
            rawFallback: String,
            hasToolCalls: Boolean
        ): String {
            val direct = parsedReply?.trim().orEmpty()
            if (direct.isNotEmpty() && !looksLikeJsonPayload(direct)) {
                return direct
            }
            if (hasToolCalls) {
                return ""
            }

            val stripped = stripThoughtsAndFences(rawFallback).trim()
            REPLY_FIELD_REGEX.find(stripped)?.groupValues?.getOrNull(1)
                ?.replace("\\n", "\n")
                ?.replace("\\\"", "\"")
                ?.replace("\\'", "'")
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !looksLikeJsonPayload(it) }
                ?.let { return it }

            val withoutJsonBlocks = stripJsonFragments(stripped)
            if (withoutJsonBlocks.isNotEmpty() && !looksLikeJsonPayload(withoutJsonBlocks)) {
                return withoutJsonBlocks
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
