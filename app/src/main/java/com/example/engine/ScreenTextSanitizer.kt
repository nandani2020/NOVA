package com.example.engine

import com.example.data.UiNodeSnapshot

/**
 * Sanitizes Android Accessibility node labels and web/search result screens into
 * clean, natural spoken text — stripping synthetic widget placeholders ("Button (...)"),
 * URLs, domain breadcrumbs, technical view IDs, and SERP/browser UI chrome, while
 * extracting direct featured-snippet answers when available.
 */
object ScreenTextSanitizer {

    private val stateTagRegex = Regex("\\[(?:ON|OFF|Selected|Checked|Not checked|[^\\]]{1,24})\\]", RegexOption.IGNORE_CASE)
    private val syntheticPrefixRegex = Regex("^(?:Button|Toggle|Field|Text input|Switch|View|ImageButton|CheckBox)\\s*\\(", RegexOption.IGNORE_CASE)
    private val fullUrlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
    private val domainBreadcrumbRegex = Regex(
        "(?:https?://)?(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:com|org|net|edu|gov|io|co|ph|uk|info|biz)(?:/[^\\s]*)?(?:\\s*[›>]\\s*[^\\s]+)*",
        RegexOption.IGNORE_CASE
    )
    private val leadingBreadcrumbRegex = Regex("^(?:[›>]\\s*\\S+\\s*)+")
    private val inlineWidgetTagRegex = Regex(
        "\\s*\\((?:Button|ImageButton|ImageView|TextView|ViewGroup|FrameLayout|LinearLayout|RelativeLayout|ConstraintLayout|WebView|EditText|Switch|CheckBox|RecyclerView|Link|Tab|Icon)\\)",
        RegexOption.IGNORE_CASE
    )
    private val trailingRoleSuffixRegex = Regex("\\s*[-–|•]\\s*(?:button|link|tab|toggle|checkbox|image)\\s*$", RegexOption.IGNORE_CASE)
    private val snakeTechIdRegex = Regex("^[a-z]{1,6}(?:_[a-z0-9]+)+$|^(?:btn|tv|iv|et|ll|rl|cl|rv|fab|nav)_[a-z0-9_]+$", RegexOption.IGNORE_CASE)
    private val androidClassRegex = Regex("^(?:android|androidx|com\\.google\\.android)\\.[a-zA-Z0-9_.]+$")
    private val numericOrPunctOnlyRegex = Regex("^[0-9:./\\s,%+\\-–—•›|]+$")

    private val noiseLabels = setOf(
        "home", "explore", "samples", "shorts", "subscriptions", "library", "you",
        "back", "navigate up", "close", "dismiss", "cancel", "more options", "more",
        "account", "filters", "all", "songs", "videos", "albums", "artists",
        "playlists", "podcasts", "profiles", "top", "latest", "channels",
        "upgrade", "get music premium", "sign in", "log in", "cast",
        "address bar", "search or type web address", "search or type url", "search or type",
        "tabs", "tab switcher", "bookmarks", "incognito", "menu", "reload",
        "refresh", "forward", "skip ad", "accept all", "reject all", "no thanks",
        "skip to main content", "accessibility links", "accessibility help",
        "feedback", "send feedback", "ai overview", "ai mode", "dive deeper in ai mode",
        "search modes", "search tools", "all filters", "safesearch", "tools",
        "more results", "people also ask", "people also search for", "related searches",
        "about this result", "about featured snippets", "featured snippet",
        "open in app", "open in chrome", "privacy", "privacy policy", "terms",
        "cookies", "manage cookies", "images", "short videos", "forums",
        "books", "flights", "finance", "news", "shopping", "maps", "web",
        "sponsored", "ad", "clear", "clear query", "voice search", "search by voice",
        "lens", "google", "google search", "google lens", "listen", "settings"
    )

    private val queryStopWords = setOf(
        "what", "is", "are", "was", "were", "the", "a", "an", "in", "on", "at", "of",
        "to", "for", "from", "by", "with", "who", "where", "when", "why", "how",
        "tell", "me", "about", "can", "you", "please", "do", "does", "did", "which",
        "name", "tall", "high", "long", "many", "much", "define", "meaning"
    )

    fun cleanUiLabelForSpeech(raw: String): String {
        if (raw.isBlank()) return ""
        if (syntheticPrefixRegex.containsMatchIn(raw.trim())) return ""

        var text = raw.trim()
        // Convert markdown links [Title](url) to Title
        text = text.replace(Regex("\\[([^\\]]+)\\]\\(https?://[^)]+\\)")) { it.groupValues[1] }
        text = stateTagRegex.replace(text, " ")
        text = domainBreadcrumbRegex.replace(text, " ")
        text = fullUrlRegex.replace(text, " ")
        text = leadingBreadcrumbRegex.replace(text, " ")
        text = text.replace("›", " ")
        text = inlineWidgetTagRegex.replace(text, "")
        text = trailingRoleSuffixRegex.replace(text, "")
        text = text.replace(Regex("\\s*[•|]\\s*$"), "")
        text = text.replace(Regex("^\\s*[•|\\-–—]+\\s*"), "")
        text = text.replace(Regex("\\s+"), " ").trim()
        return text
    }

    fun isTechnicalOrUiChromeNode(rawLabel: String): Boolean {
        val trimmed = rawLabel.trim()
        if (trimmed.isEmpty()) return true
        if (syntheticPrefixRegex.containsMatchIn(trimmed)) return true
        if (androidClassRegex.matches(trimmed)) return true
        if (snakeTechIdRegex.matches(trimmed)) return true

        val clean = cleanUiLabelForSpeech(trimmed)
        if (clean.length < 3) return true
        if (numericOrPunctOnlyRegex.matches(clean)) return true
        if (snakeTechIdRegex.matches(clean) || androidClassRegex.matches(clean)) return true

        val lower = clean.lowercase()
        if (lower in noiseLabels) return true
        if (lower.startsWith("filter ") ||
            lower.startsWith("switch ") ||
            lower.startsWith("search for ") ||
            lower.startsWith("about this result") ||
            lower.startsWith("more options for") ||
            lower.startsWith("skip to ") ||
            lower.startsWith("accessibility ") ||
            lower.startsWith("open in ") ||
            lower.startsWith("see results about") ||
            lower == "search" ||
            lower.startsWith("search ") && lower.length <= 24 ||
            lower.contains("clear query") ||
            lower.contains("voice search") ||
            lower.contains("microphone") ||
            lower.contains("navigate up") ||
            lower.contains("notifications") ||
            lower.contains("cast to") ||
            lower == "cast"
        ) {
            return true
        }
        return false
    }

    fun filterAndCleanChoices(
        nodes: List<UiNodeSnapshot>,
        maxChoices: Int = 5
    ): List<UiNodeSnapshot> {
        val seen = linkedSetOf<String>()
        return nodes.asSequence()
            .filter { !it.editable }
            .filter { it.clickable || it.nodeType.equals("button", ignoreCase = true) }
            .mapNotNull { node ->
                if (isTechnicalOrUiChromeNode(node.label)) return@mapNotNull null
                val clean = cleanUiLabelForSpeech(node.label).take(110).trim()
                if (clean.length < 3 || isTechnicalOrUiChromeNode(clean)) return@mapNotNull null
                if (!seen.add(clean.lowercase())) return@mapNotNull null
                node.copy(label = clean)
            }
            .take(maxChoices.coerceIn(1, 6))
            .toList()
    }

    fun extractScreenSummaryLines(
        nodes: List<UiNodeSnapshot>,
        maxLines: Int = 5
    ): List<String> {
        val seen = linkedSetOf<String>()
        return nodes.asSequence()
            .filterNot { isTechnicalOrUiChromeNode(it.label) }
            .mapNotNull { node ->
                val clean = cleanUiLabelForSpeech(node.label).trim()
                if (clean.length < 3 || isTechnicalOrUiChromeNode(clean)) null else clean
            }
            .filter { seen.add(it.lowercase()) }
            .take(maxLines.coerceIn(1, 8))
            .toList()
    }

    /**
     * Scans passive and card nodes from Google Search / Chrome SERP trees to pull out
     * direct featured-snippet or knowledge-panel answers before ever falling back to clickable links.
     */
    fun extractDirectWebAnswer(
        nodes: List<UiNodeSnapshot>,
        query: String
    ): String? {
        if (nodes.isEmpty()) return null
        val cleanQueryLower = query.trim().lowercase().trimEnd('?', '.', '!')
        val queryTokens = cleanQueryLower
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 && it !in queryStopWords }
            .distinct()

        data class ScoredCandidate(
            val text: String,
            val score: Int,
            val order: Int
        )

        val candidates = ArrayList<ScoredCandidate>()
        nodes.forEachIndexed { idx, node ->
            if (node.editable) return@forEachIndexed
            if (isTechnicalOrUiChromeNode(node.label)) return@forEachIndexed

            val cleaned = cleanUiLabelForSpeech(node.label)
            if (cleaned.length < 18) return@forEachIndexed

            val lower = cleaned.lowercase().trimEnd('.', '!')
            // Skip query echo, People Also Ask questions, or bare link titles ("Mount Apo - Wikipedia")
            if (lower == cleanQueryLower) return@forEachIndexed
            if (cleaned.trim().endsWith("?")) return@forEachIndexed
            if (Regex("^[a-z0-9-]+\\.[a-z]{2,}$", RegexOption.IGNORE_CASE).matches(cleaned)) return@forEachIndexed
            val looksLikeSiteTitleOnly = (cleaned.contains(" - Wikipedia", ignoreCase = true) ||
                cleaned.contains(" | ", ignoreCase = true) ||
                cleaned.contains(" - Britannica", ignoreCase = true)) && cleaned.length < 52
            if (looksLikeSiteTitleOnly) return@forEachIndexed

            val tokenHits = queryTokens.count { lower.contains(it) }
            val minNeededHits = if (queryTokens.size <= 2) 1 else 2
            val hasFactualCue = Regex(
                "\\b(?:is|are|was|were|stands|located|measures|elevation|height|population|capital|founded|invented|meters|metres|feet|km|miles)\\b|\\d{2,}",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(cleaned)

            if (tokenHits < minNeededHits && !(hasFactualCue && tokenHits >= 1 && cleaned.length >= 32)) {
                return@forEachIndexed
            }

            var score = tokenHits * 15
            if (!node.clickable) score += 12 // Prefer passive snippet / knowledge panel prose over link rows
            if (hasFactualCue) score += 12
            if (cleaned.length in 35..220) score += 10
            if (cleaned.contains(".")) score += 4

            candidates.add(ScoredCandidate(cleaned, score, idx))
        }

        if (candidates.isEmpty()) return null
        val best = candidates.sortedWith(compareByDescending<ScoredCandidate> { it.score }.thenBy { it.order })
        val chosen = mutableListOf<String>()
        for (cand in best) {
            val isDuplicate = chosen.any { existing ->
                existing.contains(cand.text, ignoreCase = true) ||
                    cand.text.contains(existing, ignoreCase = true)
            }
            if (!isDuplicate) {
                val sentence = cand.text.trim().let {
                    if (it.endsWith(".") || it.endsWith("!") || it.endsWith("?")) it else "$it."
                }
                chosen.add(sentence)
            }
            if (chosen.size >= 2 || chosen.sumOf { it.length } >= 210) break
        }

        val combined = chosen.joinToString(" ").trim()
        return combined.takeIf { it.length >= 18 }
    }
}
