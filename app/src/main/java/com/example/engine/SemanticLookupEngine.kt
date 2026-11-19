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
            "turn on turn off toggle flashlight torch wifi bluetooth do not disturb dnd",
            "set volume brightness mute unmute silent vibrate display screen dark quiet"
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
            "remember I parked basement pillar locker code personal note wifi",
            "where did I park what is my locker code recall memory note saved fact"
        ),
        "messaging-omni-send" to listOf(
            "summarize unread texts messages important notifications whatsapp sms reply send message",
            "text contact unread notifications inbox missed alerts"
        ),
        "phone-calls-manager" to listOf(
            "call contact dial speakerphone answer call decline reject incoming call",
            "phone call voice call ring hang up"
        ),
        "calendar-schedule-manager" to listOf(
            "what is on my calendar schedule today meetings free slot reminders",
            "create reminder add calendar event schedule appointment tomorrow"
        ),
        "ui-autonomous-navigator" to listOf(
            "open app click tap order pay checkout confirm navigate third party app",
            "tap button fill text scroll screen inside app"
        ),
        "screen-context-assistant" to listOf(
            "what is on my screen summarize this page read screen translate",
            "explain current open app screen context"
        ),
        "local-screen-control" to listOf(
            "tap button click element type into search box scroll down scroll up screen control",
            "automate app locally press play click search control screen"
        ),
        "compound-routine-orchestrator" to listOf(
            "good morning routine focus mode bedtime routine sync phone apps",
            "record routine floating control run recorded routine conditional if statement step delay",
            "multi step combine search briefing timer dnd tap swipe automate routine"
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
        ),
        "general-knowledge-qa" to listOf(
            "what is the highest mountain in the philippines capital of country population height facts trivia",
            "who is who invented when did why does how tall define meaning explain science history geography"
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
                        val neuralBest = protos.maxOfOrNull { v ->
                            EmbeddingGemmaRuntime.cosineSimilarity(queryVec, v)
                        }?.coerceIn(0f, 1f) ?: 0f
                        val lexicalPrior = computeDomainBoost(query, skill)
                        val blended = (neuralBest * 0.84f + lexicalPrior * 0.16f).coerceIn(0f, 0.99f)
                        skill to blended
                    }.sortedByDescending { it.second }
                    return buildResult(query, scored, usedNeural = true)
                }
            }
        }
        return lookup(query)
    }

    suspend fun warmPrototypes(embeddingRuntime: EmbeddingGemmaRuntime): Boolean {
        if (!embeddingRuntime.isModelFilePresent()) return false
        return getOrComputeNeuralPrototypes(embeddingRuntime).isNotEmpty()
    }

    fun buildCleanSkillPrototypes(skill: SkillSpec): List<String> {
        val utterances = rawUtterances[skill.id].orEmpty()
        val fewShotUtterances = skill.fewShotExamples
            .map { it.substringBefore("->").trim() }
            .filter { it.isNotBlank() && !it.startsWith("{") }
        return (listOf(skill.description) + utterances + fewShotUtterances)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private suspend fun getOrComputeNeuralPrototypes(
        runtime: EmbeddingGemmaRuntime
    ): Map<String, List<FloatArray>> {
        cachedNeuralPrototypes?.let { if (it.isNotEmpty()) return it }

        val flatRequests = mutableListOf<Pair<String, Pair<String, String>>>()
        for (skill in SkillCatalog.enabledSkills) {
            for (phrase in buildCleanSkillPrototypes(skill)) {
                flatRequests.add(skill.id to (skill.title to phrase))
            }
        }
        if (flatRequests.isEmpty()) return emptyMap()

        val batchVectors = runtime.embedDocumentsBatch(flatRequests.map { it.second })
        val built = linkedMapOf<String, MutableList<FloatArray>>()
        flatRequests.forEachIndexed { idx, (skillId, _) ->
            val vec = batchVectors.getOrNull(idx) ?: return@forEachIndexed
            built.getOrPut(skillId) { mutableListOf() }.add(vec)
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
        val minCandidateThreshold = if (usedNeural) 0.24f else 0.28f
        val candidates = scored
            .filter { it.second >= minCandidateThreshold }
            .take(3)
            .map { it.first }
            .ifEmpty { listOfNotNull(topSkill?.first) }

        val fewShots = candidates
            .flatMap { it.fewShotExamples.take(2) }
            .distinct()
            .take(5)

        val directThreshold = if (usedNeural) 0.78f else 0.92f
        val isSingleStep = !InputNormalizer.normalize(query).hasConditionalLogic &&
            topSkill?.first?.requiresThinking == false &&
            topScore >= directThreshold

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
            "personal-memory-vault" -> listOf("remember", "parked", "park", "code", "locker", "where did i", "what is my", "recall")
            "messaging-omni-send" -> listOf("text", "texts", "unread", "message", "messages", "notifications", "summarize", "whatsapp", "sms", "reply")
            "ui-autonomous-navigator" -> listOf("pay", "transfer", "order", "checkout", "open", "tap", "click", "scroll", "email")
            "compound-routine-orchestrator" -> listOf("good morning", "morning", "focus mode", "bedtime", "routine", "routines", "record routine", "floating", "conditional", "delay", "and send", "then", "sync")
            "clock-alarms-timers" -> listOf("timer", "alarm", "snooze", "minute", "minutes", "focus")
            "device-hardware-control" -> listOf(
                "flashlight", "torch", "wifi", "wi-fi", "bluetooth", "volume", "brightness",
                "brighter", "dimmer", "mute", "unmute", "dnd", "do not disturb", "silent",
                "vibrate", "display", "turn on", "turn off", "disable", "enable", "toggle"
            )
            "calendar-schedule-manager" -> listOf("calendar", "schedule", "meeting", "events", "reminder", "remind", "free", "today")
            "phone-calls-manager" -> listOf("call", "dial", "speakerphone", "answer", "decline", "reject", "missed", "contact", "contacts")
            "media-playback-control" -> listOf("play", "music", "song", "spotify", "pause", "skip", "resume", "track")
            "screen-context-assistant" -> listOf("screen", "page", "translate", "summarize this", "read this", "what is on")
            "local-screen-control" -> listOf("tap", "click", "press", "type", "enter", "scroll", "swipe", "automate", "control")
            "app-knowledge-controller" -> listOf("apps", "installed", "launch", "open", "how to use", "which app", "what can")
            "app-media-streaming" -> listOf("youtube music", "yt music", "ytm", "youtube", "spotify", "music", "song", "play", "playlist", "podcast", "video")
            "app-search-and-action" -> listOf("search", "navigate", "directions", "maps", "waze", "chrome", "browser", "youtube")
            "alexa-utility-briefings" -> listOf("shopping", "todo", "list", "battery", "time", "date", "briefing", "morning")
            "general-knowledge-qa" -> listOf("highest", "mountain", "capital", "country", "population", "invented", "define", "meaning", "history", "tallest", "largest", "who is", "who was", "what is the", "where is", "when did", "how tall", "how many")
            else -> emptyList()
        }
        if (skill.id == "general-knowledge-qa" && WebKnowledgeResolver.isGeneralKnowledgeQuestion(query)) {
            return 0.98f
        }
        val hits = domainKeywords.count { kw -> containsWholePhrase(q, kw) }
        return when {
            hits >= 2 -> 0.96f
            hits == 1 -> 0.72f
            else -> 0.1f
        }
    }

    private fun containsWholePhrase(text: String, phrase: String): Boolean {
        val pattern = "\\b${Regex.escape(phrase)}\\b"
        return Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
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
