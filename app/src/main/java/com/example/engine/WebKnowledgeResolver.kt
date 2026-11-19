package com.example.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Resolves general-knowledge questions, unknown topics, and song/artist metadata cleanly over
 * HTTPS (DuckDuckGo Instant Answer + Wikipedia REST Summary + Apple iTunes Search + YouTube
 * direct watch resolution) whenever internet is available, and checks connectivity cleanly
 * when offline.
 */
object WebKnowledgeResolver {

    data class ResolvedMusicTrack(
        val title: String,
        val artist: String,
        val album: String = "",
        val youtubeVideoId: String? = null
    ) {
        val displayTitleAndArtist: String
            get() = if (artist.isNotBlank()) "$title by $artist" else title

        val searchQueryForPlayback: String
            get() = if (artist.isNotBlank()) "$title $artist" else title
    }

    @Volatile
    internal var testAnswerOverride: ((String) -> String?)? = null

    @Volatile
    internal var testMusicResolverOverride: ((String) -> ResolvedMusicTrack?)? = null

    @Volatile
    internal var testNetworkAvailableOverride: Boolean? = null

    private val conversationalPrefixRegex = Regex(
        "^(?:please\\s+|hey\\s+nova\\s+|nova\\s+|can\\s+you\\s+|could\\s+you\\s+|would\\s+you\\s+|do\\s+you\\s+know\\s+|tell\\s+me\\s+(?:about\\s+)?|explain\\s+)+",
        RegexOption.IGNORE_CASE
    )

    private val factualQuestionPrefixRegex = Regex(
        "^(?:what(?:'s|\\s+(?:is|are|was|were|did|do|does|can|song|artist|singer|album|band|country|city|mountain|river|ocean|planet|language|year|height|population|animal|element))|who(?:'s|\\b)|where(?:'s|\\s+(?:is|are|was|were|did|do|does))|when(?:'s|\\s+(?:is|was|were|did|do))|why(?:'s|\\s+(?:is|are|does|do|did))|how\\s+(?:tall|high|long|old|far|deep|wide|fast|big|heavy|hot|cold|many|much|does|do|did|is|are|was|were|to)|is\\s+|are\\s+|was\\s+|were\\s+|do\\s+|does\\s+|did\\s+|can\\s+|could\\s+|name\\s+the|which\\s+(?:song|artist|country|mountain|city|planet|ocean|river)|capital\\s+of|population\\s+of|height\\s+of|define|meaning\\s+of|tell\\s+me\\s+about|explain)\\b",
        RegexOption.IGNORE_CASE
    )

    private val personalOrDeviceActionRegex = Regex(
        "\\b(?:my|mine|me|call|called|calling|text|texted|calendar|schedule|meeting|meetings|event|events|reminder|reminders|alarm|alarms|timer|timers|battery|screen|installed|notification|notifications|message|messages|email|emails|contact|contacts|file|files|note|notes|shopping|todo|to-do|bluetooth|wifi|wi-fi|flashlight|torch|volume|brightness|routine|routines|process|habit|workflow|personality|memory|chrome|maps|waze|whatsapp|gallery|photos)\\b",
        RegexOption.IGNORE_CASE
    )

    private val conversationalOrAssistantDirectedRegex = Regex(
        "\\b(?:you|your|yours|yourself|you're|you've|you'd|nova|joke|jokes|riddle|riddles|poem|poems|story|stories|funny|laugh|humor|humour|roast|compliment|favorite|favourite|prefer|opinion|advice|bored|lonely|hello|hi|hey|how\\s+are\\s+you|who\\s+are\\s+you|what\\s+are\\s+you)\\b",
        RegexOption.IGNORE_CASE
    )

    private val personalActionVerbStartRegex = Regex(
        "^(?:open|launch|turn\\s+on|turn\\s+off|toggle|set|create|add|remove|delete|send|call|dial|text|navigate|directions|drive|plan|summarize|read|check\\s+my|list\\s+my|tell\\s+me\\s+a\\s+(?:joke|story|riddle|poem|fun\\s+fact)|write\\s+me|make\\s+up|invent|chat|talk\\s+to\\s+me)\\b",
        RegexOption.IGNORE_CASE
    )

    private val liveClockOrWeatherRegex = Regex(
        "\\b(?:current\\s+time|time\\s+(?:right\\s+)?now|what\\s+time\\s+is\\s+it|time\\s+and\\s+date|date\\s+and\\s+time|(?:check|tell\\s+me)\\s+(?:the\\s+)?(?:current\\s+)?time|today'?s?\\s+date|current\\s+date|what\\s+day\\s+is\\s+(?:it|today)|local\\s+weather|weather\\s+(?:today|tonight|now|forecast|in\\s+)|current\\s+status|your\\s+status|device\\s+status|sensor\\s+status|sensors|ambient\\s+light)\\b",
        RegexOption.IGNORE_CASE
    )

    private val explicitWebSearchRegex = Regex(
        "^(?:google|search\\s+(?:google|the\\s+web|online|wikipedia)\\s+for|look\\s+up)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )

    private val musicTopicHintRegex = Regex(
        "\\b(?:song|sang|sing|singer|artist|band|album|track|music|lyrics|composer|chords)\\b",
        RegexOption.IGNORE_CASE
    )

    private val youtubeVideoRendererIdRegex = Regex(
        "(?:videoRenderer|musicResponsiveListItemRenderer)[^\\n]{0,220}?(?:\"videoId\"|\\\\\"videoId\\\\\"|\\\\x22videoId\\\\x22)\\s*:\\s*(?:\"|\\\\\"|\\\\x22)([a-zA-Z0-9_-]{11})(?:\"|\\\\\"|\\\\x22)"
    )

    private val youtubeVideoIdRegex = Regex(
        "(?:\"videoId\"|\\\\\"videoId\\\\\"|\\\\x22videoId\\\\x22)\\s*:\\s*(?:\"|\\\\\"|\\\\x22)([a-zA-Z0-9_-]{11})(?:\"|\\\\\"|\\\\x22)"
    )

    fun isInternetAvailable(context: Context?): Boolean {
        testNetworkAvailableOverride?.let { return it }
        if (testAnswerOverride != null || testMusicResolverOverride != null) return true
        if (context == null || android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) {
            return true
        }
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return runCatching {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        }.getOrDefault(false)
    }

    fun extractExplicitWebLookupQuery(rawCommand: String): String? {
        val clean = rawCommand.trim().trimEnd('.', '!', '?')
        val m = explicitWebSearchRegex.find(clean) ?: return null
        val target = m.groupValues[1].trim()
        if (target.isBlank()) return null
        if (Regex("\\b(?:in|on|inside)\\s+(?:youtube|yt|spotify|play store|gallery|photos|files|notes|contacts|chrome)\\b", RegexOption.IGNORE_CASE).containsMatchIn(target)) {
            return null
        }
        return target
    }

    /**
     * Returns true for assistant-directed personal questions ("what is your favorite food?"),
     * creative requests ("tell me a joke", "write a poem"), or casual conversation that should
     * be answered directly by the local LLM rather than intercepted by Wikipedia/DuckDuckGo trivia.
     */
    fun isConversationalOrPersonalChat(rawQuery: String): Boolean {
        val clean = rawQuery.trim().trimEnd('.', '!', '?')
        if (clean.isEmpty()) return false
        if (explicitWebSearchRegex.containsMatchIn(clean)) return false
        val stripped = conversationalPrefixRegex.replace(clean, "").trim()
        // Exclude explicit factual lookups like "do you know who sang..." or "can you tell me what is the capital of..."
        val isPoliteFactualWrapper = Regex(
            "^(?:can\\s+you|could\\s+you|would\\s+you|do\\s+you\\s+know)\\s+(?:tell\\s+me\\s+)?(?:what|who|where|when|how\\s+(?:tall|high|long|old|far|many|much)|the\\s+(?:capital|height|population|meaning|definition))\\b",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(clean) && !conversationalOrAssistantDirectedRegex.containsMatchIn(stripped)
        if (isPoliteFactualWrapper) return false

        return conversationalOrAssistantDirectedRegex.containsMatchIn(stripped) ||
            Regex(
                "^(?:tell\\s+me\\s+a\\s+(?:joke|story|riddle|poem)|give\\s+me\\s+a\\s+(?:joke|riddle|compliment)|make\\s+me\\s+laugh|write\\s+(?:a|me\\s+a)\\s+(?:joke|poem|story|haiku))\\b",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(clean)
    }

    fun isGeneralKnowledgeQuestion(rawQuery: String): Boolean {
        val clean = rawQuery.trim().trimEnd('.', '!', '?')
        if (clean.length < 4) return false
        if (personalActionVerbStartRegex.containsMatchIn(clean)) return false
        if (liveClockOrWeatherRegex.containsMatchIn(clean)) return false
        if (isConversationalOrPersonalChat(clean)) return false
        val stripped = conversationalPrefixRegex.replace(clean, "").trim()
        if (stripped.isEmpty()) return false
        if (personalOrDeviceActionRegex.containsMatchIn(stripped)) return false
        if (factualQuestionPrefixRegex.containsMatchIn(clean) || factualQuestionPrefixRegex.containsMatchIn(stripped)) {
            return true
        }
        // Standalone unknown 2..6 word phrase/question (e.g. "Christmas in our hearts song", "Mount Apo")
        val wordCount = stripped.split(Regex("\\s+")).count { it.isNotBlank() }
        return rawQuery.trim().endsWith("?") || (musicTopicHintRegex.containsMatchIn(stripped) && wordCount in 2..8)
    }

    /**
     * Broader check used when Tier 1 & local memory don't recognize a query: checks if Nova
     * should consult the internet instead of returning "model missing" when connectivity exists.
     */
    fun isInformationalWebFallbackCandidate(rawQuery: String): Boolean {
        val clean = rawQuery.trim().trimEnd('.', '!', '?')
        if (clean.length < 3) return false
        if (personalActionVerbStartRegex.containsMatchIn(clean)) return false
        if (liveClockOrWeatherRegex.containsMatchIn(clean)) return false
        if (isConversationalOrPersonalChat(clean)) return false
        if (personalOrDeviceActionRegex.containsMatchIn(clean)) return false
        return true
    }

    fun extractSearchTopic(rawQuery: String): String {
        var clean = rawQuery.trim().trimEnd('?', '.', '!')
        clean = conversationalPrefixRegex.replace(clean, "").trim()
        clean = clean.replace(
            Regex(
                "^(?:who\\s+(?:is|are|was|were|sang|wrote|composed|created|made)|what(?:'s|\\s+is|\\s+are|\\s+was|\\s+were)|where(?:'s|\\s+is|\\s+are|\\s+was|\\s+were)|when\\s+(?:was|were|did|is)|how\\s+(?:tall|high|long|old|far|deep|wide)\\s+(?:is|are|was)|define|meaning\\s+of)\\s+(?:the\\s+(?:song|singer\\s+of|artist\\s+of|meaning\\s+of)\\s+|a\\s+|an\\s+)?",
                RegexOption.IGNORE_CASE
            ),
            ""
        ).trim()
        return clean.ifBlank { rawQuery.trim().trimEnd('?', '.', '!') }
    }

    suspend fun fetchCleanAnswer(rawQuery: String, detailed: Boolean = false): String? {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return null
        testAnswerOverride?.let { override ->
            return override.invoke(trimmed)?.let { sanitizeAnswerForSpeech(it, detailed) }?.takeIf { it.isNotBlank() }
        }
        if (android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) {
            return null
        }
        return withContext(Dispatchers.IO) {
            val topic = extractSearchTopic(trimmed)
            val musicHint = musicTopicHintRegex.containsMatchIn(trimmed)
            val primaryAnswer = fetchDuckDuckGoInstantAnswer(topic, detailed)
                ?: fetchWikipediaAnswer(topic, detailed)
                ?: if (!topic.equals(trimmed, ignoreCase = true)) {
                    fetchDuckDuckGoInstantAnswer(trimmed, detailed) ?: fetchWikipediaAnswer(trimmed, detailed)
                } else {
                    null
                }
            if (!primaryAnswer.isNullOrBlank()) return@withContext primaryAnswer

            if (musicHint || !trimmed.equals(topic, ignoreCase = true)) {
                fetchMusicMetadataSummary(topic)
            } else {
                fetchMusicMetadataSummary(topic)
            }
        }
    }

    suspend fun resolveMusicTrack(rawQuery: String, pickRandom: Boolean = false): ResolvedMusicTrack? {
        val clean = rawQuery.trim()
        if (clean.isEmpty()) return null
        testMusicResolverOverride?.let { return it.invoke(clean) }
        if (android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)) {
            return null
        }
        return withContext(Dispatchers.IO) {
            val itunesTrack = fetchItunesTrack(clean, pickRandom = pickRandom)
            val searchPhrase = itunesTrack?.searchQueryForPlayback ?: clean
            val videoId = fetchDirectYouTubeVideoId("$searchPhrase audio")
                ?: fetchDirectYouTubeVideoId(searchPhrase)
            when {
                itunesTrack != null -> itunesTrack.copy(youtubeVideoId = videoId)
                videoId != null -> ResolvedMusicTrack(title = clean, artist = "", youtubeVideoId = videoId)
                else -> null
            }
        }
    }

    private fun fetchMusicMetadataSummary(query: String): String? {
        val track = fetchItunesTrack(query) ?: return null
        val albumNote = if (track.album.isNotBlank() && !track.album.equals(track.title, ignoreCase = true)) {
            " from the album ${track.album}"
        } else {
            ""
        }
        return sanitizeAnswerForSpeech(
            "'${track.title}' is a song by ${track.artist}$albumNote."
        )
    }

    private fun fetchItunesTrack(query: String, pickRandom: Boolean = false): ResolvedMusicTrack? {
        val encoded = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrNull() ?: return null
        val limit = if (pickRandom) 12 else 1
        val jsonStr = httpGet("https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=$limit")
            ?: return null
        return runCatching {
            val arr = JSONObject(jsonStr).optJSONArray("results") ?: return null
            if (arr.length() == 0) return null
            val validTracks = buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val trackName = item.optString("trackName").trim()
                    val artistName = item.optString("artistName").trim()
                    val albumName = item.optString("collectionName").trim()
                    if (trackName.isNotEmpty() && artistName.isNotEmpty()) {
                        add(
                            ResolvedMusicTrack(
                                title = ScreenTextSanitizer.cleanUiLabelForSpeech(trackName),
                                artist = ScreenTextSanitizer.cleanUiLabelForSpeech(artistName),
                                album = ScreenTextSanitizer.cleanUiLabelForSpeech(albumName)
                            )
                        )
                    }
                }
            }
            if (validTracks.isEmpty()) return null
            if (pickRandom) validTracks.random() else validTracks.first()
        }.getOrNull()
    }

    internal fun extractYouTubeVideoIdFromHtml(html: String): String? {
        if (html.isBlank()) return null
        return youtubeVideoRendererIdRegex.find(html)?.groupValues?.getOrNull(1)
            ?: youtubeVideoIdRegex.find(html)?.groupValues?.getOrNull(1)
    }

    private fun fetchDirectYouTubeVideoId(query: String): String? {
        val encoded = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrNull() ?: return null
        val html = httpGet(
            urlString = "https://www.youtube.com/results?search_query=$encoded&hl=en",
            accept = "text/html,application/xhtml+xml",
            desktopBrowserUa = true,
            extraHeaders = mapOf(
                "Accept-Language" to "en-US,en;q=0.9",
                "Cookie" to "CONSENT=YES+cb.20230531-04-p0.en+FX+908"
            )
        ) ?: return null
        return extractYouTubeVideoIdFromHtml(html)
    }

    private fun fetchDuckDuckGoInstantAnswer(query: String, detailed: Boolean): String? {
        val encoded = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrNull() ?: return null
        val jsonStr = httpGet("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
            ?: return null
        return runCatching {
            val obj = JSONObject(jsonStr)
            val direct = listOf(
                obj.optString("Answer"),
                obj.optString("AbstractText"),
                obj.optString("Definition")
            ).firstOrNull { it.isNotBlank() }
            direct?.let { sanitizeAnswerForSpeech(it, detailed) }?.takeIf { it.length >= 12 }
        }.getOrNull()
    }

    private fun fetchWikipediaAnswer(query: String, detailed: Boolean): String? {
        val encoded = runCatching { URLEncoder.encode(query, "UTF-8") }.getOrNull() ?: return null
        val searchJson = httpGet(
            "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srlimit=3&format=json&utf8=1"
        ) ?: return null

        val titles = runCatching {
            val arr = JSONObject(searchJson)
                .optJSONObject("query")
                ?.optJSONArray("search")
                ?: return null
            buildList {
                for (i in 0 until arr.length()) {
                    val title = arr.optJSONObject(i)?.optString("title").orEmpty().trim()
                    if (title.isNotEmpty()) add(title)
                }
            }
        }.getOrDefault(emptyList())

        val orderedTitles = titles.sortedBy { if (it.startsWith("List of ", ignoreCase = true)) 1 else 0 }
        for (title in orderedTitles.take(2)) {
            val slug = runCatching { URLEncoder.encode(title.replace(' ', '_'), "UTF-8") }.getOrNull() ?: continue
            val summaryJson = httpGet("https://en.wikipedia.org/api/rest_v1/page/summary/$slug") ?: continue
            val extract = runCatching {
                val obj = JSONObject(summaryJson)
                if (obj.optString("type").equals("disambiguation", ignoreCase = true)) return@runCatching null
                obj.optString("extract")
            }.getOrNull()
            val clean = extract?.let { sanitizeAnswerForSpeech(it, detailed) }
            if (!clean.isNullOrBlank() && clean.length >= 20) {
                return clean
            }
        }
        return null
    }

    private fun httpGet(
        urlString: String,
        accept: String = "application/json",
        desktopBrowserUa: Boolean = false,
        extraHeaders: Map<String, String> = emptyMap()
    ): String? {
        var conn: HttpURLConnection? = null
        return runCatching {
            conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2800
                readTimeout = 4000
                instanceFollowRedirects = true
                val ua = if (desktopBrowserUa) {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                } else {
                    "Mozilla/5.0 (Linux; Android 14) NovaAssistant/1.0"
                }
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept", accept)
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            if (conn!!.responseCode !in 200..299) return null
            BufferedReader(InputStreamReader(conn!!.inputStream)).use { reader ->
                reader.readText()
            }
        }.getOrNull().also {
            runCatching { conn?.disconnect() }
        }
    }

    fun sanitizeAnswerForSpeech(raw: String, detailed: Boolean = false): String {
        var text = raw.trim()
        if (text.isEmpty()) return ""
        text = text.replace(Regex("<[^>]+>"), " ")
        text = text.replace(Regex("\\[\\d+\\]|\\[citation needed\\]", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("\\([^)]*(?:/[^)]*/|pronunciation|listen)[^)]*\\)", RegexOption.IGNORE_CASE), "")
        text = text.replace(Regex("\\s*\\(\\s*[;,]?\\s*\\)"), "")
        text = ScreenTextSanitizer.cleanUiLabelForSpeech(text)
        text = text.replace(Regex("\\s+([.,;!?])"), "$1")
        text = text.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return ""

        val maxSentences = if (detailed) 4 else 2
        val maxChars = if (detailed) 460 else 260
        val sentences = Regex("[^.!?]+[.!?]").findAll(text).map { it.value.trim() }.toList()
        if (sentences.isNotEmpty()) {
            val selected = mutableListOf<String>()
            for (s in sentences) {
                if (selected.isEmpty() || (selected.size < maxSentences && selected.sumOf { it.length } + s.length <= maxChars)) {
                    selected.add(s)
                } else {
                    break
                }
            }
            return selected.joinToString(" ").trim()
        }
        return text.take(maxChars).trim().let { if (it.endsWith(".")) it else "$it." }
    }
}
