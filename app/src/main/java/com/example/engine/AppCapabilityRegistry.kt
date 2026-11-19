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
 * inspects their Android category, supported intents, and package tokens, and builds rich semantic
 * tags & aliases (e.g., "YT Music" <-> "YouTube Music", "ytm", "music", "songs") for both
 * local FTS5/EmbeddingGemma indexing and Process B (:llm) Gemma 4 app-control prompts.
 */
object AppCapabilityRegistry {

    data class InstalledAppProfile(
        val appName: String,
        val packageName: String,
        val categoryLabel: String,
        val capabilities: List<String>,
        val howToUse: String,
        val aliases: List<String> = emptyList(),
        val tags: List<String> = emptyList()
    )

    private data class KnownAppMeta(
        val aliases: List<String>,
        val tags: List<String>,
        val categoryOverride: String? = null
    )

    private val knownAppCatalog: Map<String, KnownAppMeta> = mapOf(
        "com.google.android.apps.youtube.music" to KnownAppMeta(
            aliases = listOf("YouTube Music", "YT Music", "YTM", "Google Music", "YouTubeMusic"),
            tags = listOf("music", "song", "songs", "audio", "playlist", "streaming", "artist", "album", "listen", "podcast", "media"),
            categoryOverride = "Audio/Music"
        ),
        "app.rvx.android.apps.youtube.music" to KnownAppMeta(
            aliases = listOf("YouTube Music", "YT Music", "YTM", "RVX Music", "YouTube Music Extended"),
            tags = listOf("music", "song", "songs", "audio", "playlist", "streaming", "listen", "media"),
            categoryOverride = "Audio/Music"
        ),
        "app.revanced.android.apps.youtube.music" to KnownAppMeta(
            aliases = listOf("YouTube Music", "YT Music", "YTM", "ReVanced Music"),
            tags = listOf("music", "song", "songs", "audio", "playlist", "streaming", "listen", "media"),
            categoryOverride = "Audio/Music"
        ),
        "com.google.android.youtube" to KnownAppMeta(
            aliases = listOf("YouTube", "YT", "YouTube Video"),
            tags = listOf("video", "videos", "watch", "streaming", "channel", "shorts", "media"),
            categoryOverride = "Video/Streaming"
        ),
        "com.spotify.music" to KnownAppMeta(
            aliases = listOf("Spotify", "Spotify Music"),
            tags = listOf("music", "song", "songs", "audio", "playlist", "podcast", "streaming", "listen", "media"),
            categoryOverride = "Audio/Music"
        ),
        "com.spotify.lite" to KnownAppMeta(
            aliases = listOf("Spotify Lite", "Spotify"),
            tags = listOf("music", "song", "songs", "audio", "playlist", "streaming", "media"),
            categoryOverride = "Audio/Music"
        ),
        "com.apple.android.music" to KnownAppMeta(
            aliases = listOf("Apple Music"),
            tags = listOf("music", "song", "audio", "playlist", "streaming", "media"),
            categoryOverride = "Audio/Music"
        ),
        "com.amazon.mp3" to KnownAppMeta(
            aliases = listOf("Amazon Music"),
            tags = listOf("music", "song", "audio", "playlist", "streaming", "media"),
            categoryOverride = "Audio/Music"
        ),
        "com.soundcloud.android" to KnownAppMeta(
            aliases = listOf("SoundCloud"),
            tags = listOf("music", "audio", "tracks", "remix", "streaming"),
            categoryOverride = "Audio/Music"
        ),
        "com.aspiro.tidal" to KnownAppMeta(
            aliases = listOf("Tidal"),
            tags = listOf("music", "audio", "streaming", "media"),
            categoryOverride = "Audio/Music"
        ),
        "deezer.android.app" to KnownAppMeta(
            aliases = listOf("Deezer"),
            tags = listOf("music", "audio", "streaming", "media"),
            categoryOverride = "Audio/Music"
        ),
        "com.sec.android.app.music" to KnownAppMeta(
            aliases = listOf("Samsung Music", "Music"),
            tags = listOf("music", "song", "audio", "local music", "media"),
            categoryOverride = "Audio/Music"
        ),
        "com.miui.player" to KnownAppMeta(
            aliases = listOf("Mi Music", "Music"),
            tags = listOf("music", "song", "audio", "local music", "media"),
            categoryOverride = "Audio/Music"
        ),
        "com.heytap.music" to KnownAppMeta(
            aliases = listOf("Music", "OPPO Music", "Realme Music"),
            tags = listOf("music", "song", "audio", "local music", "media"),
            categoryOverride = "Audio/Music"
        ),
        "com.oppo.music" to KnownAppMeta(
            aliases = listOf("Music", "OPPO Music"),
            tags = listOf("music", "song", "audio", "local music", "media"),
            categoryOverride = "Audio/Music"
        ),
        "com.android.bbkmusic" to KnownAppMeta(
            aliases = listOf("iMusic", "Vivo Music", "Music"),
            tags = listOf("music", "song", "audio", "local music", "media"),
            categoryOverride = "Audio/Music"
        ),
        "com.huawei.music" to KnownAppMeta(
            aliases = listOf("Huawei Music", "Music"),
            tags = listOf("music", "song", "audio", "local music", "media"),
            categoryOverride = "Audio/Music"
        ),
        "in.krosbits.musicolet" to KnownAppMeta(
            aliases = listOf("Musicolet"),
            tags = listOf("music", "song", "audio", "local music", "media"),
            categoryOverride = "Audio/Music"
        ),
        "com.maxmpz.audioplayer" to KnownAppMeta(
            aliases = listOf("Poweramp"),
            tags = listOf("music", "song", "audio", "local music", "media"),
            categoryOverride = "Audio/Music"
        ),
        "org.videolan.vlc" to KnownAppMeta(
            aliases = listOf("VLC", "VLC Player", "VLC Media Player"),
            tags = listOf("video", "audio", "music", "media player", "local media"),
            categoryOverride = "Video/Streaming"
        ),
        "com.netflix.mediaclient" to KnownAppMeta(
            aliases = listOf("Netflix"),
            tags = listOf("movies", "shows", "video", "streaming", "watch"),
            categoryOverride = "Video/Streaming"
        ),
        "com.google.android.apps.maps" to KnownAppMeta(
            aliases = listOf("Google Maps", "Maps", "G Maps"),
            tags = listOf("navigation", "directions", "gps", "route", "traffic", "drive", "places"),
            categoryOverride = "Maps/Navigation"
        ),
        "com.waze" to KnownAppMeta(
            aliases = listOf("Waze"),
            tags = listOf("navigation", "directions", "traffic", "drive", "gps"),
            categoryOverride = "Maps/Navigation"
        ),
        "com.android.chrome" to KnownAppMeta(
            aliases = listOf("Google Chrome", "Chrome"),
            tags = listOf("browser", "web", "internet", "search", "website"),
            categoryOverride = "Browser/Web"
        ),
        "com.google.android.gm" to KnownAppMeta(
            aliases = listOf("Gmail", "Google Mail", "Mail"),
            tags = listOf("email", "mail", "inbox", "compose", "message"),
            categoryOverride = "Social/Messaging"
        ),
        "com.google.android.apps.messaging" to KnownAppMeta(
            aliases = listOf("Messages", "Google Messages", "SMS", "Text Messages"),
            tags = listOf("sms", "text", "messaging", "chat"),
            categoryOverride = "Social/Messaging"
        ),
        "com.whatsapp" to KnownAppMeta(
            aliases = listOf("WhatsApp", "WA"),
            tags = listOf("chat", "messaging", "call", "voice", "text"),
            categoryOverride = "Social/Messaging"
        ),
        "com.facebook.orca" to KnownAppMeta(
            aliases = listOf("Messenger", "Facebook Messenger", "FB Messenger"),
            tags = listOf("chat", "messaging", "call", "facebook", "text"),
            categoryOverride = "Social/Messaging"
        ),
        "org.telegram.messenger" to KnownAppMeta(
            aliases = listOf("Telegram", "TG"),
            tags = listOf("chat", "messaging", "channel", "text"),
            categoryOverride = "Social/Messaging"
        ),
        "com.viber.voip" to KnownAppMeta(
            aliases = listOf("Viber"),
            tags = listOf("chat", "messaging", "call", "text"),
            categoryOverride = "Social/Messaging"
        ),
        "com.google.android.apps.photos" to KnownAppMeta(
            aliases = listOf("Google Photos", "Photos", "Gallery"),
            tags = listOf("photos", "pictures", "images", "gallery", "albums", "videos", "search"),
            categoryOverride = "Camera/Photos"
        ),
        "com.google.android.apps.photosgo" to KnownAppMeta(
            aliases = listOf("Gallery", "Gallery Go", "Google Gallery", "Photos"),
            tags = listOf("gallery", "photos", "pictures", "images", "albums", "videos", "search"),
            categoryOverride = "Camera/Photos"
        ),
        "com.sec.android.gallery3d" to KnownAppMeta(
            aliases = listOf("Gallery", "Samsung Gallery", "Photos"),
            tags = listOf("gallery", "photos", "pictures", "images", "albums", "videos", "search"),
            categoryOverride = "Camera/Photos"
        ),
        "com.miui.gallery" to KnownAppMeta(
            aliases = listOf("Gallery", "Mi Gallery", "Photos"),
            tags = listOf("gallery", "photos", "pictures", "images", "albums", "videos", "search"),
            categoryOverride = "Camera/Photos"
        ),
        "com.android.gallery3d" to KnownAppMeta(
            aliases = listOf("Gallery", "Photos"),
            tags = listOf("gallery", "photos", "pictures", "images", "albums", "videos", "search"),
            categoryOverride = "Camera/Photos"
        ),
        "com.oneplus.gallery" to KnownAppMeta(
            aliases = listOf("Gallery", "OnePlus Gallery", "Photos"),
            tags = listOf("gallery", "photos", "pictures", "images", "albums", "videos", "search"),
            categoryOverride = "Camera/Photos"
        ),
        "com.coloros.gallery3d" to KnownAppMeta(
            aliases = listOf("Gallery", "Photos"),
            tags = listOf("gallery", "photos", "pictures", "images", "albums", "videos", "search"),
            categoryOverride = "Camera/Photos"
        ),
        "com.vivo.gallery" to KnownAppMeta(
            aliases = listOf("Gallery", "Albums", "Photos"),
            tags = listOf("gallery", "photos", "pictures", "images", "albums", "videos", "search"),
            categoryOverride = "Camera/Photos"
        ),
        "com.google.android.apps.docs" to KnownAppMeta(
            aliases = listOf("Google Drive", "Drive"),
            tags = listOf("files", "cloud", "storage", "documents", "pdf"),
            categoryOverride = "Productivity/Docs"
        ),
        "com.google.android.keep" to KnownAppMeta(
            aliases = listOf("Google Keep", "Keep Notes", "Keep"),
            tags = listOf("notes", "memo", "checklist", "reminders"),
            categoryOverride = "Productivity/Docs"
        ),
        "com.google.android.calendar" to KnownAppMeta(
            aliases = listOf("Google Calendar", "Calendar"),
            tags = listOf("calendar", "events", "schedule", "meetings"),
            categoryOverride = "Productivity/Docs"
        ),
        "com.globe.gcash.android" to KnownAppMeta(
            aliases = listOf("GCash"),
            tags = listOf("wallet", "finance", "pay", "transfer", "bills"),
            categoryOverride = "Finance/Wallet"
        ),
        "com.paymaya" to KnownAppMeta(
            aliases = listOf("Maya", "PayMaya"),
            tags = listOf("wallet", "finance", "pay", "transfer", "bills"),
            categoryOverride = "Finance/Wallet"
        ),
        "com.android.vending" to KnownAppMeta(
            aliases = listOf("Play Store", "PlayStore", "Google Play", "Google Play Store"),
            tags = listOf("apps", "store", "download", "games", "updates", "search"),
            categoryOverride = "Shopping/Food"
        ),
        "com.android.settings" to KnownAppMeta(
            aliases = listOf("Settings", "System Settings", "Phone Settings"),
            tags = listOf("settings", "wifi", "bluetooth", "system", "accessibility"),
            categoryOverride = "System/Device"
        )
    )

    private val tokenSynonyms = mapOf(
        "yt" to "youtube",
        "youtube" to "yt",
        "ytm" to "youtube music",
        "fb" to "facebook",
        "ig" to "instagram",
        "wa" to "whatsapp",
        "msg" to "messages",
        "gm" to "gmail",
        "cam" to "camera",
        "calc" to "calculator",
        "cal" to "calendar"
    )

    private val packageNoiseTokens = setOf(
        "com", "org", "net", "io", "co", "app", "apps", "android", "google",
        "sec", "samsung", "miui", "xiaomi", "oppo", "vivo", "oneplus", "mobile",
        "client", "lite", "free", "pro", "vending", "mediaclient", "voip", "orca"
    )

    fun discoverInstalledApps(context: Context): List<InstalledAppProfile> {
        val pm = context.packageManager
        val launcherIntents = listOf(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MAPS),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY)
        )

        val queryFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else 0

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

        fun addProfileIfValid(pkg: String, rawLabel: String, appInfo: ApplicationInfo?) {
            if (pkg == context.packageName || !seenPkgs.add(pkg)) return
            val label = rawLabel.trim().ifEmpty {
                appInfo?.let { runCatching { it.loadLabel(pm).toString().trim() }.getOrNull() }.orEmpty()
            }
            if (label.isEmpty()) return

            val known = knownAppCatalog[pkg]
            val catLabel = known?.categoryOverride ?: resolveCategoryLabel(appInfo, pkg, label)
            val caps = mutableListOf("launch")
            if (pkg in mediaSearchPkgs || catLabel == "Audio/Music" || catLabel == "Video/Streaming") caps.add("media_search")
            if (pkg in inAppSearchPkgs || pkg in webSearchPkgs) caps.add("in_app_search")
            if (pkg in shareTextPkgs || catLabel == "Social/Messaging") caps.add("share_text")
            if (pkg in dialPkgs) caps.add("dial")
            if (catLabel == "Maps/Navigation") caps.add("navigation")
            caps.add("ui_screen_automation")

            val (aliases, tags) = buildAliasesAndTags(label, pkg, catLabel, caps, known)
            val howTo = buildHowToHint(label, catLabel, caps)
            profiles.add(
                InstalledAppProfile(
                    appName = label,
                    packageName = pkg,
                    categoryLabel = catLabel,
                    capabilities = caps,
                    howToUse = howTo,
                    aliases = aliases,
                    tags = tags
                )
            )
        }

        // 1. Standard Launcher & Category Intents
        for (intent in launcherIntents) {
            val resolved = runCatching { pm.queryIntentActivities(intent, queryFlags) }
                .getOrElse { runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList()) }
            for (info in resolved) {
                val actInfo = info.activityInfo ?: continue
                val pkg = actInfo.packageName ?: continue
                val label = runCatching { info.loadLabel(pm)?.toString() }.getOrNull().orEmpty()
                addProfileIfValid(pkg, label, actInfo.applicationInfo)
            }
        }

        // 2. Fallback scan of visible packages (covers <queries><package .../> and non-standard launcher flags)
        val installedApps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        for (appInfo in installedApps) {
            val pkg = appInfo.packageName ?: continue
            if (pkg in seenPkgs || pkg == context.packageName) continue
            val hasLauncher = runCatching { pm.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
            if (hasLauncher || pkg in knownAppCatalog) {
                val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault("")
                addProfileIfValid(pkg, label, appInfo)
            }
        }

        // 3. Explicit probe for curated packages declared in <queries>
        for (pkg in knownAppCatalog.keys) {
            if (pkg in seenPkgs) continue
            val appInfo = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            if (!appInfo.enabled) continue
            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault("")
            addProfileIfValid(pkg, label, appInfo)
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

        val enrichedApps = apps.map { ensureEnrichedProfile(it) }
        val normalizedQ = q.replace(Regex("[^a-z0-9]"), "")
        val queryVariants = expandQueryVariants(q)

        // 1. Exact label or alias match
        for (variant in queryVariants) {
            enrichedApps.firstOrNull { app ->
                app.appName.equals(variant, ignoreCase = true) ||
                    app.aliases.any { it.equals(variant, ignoreCase = true) }
            }?.let { return it }
        }

        // 2. Normalized alphanumeric exact match (e.g., "youtubemusic" == "YouTube Music")
        val normalizedVariants = queryVariants.map { it.replace(Regex("[^a-z0-9]"), "") }.filter { it.isNotEmpty() }
        enrichedApps.firstOrNull { app ->
            val candidates = (listOf(app.appName) + app.aliases)
                .map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
            normalizedVariants.any { nv -> nv in candidates }
        }?.let { return it }

        // 3. Prefix or substring match across appName, aliases, and space-separated packageName
        for (variant in queryVariants) {
            enrichedApps.firstOrNull { app ->
                app.appName.lowercase().startsWith(variant) ||
                    app.aliases.any { it.lowercase().startsWith(variant) }
            }?.let { return it }
        }
        for (variant in queryVariants) {
            enrichedApps.firstOrNull { app ->
                val pkgPhrase = app.packageName.lowercase().replace('.', ' ').replace('_', ' ')
                app.appName.lowercase().contains(variant) ||
                    app.aliases.any { it.lowercase().contains(variant) } ||
                    app.packageName.lowercase().contains(variant) ||
                    pkgPhrase.contains(variant)
            }?.let { return it }
        }

        // 4. Fuzzy typo + token overlap scoring across appName AND aliases (e.g., "youitube music" -> "YouTube Music" alias of "YT Music")
        val fuzzyBest = enrichedApps.mapNotNull { app ->
            val candidateNames = (listOf(app.appName) + app.aliases).distinct()
            var bestScore = Int.MIN_VALUE
            var bestDist = Int.MAX_VALUE
            var bestCandidateLen = app.appName.length

            for (variant in queryVariants) {
                val normVar = variant.replace(Regex("[^a-z0-9]"), "")
                val qTokens = variant.split(Regex("\\s+")).filter { it.length >= 2 }

                for (cand in candidateNames) {
                    val normCand = cand.lowercase().replace(Regex("[^a-z0-9]"), "")
                    if (normCand.isEmpty()) continue
                    val fullDist = levenshtein(normVar, normCand)
                    val candTokens = cand.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    val tokenHits = qTokens.count { tok ->
                        candTokens.any { appTok ->
                            appTok == tok || appTok.contains(tok) || (tok.length >= 3 && levenshtein(tok, appTok) <= 2)
                        }
                    }
                    val score = (tokenHits * 10) - fullDist
                    if (score > bestScore || (score == bestScore && fullDist < bestDist)) {
                        bestScore = score
                        bestDist = fullDist
                        bestCandidateLen = normCand.length
                    }
                }
            }

            val maxLen = maxOf(normalizedQ.length, bestCandidateLen).coerceAtLeast(1)
            val thresholdOk = bestDist <= (maxLen / 3).coerceAtLeast(2) || bestScore >= 6
            if (thresholdOk) Triple(app, bestDist, bestScore) else null
        }.maxByOrNull { it.third * 100 - it.second }

        if (fuzzyBest != null) return fuzzyBest.first

        // 5. Tag or Category match fallback (e.g., "music app", "navigation") — require >=4 char tag match & ignore hardware terms on 3P apps
        val cleanTagQuery = q.removeSuffix(" app").trim()
        val reservedHardwareTerms = setOf("bluetooth", "wifi", "wi-fi", "flashlight", "torch", "dnd", "hotspot")
        if (cleanTagQuery in reservedHardwareTerms) {
            return enrichedApps.firstOrNull { it.packageName == "com.android.settings" }
        }
        return enrichedApps.firstOrNull { app ->
            app.tags.any { tag ->
                tag.equals(cleanTagQuery, ignoreCase = true) ||
                    (tag.length >= 4 && cleanTagQuery.contains(tag, ignoreCase = true))
            } || app.categoryLabel.lowercase().contains(cleanTagQuery)
        }
    }

    fun findStrictMatchFromProfiles(
        apps: List<InstalledAppProfile>,
        appQuery: String
    ): InstalledAppProfile? {
        val q = appQuery.trim().lowercase()
        if (q.isEmpty() || apps.isEmpty()) return null
        val enrichedApps = apps.map { ensureEnrichedProfile(it) }
        val queryVariants = expandQueryVariants(q)
        val normalizedVariants = queryVariants
            .map { it.replace(Regex("[^a-z0-9]"), "") }
            .filter { it.isNotEmpty() }

        for (variant in queryVariants) {
            enrichedApps.firstOrNull { app ->
                app.appName.equals(variant, ignoreCase = true) ||
                    app.aliases.any { it.equals(variant, ignoreCase = true) }
            }?.let { return it }
        }
        return enrichedApps.firstOrNull { app ->
            val candidates = (listOf(app.appName) + app.aliases)
                .map { it.lowercase().replace(Regex("[^a-z0-9]"), "") }
            normalizedVariants.any { nv -> nv in candidates }
        }
    }

    private val preferredMusicPackageOrder = listOf(
        "com.google.android.apps.youtube.music",
        "app.rvx.android.apps.youtube.music",
        "app.revanced.android.apps.youtube.music",
        "com.spotify.music",
        "com.spotify.lite",
        "com.apple.android.music",
        "com.amazon.mp3",
        "com.soundcloud.android",
        "com.aspiro.tidal",
        "deezer.android.app",
        "com.sec.android.app.music",
        "com.miui.player",
        "com.heytap.music",
        "com.oppo.music",
        "com.android.bbkmusic",
        "com.huawei.music",
        "in.krosbits.musicolet",
        "com.maxmpz.audioplayer"
    )

    private val extraKnownAppNames = setOf(
        "youtube", "yt", "youtube music", "yt music", "ytm", "spotify", "spotify lite", "apple music",
        "soundcloud", "amazon music", "tidal", "deezer", "samsung music", "mi music", "musicolet",
        "poweramp", "vlc", "netflix", "maps", "google maps",
        "waze", "chrome", "google chrome", "gmail", "messages", "google messages",
        "whatsapp", "messenger", "telegram", "viber", "discord", "photos", "google photos",
        "gallery", "samsung gallery", "drive", "google drive", "keep", "google keep",
        "calendar", "google calendar", "gcash", "maya", "paymaya", "play store", "playstore",
        "google play", "settings", "camera", "clock", "contacts", "files", "browser", "music"
    )

    fun isKnownAppNameOrAlias(rawCandidate: String): Boolean {
        val clean = rawCandidate.trim().lowercase()
            .removePrefix("the ")
            .removeSuffix(" app")
            .trim()
        if (clean.isEmpty()) return false
        if (clean in extraKnownAppNames) return true
        val normClean = clean.replace(Regex("[^a-z0-9]"), "")
        return knownAppCatalog.values.any { meta ->
            meta.aliases.any { alias ->
                alias.equals(clean, ignoreCase = true) ||
                    alias.lowercase().replace(Regex("[^a-z0-9]"), "") == normClean
            }
        }
    }

    fun isBrowserApp(profile: InstalledAppProfile): Boolean {
        if (profile.categoryLabel == "Browser/Web") return true
        val hay = "${profile.packageName} ${profile.appName}".lowercase()
        return hay.containsAny("chrome", "browser", "firefox", "edge", "brave", "opera", "sbrowser", "duckduckgo", "vivaldi")
    }

    fun findPreferredMediaApp(context: Context): InstalledAppProfile? {
        val allApps = discoverInstalledApps(context).filterNot { isBrowserApp(it) }
        if (allApps.isEmpty()) return null
        for (pkg in preferredMusicPackageOrder) {
            allApps.firstOrNull { it.packageName == pkg }?.let { return it }
        }
        // Prefer any dedicated Audio/Music app (including OEM Music apps or CATEGORY_APP_MUSIC) BEFORE YouTube video
        val dedicatedAudioApp = allApps.firstOrNull {
            it.categoryLabel == "Audio/Music" &&
                !it.appName.contains("recorder", ignoreCase = true) &&
                !it.appName.contains("effect", ignoreCase = true) &&
                !it.appName.contains("equalizer", ignoreCase = true)
        }
        if (dedicatedAudioApp != null) return dedicatedAudioApp

        return allApps.firstOrNull { it.packageName == "com.google.android.youtube" }
            ?: allApps.firstOrNull { "media_search" in it.capabilities }
            ?: allApps.firstOrNull { it.categoryLabel == "Audio/Music" }
    }

    fun describeAppSkills(profile: InstalledAppProfile): String {
        val enriched = ensureEnrichedProfile(profile)
        val features = buildList {
            add("open the app")
            if ("media_search" in enriched.capabilities) add("play or search music and videos")
            if ("in_app_search" in enriched.capabilities) add("search inside the app")
            if ("navigation" in enriched.capabilities) add("get directions or navigate to places")
            if ("share_text" in enriched.capabilities) add("send or draft messages")
            if ("dial" in enriched.capabilities) add("make phone calls")
            add("read and tap buttons on its screen automatically")
        }
        val aliasNote = enriched.aliases
            .filterNot { it.equals(enriched.appName, ignoreCase = true) }
            .take(2)
            .takeIf { it.isNotEmpty() }
            ?.let { " (also known as ${it.joinToString(", ")})" }
            .orEmpty()
        return "${enriched.appName}$aliasNote is a ${enriched.categoryLabel.substringBefore('/')} app. With it, I can ${features.joinToString(", ")}."
    }

    suspend fun syncInstalledAppsWithCapabilities(
        context: Context,
        ftsHelper: LocalFtsIndexHelper,
        embeddingRuntime: EmbeddingGemmaRuntime? = null
    ): Int {
        val apps = discoverInstalledApps(context)
        if (apps.isEmpty()) return 0

        val docs = apps.map { app ->
            val aliasText = app.aliases.joinToString(", ")
            val tagText = app.tags.joinToString(", ")
            val body = "Category: ${app.categoryLabel} | Aliases: $aliasText | Tags: $tagText | " +
                "Package: ${app.packageName} | Capabilities: ${app.capabilities.joinToString(", ")} | " +
                "How to control: ${app.howToUse}"
            app to body
        }

        val vectors = if (embeddingRuntime != null && embeddingRuntime.isModelFilePresent()) {
            embeddingRuntime.embedDocumentsBatch(docs.map { (app, body) -> app.appName to body })
        } else {
            List(docs.size) { null }
        }

        docs.forEachIndexed { idx, (app, body) ->
            ftsHelper.upsertDocument(
                docId = "app_${app.packageName}",
                category = "apps",
                title = app.appName,
                body = body,
                uriOrRef = "package://${app.packageName}",
                embedding = vectors.getOrNull(idx)
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
                    it.aliases.any { a -> a.lowercase().contains(q) } ||
                    it.tags.any { t -> t.lowercase().contains(q) } ||
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
     * Compact tagged summary injected into Process B (:llm) prompt so Gemma 4 knows what real apps
     * are on the phone, their aliases/tags, and which tools to invoke to control them.
     */
    fun buildLlmAppContext(context: Context, query: String, maxApps: Int = 20): String {
        val apps = discoverInstalledApps(context)
        return formatLlmAppContext(apps, query, maxApps)
    }

    fun formatLlmAppContext(
        apps: List<InstalledAppProfile>,
        query: String,
        maxApps: Int = 20
    ): String {
        if (apps.isEmpty()) return ""
        val enriched = apps.map { ensureEnrichedProfile(it) }
        val qTokens = query.lowercase().split(Regex("\\s+")).filter { it.length >= 2 }
        val prioritized = enriched.sortedByDescending { app ->
            val hay = buildString {
                append(app.appName).append(' ')
                append(app.aliases.joinToString(" ")).append(' ')
                append(app.tags.joinToString(" ")).append(' ')
                append(app.categoryLabel).append(' ')
                append(app.packageName)
            }.lowercase()
            val hits = qTokens.count { hay.contains(it) }
            hits * 10 + if (app.capabilities.contains("media_search") || app.capabilities.contains("share_text")) 2 else 0
        }.take(maxApps)

        return prioritized.joinToString("\n") { app ->
            val aka = app.aliases
                .filterNot { it.equals(app.appName, ignoreCase = true) }
                .take(3)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
                ?.let { " | aka: $it" }
                .orEmpty()
            val tagStr = app.tags.take(5).joinToString(", ")
            "• ${app.appName} (${app.categoryLabel}$aka | tags: $tagStr) -> ${app.howToUse}"
        }
    }

    internal fun ensureEnrichedProfile(profile: InstalledAppProfile): InstalledAppProfile {
        if (profile.aliases.isNotEmpty() && profile.tags.isNotEmpty()) return profile
        val known = knownAppCatalog[profile.packageName]
        val (aliases, tags) = buildAliasesAndTags(
            label = profile.appName,
            pkg = profile.packageName,
            catLabel = profile.categoryLabel,
            caps = profile.capabilities,
            known = known
        )
        return profile.copy(
            aliases = (profile.aliases + aliases).distinct(),
            tags = (profile.tags + tags).distinct()
        )
    }

    private fun buildAliasesAndTags(
        label: String,
        pkg: String,
        catLabel: String,
        caps: List<String>,
        known: KnownAppMeta?
    ): Pair<List<String>, List<String>> {
        val aliases = linkedSetOf<String>()
        aliases.add(label)
        known?.aliases?.forEach { aliases.add(it) }

        // Extract meaningful words from package name (e.g., com.google.android.apps.youtube.music -> "youtube music")
        val pkgTokens = pkg.lowercase()
            .split('.', '_', '-')
            .filter { it.length >= 2 && it !in packageNoiseTokens }
        if (pkgTokens.isNotEmpty()) {
            val joined = pkgTokens.joinToString(" ")
            aliases.add(joined)
            if (pkgTokens.size >= 2) {
                aliases.add(pkgTokens.takeLast(2).joinToString(" "))
            }
        }

        // Expand abbreviations in label/aliases (e.g. "YT Music" <-> "YouTube Music")
        val labelTokens = label.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (labelTokens.any { it in tokenSynonyms }) {
            val expanded = labelTokens.joinToString(" ") { tokenSynonyms[it] ?: it }
            aliases.add(expanded)
        }

        val tagStopwords = setOf(
            "the", "and", "for", "with", "from", "into", "onto", "app", "apps", "pro", "lite", "free", "mobile"
        )
        val tags = linkedSetOf<String>()
        known?.tags?.forEach { tags.add(it) }
        tags.addAll(pkgTokens.filter { it.length >= 3 && it !in tagStopwords })
        tags.addAll(labelTokens.filter { it.length >= 3 && it !in tagStopwords })

        when (catLabel) {
            "Audio/Music" -> tags.addAll(listOf("music", "audio", "song", "songs", "track", "playlist", "listen"))
            "Video/Streaming" -> tags.addAll(listOf("video", "videos", "watch", "stream", "media"))
            "Maps/Navigation" -> tags.addAll(listOf("maps", "navigation", "directions", "route", "gps", "drive"))
            "Social/Messaging" -> tags.addAll(listOf("message", "messages", "chat", "text", "send"))
            "Browser/Web" -> tags.addAll(listOf("browser", "web", "internet", "search"))
            "Finance/Wallet" -> tags.addAll(listOf("finance", "wallet", "money", "pay", "transfer"))
            "Shopping/Food" -> tags.addAll(listOf("shop", "shopping", "order", "store"))
            "Productivity/Docs" -> tags.addAll(listOf("docs", "notes", "productivity", "work"))
            "Camera/Photos" -> tags.addAll(listOf("camera", "photo", "photos", "gallery"))
            "System/Device" -> tags.addAll(listOf("system", "device", "settings"))
        }
        if ("media_search" in caps) tags.add("play media")
        if ("in_app_search" in caps) tags.add("search")
        tags.add("screen control")

        return aliases.toList() to tags.toList()
    }

    private fun expandQueryVariants(rawQuery: String): List<String> {
        val q = rawQuery.trim().lowercase()
        val variants = linkedSetOf(q)
        val noAppSuffix = q.removePrefix("the ").removeSuffix(" app").trim()
        if (noAppSuffix.isNotEmpty()) variants.add(noAppSuffix)

        val tokens = noAppSuffix.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.any { it in tokenSynonyms }) {
            variants.add(tokens.joinToString(" ") { tokenSynonyms[it] ?: it })
        }
        if (noAppSuffix == "ytm" || noAppSuffix == "yt music") {
            variants.add("youtube music")
        }
        if (noAppSuffix == "youtube music") {
            variants.add("yt music")
        }
        return variants.toList()
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

    private fun queryPackagesForAction(pm: PackageManager, intent: Intent): Set<String> {
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())
    }

    private fun resolveCategoryLabel(appInfo: ApplicationInfo?, pkg: String, label: String): String {
        if (appInfo != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            hay.containsAny("spotify", "music", "audio", "podcast", "ytmusic", "youtube.music", "soundcloud") -> "Audio/Music"
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
                "play_in_app(app_name='$name', query='...') or screen_control_app(app_name='$name', goal='search_and_play', query_text='...')"
            "navigation" in caps ->
                "navigate_in_maps(destination='...', app_name='$name') or launch_app('$name')"
            "in_app_search" in caps || category == "Browser/Web" || category == "Shopping/Food" || category == "Camera/Photos" ->
                "search_in_app(app_name='$name', query='...') or screen_control_app(app_name='$name', goal='search', query_text='...')"
            category == "Social/Messaging" ->
                "ui_open_and_scan('$name') + ui_act(CLICK/TYPE) or message_send"
            else ->
                "launch_app('$name') or screen_control_app(app_name='$name', goal='...')"
        }
    }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { this.contains(it) }
}
