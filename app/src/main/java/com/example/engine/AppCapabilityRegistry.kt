package com.example.engine

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import com.example.data.LocalFtsIndexHelper
import com.example.processA.EmbeddingGemmaRuntime

/**
 * Discovers real launcher apps installed on the device (via Play-Policy-compliant <queries> intents),
 * inspects their Android category & supported intents, and provides structured "how to use" context
 * for both local FTS5 indexing and Process B (:llm) Gemma 4 app-control prompts.
 */
object AppCapabilityRegistry {

    data class InstalledAppProfile(
        val appName: String,
        val packageName: String,
        val categoryLabel: String,
        val capabilities: List<String>,
        val howToUse: String
    )

    fun discoverInstalledApps(context: Context): List<InstalledAppProfile> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launcherInfos = pm.queryIntentActivities(mainIntent, 0)

        val mediaSearchPkgs = queryPackagesForAction(pm, Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH))
        val shareTextPkgs = queryPackagesForAction(
            pm,
            Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
        )
        val dialPkgs = queryPackagesForAction(pm, Intent(Intent.ACTION_DIAL))

        val webSearchPkgs = queryPackagesForAction(pm, Intent(Intent.ACTION_WEB_SEARCH))
        val inAppSearchPkgs = queryPackagesForAction(pm, Intent(Intent.ACTION_SEARCH))

        val seenPkgs = HashSet<String>()
        val profiles = mutableListOf<InstalledAppProfile>()

        for (info in launcherInfos) {
            val actInfo = info.activityInfo ?: continue
            val pkg = actInfo.packageName ?: continue
            if (pkg == context.packageName || !seenPkgs.add(pkg)) continue

            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
            if (label.isEmpty()) continue

            val appInfo = actInfo.applicationInfo
            val catLabel = resolveCategoryLabel(appInfo, pkg, label)
            val caps = mutableListOf("launch")
            if (pkg in mediaSearchPkgs || catLabel == "Audio/Music" || catLabel == "Video/Streaming") caps.add("media_search")
            if (pkg in inAppSearchPkgs || pkg in webSearchPkgs) caps.add("in_app_search")
            if (pkg in shareTextPkgs || catLabel == "Social/Messaging") caps.add("share_text")
            if (pkg in dialPkgs) caps.add("dial")
            if (catLabel == "Maps/Navigation") caps.add("navigation")
            caps.add("ui_screen_automation")

            val howTo = buildHowToHint(label, catLabel, caps)
            profiles.add(
                InstalledAppProfile(
                    appName = label,
                    packageName = pkg,
                    categoryLabel = catLabel,
                    capabilities = caps,
                    howToUse = howTo
                )
            )
        }
        return profiles.sortedBy { it.appName.lowercase() }
    }

    fun findBestMatch(context: Context, appQuery: String): InstalledAppProfile? {
        val apps = discoverInstalledApps(context)
        return findBestMatchFromProfiles(apps, appQuery)
    }

    fun findBestMatchFromProfiles(
        apps: List<InstalledAppProfile>,
        appQuery: String
    ): InstalledAppProfile? {
        val q = appQuery.trim().lowercase()
        if (q.isEmpty() || apps.isEmpty()) return null

        // 1. Exact or substring matches
        apps.firstOrNull { it.appName.equals(q, ignoreCase = true) }?.let { return it }
        apps.firstOrNull { it.appName.lowercase().startsWith(q) }?.let { return it }
        apps.firstOrNull { it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q) }?.let { return it }

        // 2. Fuzzy typo match (e.g., "youitube music" -> "YouTube Music", "spotfy" -> "Spotify")
        val normalizedQ = q.replace(Regex("[^a-z0-9]"), "")
        val fuzzyBest = apps.map { app ->
            val normApp = app.appName.lowercase().replace(Regex("[^a-z0-9]"), "")
            val fullDist = levenshtein(normalizedQ, normApp)
            val tokenHits = q.split(Regex("\\s+")).filter { it.length >= 3 }.count { tok ->
                app.appName.lowercase().split(Regex("\\s+")).any { appTok ->
                    levenshtein(tok, appTok) <= 2 || appTok.contains(tok)
                }
            }
            val score = (tokenHits * 10) - fullDist
            Triple(app, fullDist, score)
        }.filter { (app, dist, score) ->
            val maxLen = maxOf(normalizedQ.length, app.appName.length).coerceAtLeast(1)
            dist <= (maxLen / 3).coerceAtLeast(2) || score >= 6
        }.maxByOrNull { it.third }

        if (fuzzyBest != null) return fuzzyBest.first

        // 3. Category match fallback
        return apps.firstOrNull { it.categoryLabel.lowercase().contains(q) }
    }

    fun describeAppSkills(profile: InstalledAppProfile): String {
        val features = buildList {
            add("open the app")
            if ("media_search" in profile.capabilities) add("play or search music and videos")
            if ("in_app_search" in profile.capabilities) add("search inside the app")
            if ("navigation" in profile.capabilities) add("get directions or navigate to places")
            if ("share_text" in profile.capabilities) add("send or draft messages")
            if ("dial" in profile.capabilities) add("make phone calls")
            add("read and tap buttons on its screen automatically")
        }
        return "${profile.appName} is a ${profile.categoryLabel.substringBefore('/')} app. With it, I can ${features.joinToString(", ")}."
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val cur = dp[j]
                dp[j] = if (a[i - 1] == b[j - 1]) prev
                else 1 + minOf(prev, dp[j], dp[j - 1])
                prev = cur
            }
        }
        return dp[b.length]
    }

    suspend fun syncInstalledAppsWithCapabilities(
        context: Context,
        ftsHelper: LocalFtsIndexHelper,
        embeddingRuntime: EmbeddingGemmaRuntime? = null
    ): Int {
        val apps = discoverInstalledApps(context)
        apps.forEach { app ->
            val body = "Category: ${app.categoryLabel} | Package: ${app.packageName} | " +
                "Capabilities: ${app.capabilities.joinToString(", ")} | How to control: ${app.howToUse}"
            val vec = embeddingRuntime?.embedDocument(app.appName, body)
            ftsHelper.upsertDocument(
                docId = "app_${app.packageName}",
                category = "apps",
                title = app.appName,
                body = body,
                uriOrRef = "package://${app.packageName}",
                embedding = vec
            )
        }
        return apps.size
    }

    fun findMatchingProfiles(context: Context, filter: String, limit: Int = 24): List<InstalledAppProfile> {
        val apps = discoverInstalledApps(context)
        val q = filter.trim().lowercase()
        val matched = if (q.isEmpty() || q == "all") {
            apps
        } else {
            apps.filter {
                it.appName.lowercase().contains(q) ||
                    it.categoryLabel.lowercase().contains(q) ||
                    it.capabilities.any { c -> c.contains(q) }
            }
        }
        return matched.take(limit)
    }

    fun formatFilteredList(context: Context, filter: String, limit: Int = 24): String {
        val allApps = discoverInstalledApps(context)
        if (allApps.isEmpty()) return "I couldn't find any apps on this phone yet."
        val matched = findMatchingProfiles(context, filter, limit)
        if (matched.isEmpty()) {
            return "I couldn't find any installed apps matching '$filter'."
        }
        val names = matched.take(6).joinToString(", ") { "${it.appName} (${it.categoryLabel.substringBefore('/')})" }
        val extra = if (matched.size > 6) ", and ${matched.size - 6} more" else ""
        return "You have ${matched.size} apps available, including $names$extra. Which app would you like me to open?"
    }

    /**
     * Compact summary injected into Process B (:llm) prompt so Gemma 4 knows what real apps
     * are on the phone and which tools to invoke to use them.
     */
    fun buildLlmAppContext(context: Context, query: String, maxApps: Int = 20): String {
        val apps = discoverInstalledApps(context)
        if (apps.isEmpty()) return ""
        val qTokens = query.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }
        val prioritized = apps.sortedByDescending { app ->
            val hay = "${app.appName} ${app.categoryLabel} ${app.packageName}".lowercase()
            val hits = qTokens.count { hay.contains(it) }
            hits * 10 + if (app.capabilities.contains("media_search") || app.capabilities.contains("share_text")) 2 else 0
        }.take(maxApps)

        return prioritized.joinToString("\n") { app ->
            "• ${app.appName} (${app.categoryLabel}) -> ${app.howToUse}"
        }
    }

    private fun queryPackagesForAction(pm: PackageManager, intent: Intent): Set<String> {
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun resolveCategoryLabel(appInfo: ApplicationInfo, pkg: String, label: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            when (appInfo.category) {
                ApplicationInfo.CATEGORY_AUDIO -> return "Audio/Music"
                ApplicationInfo.CATEGORY_VIDEO -> return "Video/Streaming"
                ApplicationInfo.CATEGORY_MAPS -> return "Maps/Navigation"
                ApplicationInfo.CATEGORY_SOCIAL -> return "Social/Messaging"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> return "Productivity/Docs"
                ApplicationInfo.CATEGORY_NEWS -> return "News/Reading"
                ApplicationInfo.CATEGORY_IMAGE -> return "Camera/Photos"
                ApplicationInfo.CATEGORY_GAME -> return "Game"
                ApplicationInfo.CATEGORY_ACCESSIBILITY -> return "Accessibility"
            }
        }
        val hay = "$pkg $label".lowercase()
        return when {
            hay.containsAny("spotify", "music", "audio", "podcast", "ytmusic", "soundcloud") -> "Audio/Music"
            hay.containsAny("youtube", "netflix", "video", "vlc", "prime", "disney", "tiktok") -> "Video/Streaming"
            hay.containsAny("map", "waze", "uber", "grab", "lyft", "transit") -> "Maps/Navigation"
            hay.containsAny("whatsapp", "messenger", "telegram", "viber", "sms", "mms", "messaging", "slack", "discord", "gmail", "mail") -> "Social/Messaging"
            hay.containsAny("chrome", "browser", "firefox", "edge", "brave", "opera") -> "Browser/Web"
            hay.containsAny("gcash", "maya", "paypal", "bank", "wallet", "venmo") -> "Finance/Wallet"
            hay.containsAny("amazon", "shopee", "lazada", "food", "doordash", "shop", "store") -> "Shopping/Food"
            hay.containsAny("calendar", "notes", "keep", "docs", "drive", "pdf", "sheets", "clock", "alarm", "calculator") -> "Productivity/Docs"
            hay.containsAny("camera", "gallery", "photos") -> "Camera/Photos"
            hay.containsAny("settings", "dialer", "phone", "contacts") -> "System/Device"
            else -> "App"
        }
    }

    private fun buildHowToHint(name: String, category: String, caps: List<String>): String {
        return when {
            "media_search" in caps ->
                "play_in_app(app_name='$name', query='...') or ui_open_and_scan('$name')"
            "navigation" in caps ->
                "navigate_in_maps(destination='...', app_name='$name') or launch_app('$name')"
            "in_app_search" in caps || category == "Browser/Web" || category == "Shopping/Food" ->
                "search_in_app(app_name='$name', query='...') or ui_open_and_scan('$name')"
            category == "Social/Messaging" ->
                "ui_open_and_scan('$name') + ui_act(CLICK/TYPE) or message_send"
            else ->
                "launch_app('$name') or ui_open_and_scan('$name') + ui_act(CLICK/TYPE/SCROLL)"
        }
    }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { this.contains(it) }
}
