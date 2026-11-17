package com.example.engine

import com.example.data.SkillSpec
import com.example.processA.EmbeddingGemmaRuntime
import kotlin.math.sqrt

/**
 * Tier 1.5: On-Device Semantic Lookup in Process A.
 * Uses real `EmbeddingGemma 2` via `EmbeddingEngine` when installed & ready,
 * and falls back transparently to lexical/trigram candidate selection (marking
 * `usedNeuralEmbedding = false`) when EmbeddingGemma has not been downloaded.
 */
object SemanticLookupEngine {

    private const val DIMS = 128

    data class SemanticMatchResult(
        val topScore: Float,
        val candidateSkills: List<SkillSpec>,
        val topFewShotExamples: List<String>,
        val isDirectCacheHit: Boolean,
        val usedNeuralEmbedding: Boolean = false
    )

    private val rawUtterances = mapOf(
        "device-hardware-control" to listOf(
            "turn on flashlight torch wifi bluetooth do not disturb",
            "set volume brightness mute unmute screen dark quiet"
        ),
        "clock-alarms-timers" to listOf(
            "set a timer countdown alarm wake me up snooze focus timer",
            "how much time left stopwatch alarm for morning"
        ),
        "media-playback-control" to listOf(
            "play pause skip next song music podcast spotify track",
            "play lo-fi chill beats resume playback"
        ),
        "local-device-search" to listOf(
            "find PDF receipt flight bill screenshot search files local documents",
            "search indexed files contacts notes local fts5"
        ),
        "personal-memory-vault" to listOf(
            "remember I parked basement pillar locker code personal note",
            "where did I park recall memory note saved fact"
        ),
        "messaging-omni-send" to listOf(
            "summarize unread texts messages whatsapp sms reply send message",
            "text contact unread notifications inbox"
        ),
        "phone-calls-manager" to listOf(
            "call contact dial speakerphone answer decline missed calls",
            "phone call voice call ring"
        ),
        "calendar-schedule-manager" to listOf(
            "what is on my calendar schedule today meetings free slot",
            "add calendar event schedule appointment tomorrow"
        ),
        "ui-autonomous-navigator" to listOf(
            "open app click tap order pay checkout confirm navigate third party app",
            "tap button fill text scroll screen inside app"
        ),
        "screen-context-assistant" to listOf(
            "what is on my screen summarize this page read screen translate",
            "explain current open app screen context"
        ),
        "compound-routine-orchestrator" to listOf(
            "good morning routine focus workflow sync apps",
            "multi step combine search and send message"
        ),
        "app-knowledge-controller" to listOf(
            "what apps are installed on my phone list apps open app launch",
            "which app can play music navigate maps edit docs what can app do"
        ),
        "app-media-streaming" to listOf(
            "open youtube music spotify youtube vlc and play a song artist playlist track video",
            "play music on youtube music play podcast in spotify"
        ),
        "app-search-and-action" to listOf(
            "search inside app youtube chrome google maps waze directions navigate to place",
            "look up video product or location inside installed app"
        ),
        "alexa-utility-briefings" to listOf(
            "what time is it date battery level morning briefing daily summary",
            "add item to shopping list todo list read reminders"
        )
    )

    @Volatile
    private var cachedNeuralPrototypes: Map<String, List<FloatArray>>? = null

    private val lexicalPrototypes: Map<String, List<FloatArray>> by lazy {
        rawUtterances.mapValues { (skillId, phrases) ->
            val skill = SkillCatalog.findById(skillId)
            val allTexts = phrases + (skill?.fewShotExamples ?: emptyList())
            allTexts.map { lexicalEmbed(it) }
        }
    }

    /**
     * Async lookup that uses real EmbeddingGemma 2 when available in Process A.
     */
    suspend fun lookupWithRuntime(
        query: String,
        embeddingRuntime: EmbeddingGemmaRuntime?
    ): SemanticMatchResult {
        if (embeddingRuntime != null && embeddingRuntime.isModelFilePresent()) {
            val queryVec = embeddingRuntime.embedQuery(query)
            if (queryVec != null) {
                val protoMap = getOrComputeNeuralPrototypes(embeddingRuntime)
                if (protoMap.isNotEmpty()) {
                    val scored = SkillCatalog.enabledSkills.map { skill ->
                        val protos = protoMap[skill.id].orEmpty()
                        val best = protos.maxOfOrNull { v ->
                            EmbeddingGemmaRuntime.cosineSimilarity(queryVec, v)
                        } ?: 0f
                        skill to best.coerceIn(0f, 0.99f)
                    }.sortedByDescending { it.second }
                    return buildResult(query, scored, usedNeural = true)
                }
            }
        }
        return lookup(query)
    }

    private suspend fun getOrComputeNeuralPrototypes(
        runtime: EmbeddingGemmaRuntime
    ): Map<String, List<FloatArray>> {
        cachedNeuralPrototypes?.let { if (it.isNotEmpty()) return it }
        val built = mutableMapOf<String, List<FloatArray>>()
        for (skill in SkillCatalog.enabledSkills) {
            val texts = rawUtterances[skill.id].orEmpty() + skill.fewShotExamples
            val vecs = texts.mapNotNull { runtime.embedDocument(skill.title, it) }
            if (vecs.isNotEmpty()) {
                built[skill.id] = vecs
            }
        }
        if (built.isNotEmpty()) {
            cachedNeuralPrototypes = built
        }
        return built
    }

    fun invalidateNeuralCache() {
        cachedNeuralPrototypes = null
    }

    fun lookup(query: String): SemanticMatchResult {
        val queryVec = lexicalEmbed(query)
        val scored = SkillCatalog.enabledSkills.map { skill ->
            val prototypes = lexicalPrototypes[skill.id].orEmpty()
            val bestProtoScore = prototypes.maxOfOrNull { vec -> cosine(queryVec, vec) } ?: 0f
            val keywordBoost = computeDomainBoost(query, skill)
            val combined = (bestProtoScore * 0.72f + keywordBoost * 0.28f).coerceIn(0f, 0.99f)
            skill to combined
        }.sortedByDescending { it.second }

        return buildResult(query, scored, usedNeural = false)
    }

    private fun buildResult(
        query: String,
        scored: List<Pair<SkillSpec, Float>>,
        usedNeural: Boolean
    ): SemanticMatchResult {
        val topSkill = scored.firstOrNull()
        val topScore = topSkill?.second ?: 0f
        val candidates = scored
            .filter { it.second >= 0.28f }
            .take(3)
            .map { it.first }
            .ifEmpty { listOfNotNull(topSkill?.first) }

        val fewShots = candidates
            .flatMap { it.fewShotExamples }
            .take(3)

        val isSingleStep = !InputNormalizer.normalize(query).hasConditionalLogic &&
            topSkill?.first?.requiresThinking == false &&
            topScore >= 0.92f

        return SemanticMatchResult(
            topScore = topScore,
            candidateSkills = candidates,
            topFewShotExamples = fewShots,
            isDirectCacheHit = isSingleStep,
            usedNeuralEmbedding = usedNeural
        )
    }

    private fun computeDomainBoost(query: String, skill: SkillSpec): Float {
        val q = query.lowercase()
        val domainKeywords = when (skill.id) {
            "local-device-search" -> listOf("find", "pdf", "receipt", "flight", "bill", "password", "screenshot", "file", "search")
            "personal-memory-vault" -> listOf("remember", "parked", "park", "code", "locker", "where did i")
            "messaging-omni-send" -> listOf("text", "texts", "unread", "message", "messages", "summarize", "whatsapp", "sms", "reply")
            "ui-autonomous-navigator" -> listOf("pay", "transfer", "order", "checkout", "open", "tap", "click", "scroll", "email")
            "compound-routine-orchestrator" -> listOf("good morning", "morning", "bedtime", "routine", "and send", "then", "sync")
            "clock-alarms-timers" -> listOf("timer", "alarm", "snooze", "minute", "minutes", "focus")
            "device-hardware-control" -> listOf("flashlight", "torch", "wifi", "bluetooth", "volume", "brightness", "mute", "dnd")
            "calendar-schedule-manager" -> listOf("calendar", "schedule", "meeting", "events", "free", "today")
            "phone-calls-manager" -> listOf("call", "dial", "speakerphone", "missed", "contact", "contacts")
            "media-playback-control" -> listOf("play", "music", "song", "spotify", "pause", "skip")
            "screen-context-assistant" -> listOf("screen", "page", "translate", "summarize this", "read this", "what is on")
            "app-knowledge-controller" -> listOf("apps", "installed", "launch", "open", "how to use", "which app", "what can")
            "app-media-streaming" -> listOf("youtube music", "youtube", "spotify", "music", "song", "play", "playlist", "podcast", "video")
            "app-search-and-action" -> listOf("search", "navigate", "directions", "maps", "waze", "chrome", "browser", "youtube")
            "alexa-utility-briefings" -> listOf("shopping", "todo", "list", "battery", "time", "date", "briefing", "morning")
            else -> emptyList()
        }
        val hits = domainKeywords.count { q.contains(it) }
        return when {
            hits >= 2 -> 0.96f
            hits == 1 -> 0.72f
            else -> 0.1f
        }
    }

    private fun lexicalEmbed(text: String): FloatArray {
        val vec = FloatArray(DIMS)
        val clean = text.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").trim()
        val words = clean.split(Regex("\\s+")).filter { it.isNotEmpty() }

        for (w in words) {
            val h = (w.hashCode() and 0x7FFFFFFF) % DIMS
            vec[h] += 2.0f
            if (w.length >= 3) {
                for (i in 0..w.length - 3) {
                    val tri = w.substring(i, i + 3)
                    val th = (tri.hashCode() and 0x7FFFFFFF) % DIMS
                    vec[th] += 1.0f
                }
            }
        }
        var norm = 0f
        for (v in vec) norm += v * v
        val mag = sqrt(norm)
        if (mag > 0f) {
            for (i in vec.indices) vec[i] /= mag
        }
        return vec
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot.coerceIn(0f, 1f)
    }
}
