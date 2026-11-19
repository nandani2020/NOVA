package com.example.engine

import com.example.data.SafetyTier
import com.example.data.ToolCallRequest

/**
 * Global Multi-Stage Action Planner (Process A).
 *
 * Decomposes natural requests (across any installed app) into a finalized sequence of stages
 * before execution begins:
 *   - Stage(s) using Android Intents (launch_app, hardware/OS actions) when an intent is known
 *   - Subsequent Stage(s) using Local Screen Control UI Automation (search, tap, type, scroll, play)
 *     inside the opened app window.
 */
object MultiStageActionPlanner {

    enum class StageExecutionKind(val badge: String) {
        INTENT_LAUNCH("Intent"),
        INTENT_ACTION("Intent"),
        UI_AUTOMATION("UI Control"),
        DEVICE_REFLEX("Reflex")
    }

    data class PlannedActionStage(
        val stageNumber: Int,
        val title: String,
        val kind: StageExecutionKind,
        val toolCall: ToolCallRequest,
        val skillId: String,
        val targetAppName: String? = null,
        val targetPackageName: String? = null,
        val stopOnFailure: Boolean = true
    )

    data class ActionPlan(
        val stages: List<PlannedActionStage>,
        val planSummary: String
    )

    private val localFtsSourceNouns = setOf(
        "files", "my files", "notes", "my notes", "contacts", "my contacts",
        "messages", "my messages", "screenshots", "my screenshots",
        "calendar", "my calendar", "phone", "my phone", "device", "this device",
        "local", "documents", "my documents", "storage"
    )

    private val actionVerbBoundaryRegex = Regex(
        "^(open|launch|start|search|find|look\\s+up|play|tap|click|press|type|enter|input|scroll|swipe|turn|set|go|take|lock|mute|unmute|add|read|show|call|navigate|drive)\\b",
        RegexOption.IGNORE_CASE
    )

    private val openAppClauseRegex =
        Regex("^(?:open|launch|start|go to) (?:the )?([a-zA-Z0-9 ._-]{2,40})(?: app)?$", RegexOption.IGNORE_CASE)

    private val searchAppForClauseRegex =
        Regex("^(?:search|look up) (?:in |on |inside )?(?:the )?([a-zA-Z0-9 ._-]{2,32}) (?:app )?for (.+)$", RegexOption.IGNORE_CASE)

    private val searchInAppClauseRegex =
        Regex("^(?:search|find|look up) (?:for )?(.+?) (?:on|in|inside) (?:the )?([a-zA-Z0-9 ._-]{2,32})$", RegexOption.IGNORE_CASE)

    private val searchStandaloneClauseRegex =
        Regex("^(?:search|find|look up)\\s+(.+)$", RegexOption.IGNORE_CASE)

    private val playOnAppClauseRegex =
        Regex("^play (.+?) (?:on|in|using) (?:the )?(.+?)$", RegexOption.IGNORE_CASE)

    private val playStandaloneClauseRegex =
        Regex("^play(?:\\s+(?:the\\s+)?(.+))?$", RegexOption.IGNORE_CASE)

    private val tapClauseRegex =
        Regex("^(?:tap|click|press) (?:on |the )?(.+?)(?: button)?$", RegexOption.IGNORE_CASE)

    private val typeClauseRegex =
        Regex("^(?:type|enter|input) (.+?) (?:in|into) (?:the )?(.+?)$", RegexOption.IGNORE_CASE)

    private val scrollClauseRegex =
        Regex("^(?:scroll|swipe) (down|up)$", RegexOption.IGNORE_CASE)

    private val directSearchIntentCategories = setOf("Maps/Navigation", "Browser/Web")

    fun plan(
        normalized: InputNormalizer.NormalizedInput,
        installedApps: List<AppCapabilityRegistry.InstalledAppProfile> = emptyList(),
        hasLaunchIntent: (String) -> Boolean = { true }
    ): ActionPlan? {
        if (normalized.hasConditionalLogic) return null
        val full = normalized.cleanedFull.trim().trimEnd('.', '!', '?')
        if (full.isBlank()) return null
        if (RegexFastPathEngine.isAgenticOrMetaCommand(full)) return null

        val clauses = splitClausesSafely(full)
        if (clauses.isEmpty()) return null

        // Single non-app / single simple reflex commands should stay on fast-path unless they require multi-stage open+search
        val stages = mutableListOf<PlannedActionStage>()
        var activeAppName: String? = null
        var activePackage: String? = null
        var activeProfile: AppCapabilityRegistry.InstalledAppProfile? = null

        fun resolveApp(rawApp: String): AppCapabilityRegistry.InstalledAppProfile? {
            if (installedApps.isEmpty()) return null
            return AppCapabilityRegistry.findBestMatchFromProfiles(installedApps, rawApp)
        }

        fun addOpenStage(rawApp: String, profile: AppCapabilityRegistry.InstalledAppProfile?): Boolean {
            val displayName = profile?.appName ?: rawApp.trim()
            val pkg = profile?.packageName
            val canOpenByIntent = pkg == null || hasLaunchIntent(pkg)
            if (!canOpenByIntent) return false

            stages.add(
                PlannedActionStage(
                    stageNumber = stages.size + 1,
                    title = "Open $displayName (Intent)",
                    kind = StageExecutionKind.INTENT_LAUNCH,
                    toolCall = ToolCallRequest(
                        toolName = "launch_app",
                        args = mapOf("app_name" to (profile?.appName ?: rawApp.trim())),
                        shortRefId = "s0${stages.size + 1}",
                        safetyTier = SafetyTier.GREEN
                    ),
                    skillId = "app-knowledge-controller",
                    targetAppName = displayName,
                    targetPackageName = pkg,
                    stopOnFailure = true
                )
            )
            activeAppName = displayName
            activePackage = pkg
            activeProfile = profile
            return true
        }

        for (clause in clauses) {
            val trimmed = clause.trim()
            if (trimmed.isEmpty()) continue

            // 1. Open / Launch <app>
            openAppClauseRegex.find(trimmed)?.let { m ->
                val rawTarget = m.groupValues[1].trim()
                val lowerTarget = rawTarget.lowercase()
                if (lowerTarget.endsWith(" process") ||
                    lowerTarget.endsWith(" workflow") ||
                    lowerTarget.endsWith(" habit") ||
                    lowerTarget.endsWith(" routine") ||
                    lowerTarget.startsWith("my usual ") ||
                    lowerTarget.startsWith("my learned ") ||
                    lowerTarget.contains("personality") ||
                    lowerTarget.startsWith("recording ")
                ) {
                    return null
                }
                if (lowerTarget in setOf(
                        "settings", "my messages", "the screen", "notifications",
                        "quick settings", "recent apps", "recents", "home", "home screen"
                    )
                ) {
                    val osMatch = RegexFastPathEngine.match(trimmed) ?: return null
                    stages.add(
                        PlannedActionStage(
                            stageNumber = stages.size + 1,
                            title = "${osMatch.summary} (Intent)",
                            kind = StageExecutionKind.DEVICE_REFLEX,
                            toolCall = osMatch.toolCall,
                            skillId = osMatch.skillId
                        )
                    )
                    return@let
                }

                val profile = resolveApp(rawTarget)
                if (!addOpenStage(rawTarget, profile)) return null
                continue
            }

            // 2. Search <app> for <query>
            searchAppForClauseRegex.find(trimmed)?.let { m ->
                val rawApp = m.groupValues[1].trim()
                val query = cleanSearchQuery(m.groupValues[2])
                if (rawApp.lowercase() in localFtsSourceNouns || query.isEmpty()) return null

                val profile = resolveApp(rawApp)
                val resolvedName = profile?.appName ?: rawApp
                val alreadyOpened = activeAppName?.equals(resolvedName, ignoreCase = true) == true
                if (!alreadyOpened) {
                    // Plan Intent Open + UI Control Search when multi-stage OR when app has no direct search URI intent
                    val shouldStage = clauses.size > 1 || (profile != null && !hasDirectSearchUri(profile))
                    if (!shouldStage) return null
                    addOpenStage(rawApp, profile)
                }
                stages.add(
                    PlannedActionStage(
                        stageNumber = stages.size + 1,
                        title = "Search '$query' in $resolvedName (UI Control)",
                        kind = StageExecutionKind.UI_AUTOMATION,
                        toolCall = ToolCallRequest(
                            toolName = "screen_control_app",
                            args = mapOf(
                                "app_name" to resolvedName,
                                "goal" to "search_on_screen",
                                "query_text" to query
                            ),
                            shortRefId = "s0${stages.size + 1}",
                            safetyTier = SafetyTier.GREEN
                        ),
                        skillId = "local-screen-control",
                        targetAppName = resolvedName,
                        targetPackageName = profile?.packageName,
                        stopOnFailure = false
                    )
                )
                continue
            }

            // 3. Search / Find / Look up <query> in/on <app>
            searchInAppClauseRegex.find(trimmed)?.let { m ->
                val query = cleanSearchQuery(m.groupValues[1])
                val rawAppCandidate = m.groupValues[2].trim()
                val hasAppSuffix = rawAppCandidate.lowercase().endsWith(" app")
                val rawApp = rawAppCandidate.removeSuffix(" app").removeSuffix(" App").trim()
                val profile = if (installedApps.isNotEmpty()) {
                    AppCapabilityRegistry.findStrictMatchFromProfiles(installedApps, rawApp)
                } else null
                val plausibleApp = hasAppSuffix || profile != null || AppCapabilityRegistry.isKnownAppNameOrAlias(rawApp)
                if (rawApp.lowercase() !in localFtsSourceNouns && query.isNotEmpty() && plausibleApp) {
                    val resolvedName = profile?.appName ?: rawApp
                    val alreadyOpened = activeAppName?.equals(resolvedName, ignoreCase = true) == true
                    if (!alreadyOpened) {
                        val shouldStage = clauses.size > 1 || (profile != null && !hasDirectSearchUri(profile))
                        if (!shouldStage) return null
                        addOpenStage(rawApp, profile)
                    }
                    stages.add(
                        PlannedActionStage(
                            stageNumber = stages.size + 1,
                            title = "Search '$query' in $resolvedName (UI Control)",
                            kind = StageExecutionKind.UI_AUTOMATION,
                            toolCall = ToolCallRequest(
                                toolName = "screen_control_app",
                                args = mapOf(
                                    "app_name" to resolvedName,
                                    "goal" to "search_on_screen",
                                    "query_text" to query
                                ),
                                shortRefId = "s0${stages.size + 1}",
                                safetyTier = SafetyTier.GREEN
                            ),
                            skillId = "local-screen-control",
                            targetAppName = resolvedName,
                            targetPackageName = profile?.packageName,
                            stopOnFailure = false
                        )
                    )
                    continue
                }
            }

            // 4. Contextual Search / Find <query> inside the already-opened activeApp!
            //    e.g., "open youtube and find a christ mas song"
            searchStandaloneClauseRegex.find(trimmed)?.let { m ->
                val targetApp = activeAppName ?: return null
                val query = cleanSearchQuery(m.groupValues[1])
                if (query.isEmpty()) return null

                stages.add(
                    PlannedActionStage(
                        stageNumber = stages.size + 1,
                        title = "Search '$query' in $targetApp (UI Control)",
                        kind = StageExecutionKind.UI_AUTOMATION,
                        toolCall = ToolCallRequest(
                            toolName = "screen_control_app",
                            args = mapOf(
                                "app_name" to targetApp,
                                "goal" to "search_on_screen",
                                "query_text" to query
                            ),
                            shortRefId = "s0${stages.size + 1}",
                            safetyTier = SafetyTier.GREEN
                        ),
                        skillId = "local-screen-control",
                        targetAppName = targetApp,
                        targetPackageName = activePackage,
                        stopOnFailure = false
                    )
                )
                continue
            }

            // 5. Play <query> on <app>
            playOnAppClauseRegex.find(trimmed)?.let { m ->
                val rawQuery = m.groupValues[1].trim()
                val query = cleanSearchQuery(rawQuery)
                val rawAppCandidate = m.groupValues[2].trim()
                val hasAppSuffix = rawAppCandidate.lowercase().endsWith(" app")
                val rawApp = rawAppCandidate.removeSuffix(" app").removeSuffix(" App").trim()
                val profile = if (installedApps.isNotEmpty()) {
                    AppCapabilityRegistry.findStrictMatchFromProfiles(installedApps, rawApp)
                } else null
                val plausibleApp = hasAppSuffix || profile != null || AppCapabilityRegistry.isKnownAppNameOrAlias(rawApp)
                if (plausibleApp) {
                    val isRandom = RandomSongCatalog.isGenericOrRandomMusicQuery(rawQuery) ||
                        RandomSongCatalog.isGenericOrRandomMusicQuery(query)
                    val effectiveQuery = if (isRandom) "random" else query
                    val resolvedName = profile?.appName ?: rawApp
                    if (activeAppName?.equals(resolvedName, ignoreCase = true) != true) {
                        if (clauses.size <= 1) return null
                        addOpenStage(rawApp, profile)
                    }
                    stages.add(
                        PlannedActionStage(
                            stageNumber = stages.size + 1,
                            title = if (isRandom) "Play random song in $resolvedName (UI Control)"
                            else "Play '$effectiveQuery' in $resolvedName (UI Control)",
                            kind = StageExecutionKind.UI_AUTOMATION,
                            toolCall = ToolCallRequest(
                                toolName = "screen_control_app",
                                args = mapOf(
                                    "app_name" to resolvedName,
                                    "goal" to "search_and_play",
                                    "query_text" to effectiveQuery
                                ),
                                shortRefId = "s0${stages.size + 1}",
                                safetyTier = SafetyTier.GREEN
                            ),
                            skillId = "local-screen-control",
                            targetAppName = resolvedName,
                            targetPackageName = profile?.packageName,
                            stopOnFailure = false
                        )
                    )
                    continue
                }
            }

            // 6. Contextual Play inside activeApp (e.g., "... and play it" / "... and play music")
            playStandaloneClauseRegex.find(trimmed)?.let { m ->
                val targetApp = activeAppName ?: run {
                    // Single clause generic media play -> let RegexFastPathEngine handle it
                    return null
                }
                val rawTarget = m.groupValues[1].trim()
                val isFirstOrIt = rawTarget.isEmpty() || rawTarget.lowercase() in setOf(
                    "it", "this", "the first", "first", "first one", "first result", "first video", "first song", "result"
                )
                val cleanedTarget = cleanSearchQuery(rawTarget)
                val isRandom = !isFirstOrIt && (
                    RandomSongCatalog.isGenericOrRandomMusicQuery(rawTarget) ||
                        RandomSongCatalog.isGenericOrRandomMusicQuery(cleanedTarget)
                    )
                val goal = if (isFirstOrIt) "play" else "search_and_play"
                val queryArg = when {
                    isFirstOrIt -> ""
                    isRandom -> "random"
                    else -> cleanedTarget
                }
                val title = when {
                    isFirstOrIt -> "Play result in $targetApp (UI Control)"
                    isRandom -> "Play random song in $targetApp (UI Control)"
                    else -> "Play '$queryArg' in $targetApp (UI Control)"
                }
                stages.add(
                    PlannedActionStage(
                        stageNumber = stages.size + 1,
                        title = title,
                        kind = StageExecutionKind.UI_AUTOMATION,
                        toolCall = ToolCallRequest(
                            toolName = "screen_control_app",
                            args = buildMap {
                                put("app_name", targetApp)
                                put("goal", goal)
                                if (queryArg.isNotEmpty()) put("query_text", queryArg)
                            },
                            shortRefId = "s0${stages.size + 1}",
                            safetyTier = SafetyTier.GREEN
                        ),
                        skillId = "local-screen-control",
                        targetAppName = targetApp,
                        targetPackageName = activePackage,
                        stopOnFailure = false
                    )
                )
                continue
            }

            // 7. Tap / Click / Press <label>
            tapClauseRegex.find(trimmed)?.let { m ->
                if (clauses.size <= 1) return null
                val label = m.groupValues[1].trim()
                val tier = if (DeferredToolRegistry.isRedButtonLabel(label)) SafetyTier.RED else SafetyTier.GREEN
                stages.add(
                    PlannedActionStage(
                        stageNumber = stages.size + 1,
                        title = "Tap '$label' (UI Control)",
                        kind = StageExecutionKind.UI_AUTOMATION,
                        toolCall = ToolCallRequest(
                            toolName = "ui_find_and_tap",
                            args = mapOf("target_label" to label, "action_type" to "click"),
                            shortRefId = "s0${stages.size + 1}",
                            safetyTier = tier
                        ),
                        skillId = "local-screen-control",
                        targetAppName = activeAppName,
                        targetPackageName = activePackage,
                        stopOnFailure = false
                    )
                )
                continue
            }

            // 8. Type / Enter <text> into <field>
            typeClauseRegex.find(trimmed)?.let { m ->
                if (clauses.size <= 1) return null
                val textToType = m.groupValues[1].trim()
                val field = m.groupValues[2].trim()
                stages.add(
                    PlannedActionStage(
                        stageNumber = stages.size + 1,
                        title = "Type into '$field' (UI Control)",
                        kind = StageExecutionKind.UI_AUTOMATION,
                        toolCall = ToolCallRequest(
                            toolName = "ui_find_and_tap",
                            args = mapOf(
                                "target_label" to field,
                                "action_type" to "type",
                                "input_text" to textToType
                            ),
                            shortRefId = "s0${stages.size + 1}",
                            safetyTier = SafetyTier.GREEN
                        ),
                        skillId = "local-screen-control",
                        targetAppName = activeAppName,
                        targetPackageName = activePackage,
                        stopOnFailure = false
                    )
                )
                continue
            }

            // 9. Scroll down / up
            scrollClauseRegex.find(trimmed)?.let { m ->
                if (clauses.size <= 1) return null
                val dir = m.groupValues[1].lowercase()
                val action = if (dir == "up") "SCROLL_UP" else "SCROLL_DOWN"
                stages.add(
                    PlannedActionStage(
                        stageNumber = stages.size + 1,
                        title = "Scroll $dir (UI Control)",
                        kind = StageExecutionKind.UI_AUTOMATION,
                        toolCall = ToolCallRequest(
                            toolName = "ui_act",
                            args = mapOf("action" to action, "node_id" to ""),
                            shortRefId = "s0${stages.size + 1}",
                            safetyTier = SafetyTier.GREEN
                        ),
                        skillId = "local-screen-control",
                        targetAppName = activeAppName,
                        targetPackageName = activePackage,
                        stopOnFailure = false
                    )
                )
                continue
            }

            // 10. Any standard Tier-1 device/OS reflex in a multi-stage chain
            if (clauses.size >= 2) {
                val fast = RegexFastPathEngine.match(trimmed) ?: return null
                stages.add(
                    PlannedActionStage(
                        stageNumber = stages.size + 1,
                        title = fast.summary,
                        kind = StageExecutionKind.DEVICE_REFLEX,
                        toolCall = fast.toolCall.copy(shortRefId = "s0${stages.size + 1}"),
                        skillId = fast.skillId,
                        targetAppName = activeAppName,
                        targetPackageName = activePackage,
                        stopOnFailure = false
                    )
                )
                continue
            }

            // Unrecognized single clause -> let Tier 1 Regex or Tier 2 :llm handle it
            return null
        }

        if (stages.size < 2) return null

        val summary = stages.joinToString(" -> ") { "${it.stageNumber}. ${it.title}" }
        return ActionPlan(stages = stages, planSummary = summary)
    }

    /**
     * Splits compound user commands ONLY at clear action boundaries so phrases like
     * "find tom and jerry" or "search for cats and dogs" remain intact.
     */
    fun splitClausesSafely(text: String): List<String> {
        val majorSegments = text.split(
            Regex(
                "\\s+(?:and\\s+then|then)\\s+|\\s*;\\s*|\\s*,\\s+(?:and\\s+)?(?=(?:open|launch|start|search|find|look\\s+up|play|tap|click|press|type|enter|input|scroll|swipe|turn|set|go|take|lock|mute|unmute|add|read|show|call|navigate|drive)\\b)",
                RegexOption.IGNORE_CASE
            )
        )
        val result = mutableListOf<String>()
        for (segment in majorSegments) {
            splitSegmentOnActionAnd(segment.trim(), result)
        }
        return result.filter { it.isNotBlank() }
    }

    private fun splitSegmentOnActionAnd(segment: String, out: MutableList<String>) {
        if (segment.isBlank()) return
        val andPositions = Regex("\\s+and\\s+", RegexOption.IGNORE_CASE).findAll(segment).toList()
        for (match in andPositions) {
            val left = segment.substring(0, match.range.first).trim()
            val right = segment.substring(match.range.last + 1).trim()
            if (left.isNotEmpty() &&
                actionVerbBoundaryRegex.containsMatchIn(left) &&
                actionVerbBoundaryRegex.containsMatchIn(right)
            ) {
                out.add(left)
                splitSegmentOnActionAnd(right, out)
                return
            }
        }
        out.add(segment)
    }

    private fun cleanSearchQuery(raw: String): String {
        return raw.trim()
            .replace(Regex("^(?:for|a|an|some)\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun hasDirectSearchUri(profile: AppCapabilityRegistry.InstalledAppProfile): Boolean {
        val pkg = profile.packageName.lowercase()
        return pkg == "com.google.android.youtube" ||
            pkg == "com.google.android.apps.youtube.music" ||
            pkg == "com.android.vending" ||
            pkg == "com.spotify.music" ||
            pkg == "com.google.android.apps.maps" ||
            pkg == "com.waze" ||
            pkg == "com.android.chrome" ||
            profile.categoryLabel in directSearchIntentCategories
    }
}
