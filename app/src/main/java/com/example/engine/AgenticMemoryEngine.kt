package com.example.engine

import com.example.data.AgenticMemoryEntry
import com.example.data.AgenticMemoryType
import com.example.data.AssistantPersonality
import com.example.data.LearnedProcessRecord
import com.example.data.LocalFtsIndexHelper
import com.example.data.TimeOfDayBucket
import com.example.data.ToolCallRequest
import com.example.processA.EmbeddingGemmaRuntime
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * Agentic Memory, Adaptive Personality & Procedural Habit Learning Engine.
 *
 * Maintains three persistent SQLite memory layers inside Process A:
 * 1. Semantic Preferences & Corrections (`PREFERENCE` / `CORRECTION`)
 * 2. Episodic Interaction History (`EPISODE`)
 * 3. Induced Procedural Workflows & Time-of-Day Habits (`learned_processes` + `behavior_events`)
 */
class AgenticMemoryEngine(
    private val ftsHelper: LocalFtsIndexHelper,
    private val embeddingRuntime: EmbeddingGemmaRuntime? = null,
    private val personalityProvider: () -> AssistantPersonality = { AssistantPersonality.WARM_COMPANION },
    private val userNameProvider: () -> String = { "NOVA USER" },
    private val onPersonalityChanged: (AssistantPersonality) -> Unit = {},
    private val onUserNameChanged: (String) -> Unit = {}
) {

    fun currentPersonality(): AssistantPersonality = personalityProvider()

    fun applyPersonality(personality: AssistantPersonality, nowMs: Long = System.currentTimeMillis()) {
        onPersonalityChanged(personality)
        ftsHelper.upsertAgenticMemory(
            memoryId = "pref_response_style",
            memoryType = AgenticMemoryType.PREFERENCE,
            topic = "pref_response_style",
            content = "Active personality set to ${personality.displayName}: ${personality.tagline}",
            confidence = 0.95f,
            timeBucket = TimeOfDayBucket.ANY,
            nowMs = nowMs
        )
    }

    fun applyUserName(rawName: String, nowMs: Long = System.currentTimeMillis()): String {
        val formatted = rawName.trim()
            .take(24)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" ") { p -> p.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } }
            .ifEmpty { "Friend" }
        onUserNameChanged(formatted.uppercase(Locale.getDefault()))
        ftsHelper.upsertAgenticMemory(
            memoryId = "pref_user_name",
            memoryType = AgenticMemoryType.PREFERENCE,
            topic = "pref_user_name",
            content = "User prefers to be addressed as $formatted",
            confidence = 0.95f,
            timeBucket = TimeOfDayBucket.ANY,
            nowMs = nowMs
        )
        return formatted
    }

    fun findLearnedProcess(query: String): LearnedProcessRecord? =
        ftsHelper.findLearnedProcessByName(query)

    fun recordProcessExecution(processId: String, nowMs: Long = System.currentTimeMillis()) {
        ftsHelper.recordLearnedProcessExecution(processId, nowMs)
    }

    fun findTopSuggestedProcessForBriefing(nowMs: Long = System.currentTimeMillis()): LearnedProcessRecord? {
        val currentBucket = TimeOfDayBucket.fromTimestamp(nowMs)
        val todayStart = ImportantNotificationFilter.startOfDayMillis(nowMs)
        val recentToday = ftsHelper.getRecentBehaviorEvents(sinceMs = todayStart, limit = 15)
        val recentCommandsLower = recentToday.map { it.normalizedCommand.lowercase() }.toSet()

        return ftsHelper.getLearnedProcesses(
            timeBucket = currentBucket,
            minConfidence = 0.50f,
            limit = 4
        ).firstOrNull { proc ->
            proc.autoSuggest && proc.steps.any { step ->
                val sLower = step.lowercase()
                recentCommandsLower.none { ran -> ran.contains(sLower) || sLower.contains(ran) }
            }
        }
    }

    fun friendlyUserName(): String {
        val raw = userNameProvider().trim()
        if (raw.isEmpty() || raw.equals("NOVA USER", ignoreCase = true)) return ""
        return raw.lowercase().split(Regex("\\s+"))
            .joinToString(" ") { part -> part.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } }
    }

    /**
     * Observes a completed turn, records its behavioral trace, updates preferences/corrections,
     * consolidates multi-step or repeated workflows into Learned Processes, and writes an episode.
     */
    suspend fun learnFromTurn(
        rawQuery: String,
        normalizedQuery: String,
        subCommands: List<String>,
        toolCalls: List<ToolCallRequest>,
        responseText: String,
        success: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val cleanQuery = normalizedQuery.trim().ifBlank { rawQuery.trim() }
        if (cleanQuery.isBlank() || !success) return

        // Do not learn habits from meta-memory inspection/deletion turns
        if (toolCalls.any { it.toolName == "manage_agentic_memory" }) return

        val timeBucket = TimeOfDayBucket.fromTimestamp(nowMs)
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

        // 1. Extract explicit user corrections & tone/name directives
        extractCorrectionsAndPreferences(rawQuery, cleanQuery, toolCalls, timeBucket, nowMs)

        // 2. Skip storing bare trivial status queries as procedural habits, though record event
        val primaryApp = toolCalls.firstNotNullOfOrNull { call ->
            call.args["app_name"]?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()
        val toolSeq = toolCalls.joinToString("->") { c ->
            when (c.toolName) {
                "play_in_app", "search_in_app", "launch_app", "screen_control_app" ->
                    "${c.toolName}:${c.args["app_name"].orEmpty().ifBlank { "auto" }}"
                "set_hardware_state" ->
                    "${c.toolName}:${c.args["feature"].orEmpty()}:${c.args["state"].orEmpty()}"
                else -> c.toolName
            }
        }.ifEmpty { "conversational" }

        val canonicalTopic = canonicalizeIntentKey(cleanQuery, toolCalls)
        val recentBefore = ftsHelper.getRecentBehaviorEvents(sinceMs = nowMs - 12L * 60_000L, limit = 5)

        ftsHelper.recordBehaviorEvent(
            eventId = "ev_${nowMs}_${abs(cleanQuery.hashCode()) % 10000}",
            normalizedCommand = cleanQuery.take(180),
            toolSequence = toolSeq,
            appName = primaryApp,
            topic = canonicalTopic,
            timeBucket = timeBucket,
            dayOfWeek = dayOfWeek,
            success = true,
            timestampMs = nowMs
        )

        // 3. Record concise Episodic Memory (skipping rapid duplicates within 5 mins)
        val latestEpisode = ftsHelper.getAgenticMemories(
            types = setOf(AgenticMemoryType.EPISODE),
            limit = 1
        ).firstOrNull()
        val isDupEpisode = latestEpisode != null &&
            latestEpisode.topic == canonicalTopic &&
            (nowMs - latestEpisode.lastReinforcedAtMs) < 5L * 60_000L
        if (!isDupEpisode && toolCalls.isNotEmpty()) {
            val summarySnippet = responseText
                .replace("\n", " ")
                .trim()
                .take(110)
            val episodeContent = if (summarySnippet.isNotBlank()) {
                "${timeBucket.label}: Ran \"$cleanQuery\" -> $summarySnippet"
            } else {
                "${timeBucket.label}: Ran \"$cleanQuery\""
            }
            ftsHelper.upsertAgenticMemory(
                memoryId = "ep_${nowMs}_${abs(canonicalTopic.hashCode()) % 1000}",
                memoryType = AgenticMemoryType.EPISODE,
                topic = canonicalTopic,
                content = episodeContent,
                confidence = 0.72f,
                timeBucket = timeBucket,
                nowMs = nowMs
            )
        }

        // 4. Learn & consolidate user processes (compound commands, chained session sequences, or recurring time-of-day habits)
        consolidateLearnedProcesses(
            cleanQuery = cleanQuery,
            subCommands = subCommands,
            toolCalls = toolCalls,
            canonicalTopic = canonicalTopic,
            primaryApp = primaryApp,
            timeBucket = timeBucket,
            recentSessionEvents = recentBefore,
            nowMs = nowMs
        )
    }

    private suspend fun extractCorrectionsAndPreferences(
        rawQuery: String,
        cleanQuery: String,
        toolCalls: List<ToolCallRequest>,
        timeBucket: TimeOfDayBucket,
        nowMs: Long
    ) {
        val lower = rawQuery.lowercase()

        // Preferred music / media / navigation apps & messaging channel from tool usage
        for (call in toolCalls) {
            when (call.toolName) {
                "play_in_app", "search_in_app", "screen_control_app", "launch_app" -> {
                    val app = call.args["app_name"]?.trim().orEmpty()
                    val appLower = app.lowercase()
                    val usedBrowser = call.args["use_browser"].equals("true", ignoreCase = true)
                    if (!usedBrowser && app.isNotBlank() && !isBrowserOrWebTarget(app) &&
                        (appLower.contains("music") || appLower.contains("spotify") || appLower.contains("soundcloud") || appLower.contains("vlc") || appLower.contains("youtube") || appLower.contains("deezer") || appLower.contains("tidal"))
                    ) {
                        val topic = if (appLower.contains("youtube") && !appLower.contains("music")) {
                            "pref_video_app"
                        } else {
                            "pref_music_app"
                        }
                        val label = if (topic == "pref_video_app") "videos" else "music playback"
                        val content = "Prefers $app for $label"
                        val vec = embeddingRuntime?.embedDocument(topic, content)
                        ftsHelper.upsertAgenticMemory(
                            memoryId = topic,
                            memoryType = AgenticMemoryType.PREFERENCE,
                            topic = topic,
                            content = content,
                            confidence = 0.68f,
                            timeBucket = timeBucket,
                            embedding = vec,
                            nowMs = nowMs
                        )
                    }
                }
                "navigate_in_maps" -> {
                    val app = call.args["app_name"]?.trim().orEmpty()
                    if (app.isNotBlank()) {
                        val content = "Prefers $app for navigation"
                        ftsHelper.upsertAgenticMemory(
                            memoryId = "pref_maps_app",
                            memoryType = AgenticMemoryType.PREFERENCE,
                            topic = "pref_maps_app",
                            content = content,
                            confidence = 0.70f,
                            timeBucket = TimeOfDayBucket.ANY,
                            nowMs = nowMs
                        )
                    }
                }
                "message_send" -> {
                    val channel = call.args["channel"]?.trim().orEmpty()
                    if (channel.isNotBlank() && !channel.equals("auto", ignoreCase = true)) {
                        val cleanChannel = channel.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                        val content = "Prefers $cleanChannel for messaging"
                        ftsHelper.upsertAgenticMemory(
                            memoryId = "pref_message_channel",
                            memoryType = AgenticMemoryType.PREFERENCE,
                            topic = "pref_message_channel",
                            content = content,
                            confidence = 0.70f,
                            timeBucket = TimeOfDayBucket.ANY,
                            nowMs = nowMs
                        )
                    }
                }
            }
        }

        // User name & explicit style corrections
        Regex("(?:call me|my name is)\\s+([A-Za-z][A-Za-z0-9' -]{1,20})", RegexOption.IGNORE_CASE)
            .find(rawQuery)
            ?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() && it.lowercase() !in setOf("maybe", "later", "back") }
            ?.let { preferredName ->
                val formatted = preferredName.split(Regex("\\s+"))
                    .joinToString(" ") { p -> p.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } }
                onUserNameChanged(formatted.uppercase(Locale.getDefault()))
                ftsHelper.upsertAgenticMemory(
                    memoryId = "pref_user_name",
                    memoryType = AgenticMemoryType.PREFERENCE,
                    topic = "pref_user_name",
                    content = "User prefers to be addressed as $formatted",
                    confidence = 0.95f,
                    timeBucket = TimeOfDayBucket.ANY,
                    nowMs = nowMs
                )
            }

        // Explicit corrections: "no, use X", "instead use X", "don't use X", "i prefer X", "always use X"
        val correctionMatch = Regex(
            "(?:no,?\\s+(?:use|open|play on)|instead use|from now on use|i prefer(?: using)?|always use)\\s+([A-Za-z0-9 .+'&-]{2,48})",
            RegexOption.IGNORE_CASE
        ).find(lower)
        if (correctionMatch != null) {
            applyExplicitCorrectionOrPreference(
                rawTarget = correctionMatch.groupValues[1],
                sourceQuery = cleanQuery,
                timeBucket = timeBucket,
                nowMs = nowMs
            )
        }

        // Tone/style cue learning
        when {
            lower.contains("be more concise") || lower.contains("keep it short") || lower.contains("too wordy") -> {
                onPersonalityChanged(AssistantPersonality.CALM_MINIMALIST)
                ftsHelper.upsertAgenticMemory(
                    memoryId = "pref_response_style",
                    memoryType = AgenticMemoryType.CORRECTION,
                    topic = "pref_response_style",
                    content = "Prefers concise, minimalist replies with zero filler",
                    confidence = 0.90f,
                    timeBucket = TimeOfDayBucket.ANY,
                    nowMs = nowMs
                )
            }
            lower.contains("be more witty") || lower.contains("more playful") || lower.contains("have more personality") -> {
                onPersonalityChanged(AssistantPersonality.WITTY_COPILOT)
                ftsHelper.upsertAgenticMemory(
                    memoryId = "pref_response_style",
                    memoryType = AgenticMemoryType.PREFERENCE,
                    topic = "pref_response_style",
                    content = "Prefers an upbeat, witty co-pilot tone",
                    confidence = 0.88f,
                    timeBucket = TimeOfDayBucket.ANY,
                    nowMs = nowMs
                )
            }
            lower.contains("more executive") || lower.contains("more professional") -> {
                onPersonalityChanged(AssistantPersonality.SHARP_EXECUTIVE)
                ftsHelper.upsertAgenticMemory(
                    memoryId = "pref_response_style",
                    memoryType = AgenticMemoryType.PREFERENCE,
                    topic = "pref_response_style",
                    content = "Prefers sharp executive briefings focused on priorities",
                    confidence = 0.88f,
                    timeBucket = TimeOfDayBucket.ANY,
                    nowMs = nowMs
                )
            }
        }
    }

    private fun consolidateLearnedProcesses(
        cleanQuery: String,
        subCommands: List<String>,
        toolCalls: List<ToolCallRequest>,
        canonicalTopic: String,
        primaryApp: String,
        timeBucket: TimeOfDayBucket,
        recentSessionEvents: List<com.example.data.BehaviorEventRecord>,
        nowMs: Long
    ) {
        // Skip trivial 1-word queries, browser-fallback playback, or pure hardware flips unless part of a multi-step flow
        if (toolCalls.isNotEmpty() && toolCalls.all {
                it.args["use_browser"].equals("true", ignoreCase = true) ||
                    (it.toolName == "play_in_app" && isBrowserOrWebTarget(it.args["app_name"].orEmpty()))
            }
        ) {
            return
        }
        val isMultiStage = subCommands.size >= 2 || toolCalls.size >= 2
        if (isMultiStage) {
            val steps = if (subCommands.size >= 2) {
                subCommands.map { it.trim() }.filter { it.isNotEmpty() }
            } else {
                toolCalls.map { call -> toolCallToReadableStep(call) }
            }
            if (steps.size >= 2) {
                val procKey = steps.joinToString("|").lowercase()
                val procId = "proc_multi_${abs(procKey.hashCode())}"
                val displayName = buildProcessDisplayName(steps, timeBucket, primaryApp)
                ftsHelper.upsertLearnedProcess(
                    processId = procId,
                    name = displayName,
                    triggerPhrase = cleanQuery,
                    steps = steps,
                    preferredApp = primaryApp,
                    timeBucket = timeBucket,
                    baseConfidence = 0.56f,
                    autoSuggest = true,
                    nowMs = nowMs
                )
                return
            }
        }

        // Cross-turn sequential habit detection within the last 12 minutes (e.g., notifications -> calendar)
        val prevEvent = recentSessionEvents.firstOrNull {
            it.timeBucket == timeBucket &&
                it.topic != canonicalTopic &&
                (nowMs - it.timestampMs) in 1_000L..(12L * 60_000L)
        }
        if (prevEvent != null && isSubstantialProcessStep(prevEvent.topic) && isSubstantialProcessStep(canonicalTopic)) {
            val seqSteps = listOf(prevEvent.normalizedCommand, cleanQuery)
            val procKey = "${timeBucket.name}|${prevEvent.topic}->${canonicalTopic}"
            val procId = "proc_seq_${abs(procKey.hashCode())}"
            val displayName = "${timeBucket.label} ${topicShortTitle(prevEvent.topic)} & ${topicShortTitle(canonicalTopic)}"
            ftsHelper.upsertLearnedProcess(
                processId = procId,
                name = displayName,
                triggerPhrase = cleanQuery,
                steps = seqSteps,
                preferredApp = primaryApp.ifBlank { prevEvent.appName },
                timeBucket = timeBucket,
                baseConfidence = 0.52f,
                autoSuggest = true,
                nowMs = nowMs
            )
        }

        // Recurring same-time-of-day habit detection (2+ distinct sessions >= 30 mins apart across 14 days)
        if (isSubstantialProcessStep(canonicalTopic)) {
            val lastTwoWeeks = ftsHelper.getRecentBehaviorEvents(
                sinceMs = nowMs - 14L * LocalFtsIndexHelper.DAY_MS,
                limit = 80
            )
            val matchingEvents = lastTwoWeeks.filter {
                it.topic == canonicalTopic && it.timeBucket == timeBucket
            }
            val minTs = matchingEvents.minOfOrNull { it.timestampMs } ?: nowMs
            val maxTs = matchingEvents.maxOfOrNull { it.timestampMs } ?: nowMs
            val spansMultipleSessions = (maxTs - minTs) >= 30L * 60_000L
            if (matchingEvents.size >= 2 && spansMultipleSessions) {
                val procId = "proc_habit_${timeBucket.name.lowercase()}_${abs(canonicalTopic.hashCode())}"
                val displayName = "${timeBucket.label} ${topicShortTitle(canonicalTopic)}"
                ftsHelper.upsertLearnedProcess(
                    processId = procId,
                    name = displayName,
                    triggerPhrase = cleanQuery,
                    steps = listOf(cleanQuery),
                    preferredApp = primaryApp,
                    timeBucket = timeBucket,
                    baseConfidence = 0.55f + (matchingEvents.size - 2) * 0.10f,
                    autoSuggest = true,
                    nowMs = nowMs
                )
            }
        }
    }

    fun applyExplicitCorrectionOrPreference(
        rawTarget: String,
        sourceQuery: String = rawTarget,
        timeBucket: TimeOfDayBucket = TimeOfDayBucket.fromTimestamp(),
        nowMs: Long = System.currentTimeMillis()
    ): Pair<String, String?> {
        val cleanedRaw = rawTarget.trim()
            .replace(
                Regex(
                    "(?:\\s+(?:instead|please|thanks|thank you|from now on|for music|for songs|for videos|for navigation|for maps|for directions|for messaging|for messages|for texting))+$",
                    RegexOption.IGNORE_CASE
                ),
                ""
            )
            .trim()
            .ifEmpty { rawTarget.trim() }
        val lower = cleanedRaw.lowercase()

        val canonicalLabel = when {
            lower == "yt music" || lower == "ytm" || lower.contains("youtube music") -> "YouTube Music"
            lower == "yt" || lower == "youtube" -> "YouTube"
            lower.contains("spotify") -> "Spotify"
            lower.contains("apple music") -> "Apple Music"
            lower.contains("soundcloud") -> "SoundCloud"
            lower.contains("tidal") -> "Tidal"
            lower.contains("deezer") -> "Deezer"
            lower.contains("amazon music") -> "Amazon Music"
            lower.contains("vlc") -> "VLC"
            lower.contains("waze") -> "Waze"
            lower.contains("google maps") || lower == "maps" -> "Google Maps"
            lower.contains("whatsapp") -> "WhatsApp"
            lower.contains("messenger") -> "Messenger"
            lower.contains("telegram") -> "Telegram"
            lower.contains("viber") -> "Viber"
            lower == "sms" || lower == "text" || lower == "messages" -> "SMS"
            else -> cleanedRaw.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" ") { w -> w.replaceFirstChar { c -> c.titlecase(Locale.getDefault()) } }
        }

        ftsHelper.upsertAgenticMemory(
            memoryId = "corr_${abs(canonicalLabel.lowercase().hashCode()) % 10000}",
            memoryType = AgenticMemoryType.CORRECTION,
            topic = "correction_${canonicalLabel.lowercase().replace(Regex("[^a-z0-9]+"), "_")}",
            content = "User corrected assistant: prefers $canonicalLabel (from \"$sourceQuery\")",
            confidence = 0.90f,
            timeBucket = timeBucket,
            nowMs = nowMs
        )

        val categoryDesc: String? = when (canonicalLabel) {
            "YouTube Music", "Spotify", "Apple Music", "SoundCloud", "Tidal", "Deezer", "Amazon Music", "VLC" -> {
                ftsHelper.upsertAgenticMemory(
                    memoryId = "pref_music_app",
                    memoryType = AgenticMemoryType.PREFERENCE,
                    topic = "pref_music_app",
                    content = "Prefers $canonicalLabel for music playback",
                    confidence = 0.90f,
                    timeBucket = TimeOfDayBucket.ANY,
                    nowMs = nowMs
                )
                "music playback"
            }
            "YouTube" -> {
                ftsHelper.upsertAgenticMemory(
                    memoryId = "pref_video_app",
                    memoryType = AgenticMemoryType.PREFERENCE,
                    topic = "pref_video_app",
                    content = "Prefers YouTube for videos",
                    confidence = 0.88f,
                    timeBucket = TimeOfDayBucket.ANY,
                    nowMs = nowMs
                )
                "videos"
            }
            "Waze", "Google Maps" -> {
                ftsHelper.upsertAgenticMemory(
                    memoryId = "pref_maps_app",
                    memoryType = AgenticMemoryType.PREFERENCE,
                    topic = "pref_maps_app",
                    content = "Prefers $canonicalLabel for navigation",
                    confidence = 0.90f,
                    timeBucket = TimeOfDayBucket.ANY,
                    nowMs = nowMs
                )
                "navigation"
            }
            "WhatsApp", "Messenger", "Telegram", "Viber", "SMS" -> {
                ftsHelper.upsertAgenticMemory(
                    memoryId = "pref_message_channel",
                    memoryType = AgenticMemoryType.PREFERENCE,
                    topic = "pref_message_channel",
                    content = "Prefers $canonicalLabel for messaging",
                    confidence = 0.90f,
                    timeBucket = TimeOfDayBucket.ANY,
                    nowMs = nowMs
                )
                "messaging"
            }
            else -> null
        }
        return canonicalLabel to categoryDesc
    }

    fun teachExplicitProcess(
        name: String,
        steps: List<String>,
        timeBucket: TimeOfDayBucket = TimeOfDayBucket.fromTimestamp(),
        nowMs: Long = System.currentTimeMillis()
    ): LearnedProcessRecord {
        val cleanName = name.trim().ifEmpty { "${timeBucket.label} Custom Process" }
        val cleanSteps = steps.map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(cleanName) }
        val procId = "proc_taught_${cleanName.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(36)}"
        val record = ftsHelper.upsertLearnedProcess(
            processId = procId,
            name = cleanName,
            triggerPhrase = cleanName,
            steps = cleanSteps,
            preferredApp = "",
            timeBucket = timeBucket,
            baseConfidence = 0.92f,
            autoSuggest = true,
            nowMs = nowMs
        )
        ftsHelper.upsertAgenticMemory(
            memoryId = "mem_$procId",
            memoryType = AgenticMemoryType.FACT,
            topic = "process_${cleanName.lowercase().replace(' ', '_')}",
            content = "Taught process '$cleanName' (${timeBucket.label}): ${cleanSteps.joinToString(" -> ")}",
            confidence = 0.95f,
            timeBucket = timeBucket,
            nowMs = nowMs
        )
        return record
    }

    fun getPreferredMusicApp(): String? {
        val pref = ftsHelper.getAgenticMemoryByTopic("pref_music_app") ?: return null
        if (pref.confidence < 0.55f) return null
        val extracted = Regex("Prefers\\s+(.+?)\\s+for\\s+music", RegexOption.IGNORE_CASE)
            .find(pref.content)?.groupValues?.getOrNull(1)?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        // Never allow a browser or web fallback preference to hijack native music app playback
        if (isBrowserOrWebTarget(extracted)) return null
        return extracted
    }

    private fun isBrowserOrWebTarget(name: String): Boolean {
        val lower = name.trim().lowercase()
        if (lower.isEmpty()) return false
        return lower in setOf("chrome", "google chrome", "browser", "web", "web browser", "firefox", "edge", "brave", "opera", "safari", "internet", "samsung internet", "google") ||
            lower.contains("browser") ||
            lower.contains("chrome") ||
            lower.contains("firefox") ||
            lower.contains("brave") ||
            lower.contains("opera")
    }

    fun getPreferredMapsApp(): String? {
        val pref = ftsHelper.getAgenticMemoryByTopic("pref_maps_app") ?: return null
        if (pref.confidence < 0.55f) return null
        return Regex("Prefers\\s+(.+?)\\s+for\\s+navigation", RegexOption.IGNORE_CASE)
            .find(pref.content)?.groupValues?.getOrNull(1)?.trim()
    }

    fun getPreferredMessageChannel(): String? {
        val pref = ftsHelper.getAgenticMemoryByTopic("pref_message_channel") ?: return null
        if (pref.confidence < 0.55f) return null
        return Regex("Prefers\\s+(.+?)\\s+for\\s+messaging", RegexOption.IGNORE_CASE)
            .find(pref.content)?.groupValues?.getOrNull(1)?.trim()?.lowercase()
    }

    fun buildPersonalityProfileBlock(): String {
        val persona = currentPersonality()
        val name = friendlyUserName()
        val namePart = if (name.isNotBlank()) "User's name: $name." else "User's name: not set yet."
        return "Persona: ${persona.displayName} — ${persona.systemToneInstruction} $namePart"
    }

    fun buildPromptMemoryContext(query: String, nowMs: Long = System.currentTimeMillis()): String {
        val currentBucket = TimeOfDayBucket.fromTimestamp(nowMs)
        val prefsAndCorrections = ftsHelper.getAgenticMemories(
            types = setOf(AgenticMemoryType.CORRECTION, AgenticMemoryType.PREFERENCE, AgenticMemoryType.FACT),
            limit = 6
        )
        val episodes = ftsHelper.getAgenticMemories(
            types = setOf(AgenticMemoryType.EPISODE),
            limit = 3
        )
        val relevantProcesses = ftsHelper.getLearnedProcesses(
            timeBucket = currentBucket,
            minConfidence = 0.45f,
            limit = 4
        )

        if (prefsAndCorrections.isEmpty() && episodes.isEmpty() && relevantProcesses.isEmpty()) {
            return ""
        }

        val sb = StringBuilder()
        if (prefsAndCorrections.isNotEmpty()) {
            sb.appendLine("Learned User Preferences & Corrections:")
            prefsAndCorrections.forEach { m ->
                sb.appendLine("- [${m.memoryType.label} ${(m.confidence * 100).toInt()}%] ${m.content}")
            }
        }
        if (relevantProcesses.isNotEmpty()) {
            sb.appendLine("Learned User Processes for ${currentBucket.label}:")
            relevantProcesses.forEach { p ->
                sb.appendLine("- '${p.name}' (seen ${p.executionCount}x, ${(p.confidence * 100).toInt()}%): ${p.steps.joinToString(" -> ")}")
            }
        }
        if (episodes.isNotEmpty()) {
            sb.appendLine("Recent Interaction Episodes:")
            episodes.forEach { e ->
                val ago = ImportantNotificationFilter.formatRelativeTimestamp(e.lastReinforcedAtMs, nowMs)
                sb.appendLine("- ($ago) ${e.content}")
            }
        }
        return sb.toString().trim()
    }

    fun buildBriefingOpening(timeStr: String, batteryStr: String, nowMs: Long = System.currentTimeMillis()): String {
        val bucket = TimeOfDayBucket.fromTimestamp(nowMs)
        val name = friendlyUserName()
        val addressee = if (name.isNotBlank()) ", $name" else ""
        val timeGreeting = when (bucket) {
            TimeOfDayBucket.MORNING -> "Good morning$addressee"
            TimeOfDayBucket.AFTERNOON -> "Good afternoon$addressee"
            TimeOfDayBucket.EVENING -> "Good evening$addressee"
            else -> "Hello$addressee"
        }
        return when (currentPersonality()) {
            AssistantPersonality.WARM_COMPANION ->
                "$timeGreeting! Here's your personal briefing for $timeStr — battery is at $batteryStr."
            AssistantPersonality.SHARP_EXECUTIVE ->
                "Briefing$addressee ($timeStr • Battery $batteryStr):"
            AssistantPersonality.WITTY_COPILOT ->
                "$timeGreeting — briefing locked and loaded for $timeStr. Juice level is at $batteryStr."
            AssistantPersonality.CALM_MINIMALIST ->
                "$timeGreeting. $timeStr. Battery $batteryStr."
        }
    }

    fun buildAgenticBriefingInsights(nowMs: Long = System.currentTimeMillis()): String {
        val currentBucket = TimeOfDayBucket.fromTimestamp(nowMs)
        val todayStart = ImportantNotificationFilter.startOfDayMillis(nowMs)
        val recentToday = ftsHelper.getRecentBehaviorEvents(sinceMs = todayStart, limit = 15)
        val recentCommandsLower = recentToday.map { it.normalizedCommand.lowercase() }.toSet()

        val habits = ftsHelper.getLearnedProcesses(
            timeBucket = currentBucket,
            minConfidence = 0.50f,
            limit = 4
        ).filter { proc ->
            proc.autoSuggest && proc.steps.any { step ->
                val sLower = step.lowercase()
                recentCommandsLower.none { ran -> ran.contains(sLower) || sLower.contains(ran) }
            }
        }

        val parts = mutableListOf<String>()
        val topHabit = habits.firstOrNull()
        if (topHabit != null) {
            val stepsDesc = topHabit.steps.joinToString(", then ")
            val line = when (currentPersonality()) {
                AssistantPersonality.SHARP_EXECUTIVE ->
                    "Learned ${currentBucket.label.lowercase()} workflow '${topHabit.name}' (${topHabit.executionCount}x): $stepsDesc. Run it now?"
                AssistantPersonality.CALM_MINIMALIST ->
                    "Usual ${currentBucket.label.lowercase()} flow: $stepsDesc. Run '${topHabit.name}'?"
                AssistantPersonality.WITTY_COPILOT ->
                    "I noticed your usual ${currentBucket.label.lowercase()} groove (${topHabit.name}): $stepsDesc. Want me to kick it off?"
                AssistantPersonality.WARM_COMPANION ->
                    "From your ${currentBucket.label.lowercase()} habits, you usually follow up with ${topHabit.name} ($stepsDesc). Would you like me to run that for you?"
            }
            parts.add(line)
        } else {
            val prefMusic = getPreferredMusicApp()
            val lastEp = ftsHelper.getAgenticMemories(types = setOf(AgenticMemoryType.EPISODE), limit = 1)
                .firstOrNull { nowMs - it.lastReinforcedAtMs in 5L * 60_000L..12L * 3600_000L }
            if (lastEp != null) {
                val rel = ImportantNotificationFilter.formatRelativeTimestamp(lastEp.lastReinforcedAtMs, nowMs)
                parts.add("Recent context ($rel): ${lastEp.content}.")
            } else if (prefMusic != null && recentToday.isEmpty()) {
                parts.add("Ready with your preferred $prefMusic setup whenever you want music.")
            }
        }

        return parts.joinToString(" ")
    }

    fun formatLearnedSummary(nowMs: Long = System.currentTimeMillis()): String {
        val persona = currentPersonality()
        val prefs = ftsHelper.getAgenticMemories(
            types = setOf(AgenticMemoryType.PREFERENCE, AgenticMemoryType.CORRECTION, AgenticMemoryType.FACT),
            limit = 8
        )
        val processes = ftsHelper.getLearnedProcesses(limit = 6)
        val episodes = ftsHelper.getAgenticMemories(types = setOf(AgenticMemoryType.EPISODE), limit = 3)

        if (prefs.isEmpty() && processes.isEmpty() && episodes.isEmpty()) {
            return "Personality is set to ${persona.displayName}. I haven't learned enough of your habits yet — as you use apps, music, notifications, and multi-step routines, I'll automatically learn your processes and personalize your briefings."
        }

        val sb = StringBuilder()
        sb.append("Active personality: ${persona.displayName}.")
        friendlyUserName().takeIf { it.isNotBlank() }?.let { sb.append(" Greeting you as $it.") }
        if (prefs.isNotEmpty()) {
            sb.append(" Preferences: ")
            sb.append(prefs.take(4).joinToString("; ") { it.content })
            sb.append(".")
        }
        if (processes.isNotEmpty()) {
            sb.append(" Learned processes (${processes.size}): ")
            sb.append(
                processes.take(3).joinToString("; ") { p ->
                    "${p.name} (${p.timeBucket.label}, ${p.executionCount}x: ${p.steps.joinToString(" then ")})"
                }
            )
            sb.append(".")
        }
        if (episodes.isNotEmpty()) {
            val latest = episodes.first()
            val rel = ImportantNotificationFilter.formatRelativeTimestamp(latest.lastReinforcedAtMs, nowMs)
            sb.append(" Latest memory ($rel): ${latest.content}.")
        }
        return sb.toString().trim()
    }

    private fun canonicalizeIntentKey(cleanQuery: String, calls: List<ToolCallRequest>): String {
        val first = calls.firstOrNull()
        return when (first?.toolName) {
            "read_recent_messages" -> "read_notifications"
            "query_calendar_events" -> "check_calendar"
            "get_device_briefing" -> "device_briefing"
            "play_in_app" -> "play_music:${first.args["app_name"].orEmpty().ifBlank { "media" }}".lowercase()
            "search_in_app" -> "search:${first.args["app_name"].orEmpty()}".lowercase()
            "launch_app" -> "open:${first.args["app_name"].orEmpty()}".lowercase()
            else -> cleanQuery.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40)
        }
    }

    private fun isSubstantialProcessStep(topic: String): Boolean {
        if (topic.isBlank()) return false
        return topic in setOf("read_notifications", "check_calendar", "device_briefing") ||
            topic.startsWith("play_music") ||
            topic.startsWith("open:") ||
            topic.startsWith("search:")
    }

    private fun topicShortTitle(topic: String): String = when {
        topic == "read_notifications" -> "Notifications"
        topic == "check_calendar" -> "Schedule"
        topic == "device_briefing" -> "Briefing"
        topic.startsWith("play_music:") -> {
            val app = topic.removePrefix("play_music:").replaceFirstChar { it.titlecase(Locale.getDefault()) }
            if (app == "Media") "Music" else "$app Music"
        }
        topic.startsWith("open:") -> topic.removePrefix("open:").replaceFirstChar { it.titlecase(Locale.getDefault()) }
        topic.startsWith("search:") -> "${topic.removePrefix("search:").replaceFirstChar { it.titlecase(Locale.getDefault()) }} Search"
        else -> topic.replace('_', ' ').replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun toolCallToReadableStep(call: ToolCallRequest): String = when (call.toolName) {
        "launch_app" -> "open ${call.args["app_name"] ?: "app"}"
        "play_in_app" -> {
            val app = call.args["app_name"].orEmpty()
            val q = call.args["query"].orEmpty()
            if (app.isNotBlank()) "play $q on $app".trim() else "play $q".trim()
        }
        "screen_control_app" -> {
            val app = call.args["app_name"] ?: "app"
            val goal = call.args["goal"] ?: "control"
            val q = call.args["query_text"].orEmpty()
            "$goal $q in $app".trim()
        }
        "set_hardware_state" -> "turn ${call.args["state"] ?: "on"} ${call.args["feature"] ?: "setting"}"
        "read_recent_messages" -> "read my notifications"
        "query_calendar_events" -> "check my schedule"
        "get_device_briefing" -> "morning briefing"
        else -> call.toolName.replace('_', ' ')
    }

    private fun buildProcessDisplayName(
        steps: List<String>,
        bucket: TimeOfDayBucket,
        primaryApp: String
    ): String {
        val first = steps.firstOrNull().orEmpty().take(24)
        return when {
            primaryApp.isNotBlank() && steps.size >= 2 -> "${bucket.label} $primaryApp Flow"
            steps.size >= 2 -> "${bucket.label} ${first.replaceFirstChar { it.titlecase(Locale.getDefault()) }} Flow"
            else -> "${bucket.label} Routine"
        }
    }
}
