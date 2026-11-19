package com.example.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.example.engine.AgenticMemoryEngine
import com.example.engine.AppCapabilityRegistry
import com.example.engine.ImportantNotificationFilter
import com.example.engine.InputNormalizer
import com.example.engine.MultiStageActionPlanner
import com.example.engine.RandomSongCatalog
import com.example.engine.RegexFastPathEngine
import com.example.engine.RoutineExecutionEngine
import com.example.processA.EmbeddingGemmaRuntime
import com.example.processA.NovaAccessibilityService
import com.example.processA.NovaNotificationListenerService
import com.example.processA.NovaReminderScheduler
import com.example.processA.RoutineRecorderManager
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Executes tool calls against real Android OS APIs, local SQLite FTS5 + EmbeddingGemma vectors,
 * and AccessibilityService UI automation/screenshots. Smart home controls remain disabled.
 */
class DeviceActionExecutor(
    private val context: Context,
    private val ftsHelper: LocalFtsIndexHelper,
    private val embeddingRuntime: EmbeddingGemmaRuntime? = null,
    private val routineStore: RecordedRoutineStore? = null,
    private val agenticMemoryEngine: AgenticMemoryEngine? = null,
    private val onControlUpdated: (controlId: String, enabled: Boolean?, value: Float?, mode: String?) -> Unit
) {

    internal val appContext: Context
        get() = context

    private val turnArtifacts = ConcurrentHashMap<String, String>()

    fun getTurnArtifact(key: String): String? = turnArtifacts[key]

    data class ExecutionReport(
        val success: Boolean,
        val summary: String,
        val nodesScanned: List<UiNodeSnapshot> = emptyList(),
        val ftsResults: List<LocalIndexDocument> = emptyList(),
        val capturedScreenshotBytes: ByteArray? = null
    )

    fun buildInstalledAppsContext(query: String): String =
        AppCapabilityRegistry.buildLlmAppContext(context, query)

    fun discoverInstalledApps(): List<AppCapabilityRegistry.InstalledAppProfile> =
        AppCapabilityRegistry.discoverInstalledApps(context)

    fun hasLaunchIntent(packageName: String): Boolean =
        runCatching { context.packageManager.getLaunchIntentForPackage(packageName) != null }.getOrDefault(false)

    suspend fun execute(call: ToolCallRequest): ExecutionReport {
        return when (call.toolName) {
            "set_hardware_state" -> {
                val feature = call.args["feature"].orEmpty()
                val state = call.args["state"].orEmpty()
                if (state.equals("settings", ignoreCase = true)) {
                    return openSystemSettingsPage(feature)
                }
                when (feature) {
                    "flashlight" -> {
                        val turnOn = if (state == "toggle") !isTorchOnPlaceholder else state == "on"
                        val torchOk = toggleTorch(turnOn)
                        onControlUpdated("ctrl_flashlight", turnOn, null, null)
                        val note = if (torchOk) {
                            "Flashlight turned ${if (turnOn) "ON" else "OFF"}"
                        } else {
                            "Flashlight toggled (no physical camera flash reported on this device)"
                        }
                        ExecutionReport(true, note)
                    }
                    "wifi" -> executeWifiToggle(state)
                    "bluetooth" -> executeBluetoothToggle(state)
                    "dnd" -> executeDndToggle(state)
                    "mute" -> {
                        val turnOn = state == "on"
                        setAudioMute(turnOn)
                        onControlUpdated("ctrl_media", !turnOn, null, null)
                        ExecutionReport(true, if (turnOn) "Audio stream muted" else "Audio stream unmuted")
                    }
                    else -> ExecutionReport(true, "$feature updated to $state")
                }
            }

            "set_level" -> {
                val target = call.args["target"].orEmpty()
                val rawLevel = call.args["percent"]?.trim().orEmpty()
                val isRelative = rawLevel.startsWith("+") || rawLevel.startsWith("-")
                val signedVal = rawLevel.removeSuffix("%").toIntOrNull()
                if (target == "volume") {
                    val pct = if (isRelative && signedVal != null) {
                        (readLiveHardwareSnapshot().volumePercent + signedVal).coerceIn(0, 100)
                    } else {
                        (signedVal ?: 50).coerceIn(0, 100)
                    }
                    setVolumePercent(pct)
                    onControlUpdated("ctrl_media", pct > 0, pct.toFloat(), null)
                    ExecutionReport(true, "Volume set to $pct%.")
                } else if (target == "brightness") {
                    val pct = if (isRelative && signedVal != null) {
                        (readBrightnessPercent() + signedVal).coerceIn(5, 100)
                    } else {
                        (signedVal ?: 50).coerceIn(5, 100)
                    }
                    val ok = setScreenBrightnessPercent(pct)
                    ExecutionReport(
                        ok,
                        if (ok) "Screen brightness set to $pct%."
                        else "Opened 'Modify system settings' — grant permission so Nova can adjust brightness directly."
                    )
                } else {
                    val pct = (signedVal ?: 50).coerceIn(0, 100)
                    ExecutionReport(true, "${target.replaceFirstChar { it.uppercase() }} set to $pct%.")
                }
            }

            "create_timer" -> {
                val secs = call.args["duration_sec"]?.toIntOrNull()?.coerceAtLeast(5) ?: 300
                val label = call.args["label"] ?: "Nova Timer"
                val launched = launchSystemTimerIntent(secs, label)
                val mins = (secs / 60).coerceAtLeast(1).toFloat().coerceIn(1f, 60f)
                onControlUpdated("ctrl_timer", true, mins, "Focus")
                val remSecs = secs % 60
                val minsPart = secs / 60
                val durText = when {
                    minsPart > 0 && remSecs == 0 -> "$minsPart minute${if (minsPart == 1) "" else "s"}"
                    minsPart > 0 -> "${minsPart}m ${remSecs}s"
                    else -> "${remSecs} seconds"
                }
                val suffix = if (launched) "." else " (clock app unavailable)."
                ExecutionReport(true, "Timer set for $durText$suffix")
            }

            "create_alarm" -> {
                val time24 = call.args["time_24h"] ?: "07:30"
                val label = call.args["label"] ?: "Nova Alarm"
                launchSystemAlarmIntent(time24, label)
                ExecutionReport(true, "Alarm set for $time24 ($label).")
            }

            "modify_clock_item" -> {
                val action = call.args["action"] ?: "stop"
                onControlUpdated("ctrl_timer", false, null, null)
                ExecutionReport(true, "Timer ${action.lowercase()}ed.")
            }

            "media_transport" -> {
                val cmd = call.args["command"] ?: "play"
                val dispatched = dispatchRealMediaKey(cmd)
                val isPlaying = cmd == "play" || cmd == "next" || cmd == "previous"
                onControlUpdated("ctrl_media", isPlaying, null, cmd.replaceFirstChar { it.uppercase() })
                val phrase = when (cmd.lowercase()) {
                    "play" -> "Resumed media playback"
                    "pause" -> "Paused media playback"
                    "next" -> "Skipped to the next track"
                    "previous" -> "Went back to the previous track"
                    "stop" -> "Stopped media playback"
                    else -> "Updated media playback ($cmd)"
                }
                ExecutionReport(
                    true,
                    if (dispatched) "$phrase." else "$phrase (local player state)."
                )
            }

            "play_media_query" -> {
                val rawQ = call.args["query"] ?: "random"
                val (q, wasRandom) = RandomSongCatalog.resolveEffectiveSongQuery(rawQ)
                val targetAppName = call.args["app_name"].orEmpty()
                if (targetAppName.isNotBlank()) {
                    executePlayInApp(appName = targetAppName, rawQuery = q, mediaType = "song", wasRandomized = wasRandom)
                } else {
                    executePlayInApp(appName = "", rawQuery = q, mediaType = "song", wasRandomized = wasRandom)
                }
            }

            "local_search" -> {
                val q = call.args["query"].orEmpty()
                val src = call.args["source"] ?: "all"
                val qVec = embeddingRuntime?.embedQuery(q)
                val hits = ftsHelper.hybridSearch(q, qVec, src, limit = 5)
                val summary = when {
                    hits.isEmpty() -> "I couldn't find anything matching '$q' on your phone."
                    hits.size == 1 -> "Here's what I found: ${cleanDocumentForSpeech(hits.first())}."
                    else -> formatMultipleChoices("matching items", hits) { doc -> cleanDocumentForSpeech(doc) }
                }
                ExecutionReport(true, summary, ftsResults = hits)
            }

            "read_file_snippet" -> {
                val ref = call.args["file_uri"].orEmpty()
                val hits = ftsHelper.search(ref, "all", limit = 1)
                val targetUriStr = hits.firstOrNull()?.uriOrRef ?: ref
                val snippet = readUriSnippet(targetUriStr)
                    ?: hits.firstOrNull()?.let { cleanDocumentForSpeech(it) }
                    ?: "I couldn't open or read '$ref'."
                ExecutionReport(true, snippet, ftsResults = hits)
            }

            "save_memory_note" -> {
                val key = call.args["key"] ?: "note"
                val value = call.args["value"].orEmpty()
                val title = "Memory: ${key.replace('_', ' ')}"
                val vec = embeddingRuntime?.embedDocument(title, value)
                ftsHelper.upsertDocument(
                    docId = "mem_$key",
                    category = "notes",
                    title = title,
                    body = value,
                    uriOrRef = "memory://$key",
                    embedding = vec
                )
                ExecutionReport(true, "Got it, I've remembered that: \"$value\".")
            }

            "recall_memory_note" -> {
                val q = call.args["query"].orEmpty()
                val qVec = embeddingRuntime?.embedQuery(q)
                val hits = ftsHelper.hybridSearch(q, qVec, "notes", limit = 4)
                val summary = when {
                    hits.isEmpty() -> "I don't have any saved notes about '$q'."
                    hits.size == 1 -> "From your notes: ${cleanDocumentForSpeech(hits.first())}."
                    else -> formatMultipleChoices("saved notes", hits) { doc -> cleanDocumentForSpeech(doc) }
                }
                ExecutionReport(true, summary, ftsResults = hits)
            }

            "search_contacts" -> {
                val q = call.args["query"].orEmpty()
                val liveContacts = queryDeviceContacts(q)
                val ftsContacts = ftsHelper.search(q, "contacts", limit = 5)
                val combined = (liveContacts + ftsContacts).distinctBy { it.title + it.body }.take(5)
                val summary = when {
                    combined.isEmpty() -> "I couldn't find a contact named '$q'. You may need to allow Contacts access."
                    combined.size == 1 -> {
                        val c = combined.first()
                        "Found ${c.title}, number ${c.body}."
                    }
                    else -> formatMultipleChoices("contacts", combined) { "${it.title} (${it.body})" }
                }
                ExecutionReport(true, summary, ftsResults = combined)
            }

            "read_recent_messages" -> {
                executeReadRecentMessages(
                    contactFilter = call.args["contact_id"].orEmpty().trim(),
                    scope = call.args["scope"]?.lowercase()?.trim().orEmpty().ifEmpty { "today" },
                    action = call.args["action"]?.lowercase()?.trim().orEmpty(),
                    markReadOnDelivery = !call.args["mark_read"].equals("false", ignoreCase = true),
                    limit = (call.args["limit"]?.toIntOrNull() ?: 6).coerceIn(1, 12)
                )
            }

            "message_send" -> {
                val rawTarget = call.args["contact_id"].orEmpty().trim()
                val body = call.args["text"].orEmpty()
                val rawChannel = call.args["channel"]?.lowercase()?.trim().orEmpty().ifEmpty { "auto" }
                val channel = if (rawChannel == "auto") {
                    agenticMemoryEngine?.getPreferredMessageChannel() ?: "auto"
                } else {
                    rawChannel
                }
                val (resolvedNumber, displayLabel) = resolveContactTarget(rawTarget)
                val opened = dispatchChannelMessageIntent(resolvedNumber, body, channel)
                val channelLabel = if (channel == "auto" || channel == "sms") "" else " via ${channel.replaceFirstChar { it.uppercase() }}"
                ExecutionReport(
                    opened,
                    if (opened) "Ready to send your message to $displayLabel$channelLabel."
                    else "No messaging app is available on this phone."
                )
            }

            "place_call" -> {
                val rawTarget = call.args["contact_id"].orEmpty().trim()
                val speakerphone = call.args["speakerphone"]?.equals("true", ignoreCase = true) == true
                val (resolvedNumber, displayLabel) = resolveContactTarget(rawTarget)
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(resolvedNumber)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val opened = runCatching { context.startActivity(dialIntent) }.isSuccess
                if (opened && speakerphone) {
                    runCatching {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        @Suppress("DEPRECATION")
                        am?.isSpeakerphoneOn = true
                    }
                }
                val numberNote = if (resolvedNumber != displayLabel && resolvedNumber.any { it.isDigit() }) {
                    " ($resolvedNumber)"
                } else ""
                ExecutionReport(opened, if (opened) "Calling $displayLabel$numberNote." else "Phone dialer is unavailable.")
            }

            "handle_incoming_call" -> {
                val action = call.args["action"] ?: "answer"
                val handled = handleTelecomCallAction(action)
                ExecutionReport(
                    handled.first,
                    handled.second
                )
            }

            "query_calendar_events" -> {
                ftsHelper.syncCalendarEventsWithEmbeddings(context, embeddingRuntime)
                val events = ftsHelper.getDocumentsByCategory("calendar", limit = 5)
                val summary = when {
                    events.isEmpty() -> "You have no upcoming calendar events."
                    events.size == 1 -> "Next on your calendar: ${events.first().title}, ${events.first().body}."
                    else -> formatMultipleChoices("upcoming events", events) { "${it.title}, ${it.body}" }
                }
                ExecutionReport(true, summary, ftsResults = events)
            }

            "create_calendar_event" -> {
                val title = call.args["title"]?.trim().orEmpty().ifEmpty { "Nova Reminder" }
                val startIso = call.args["start_iso"]
                val durationMin = call.args["duration_min"]?.toIntOrNull()?.coerceAtLeast(15) ?: 30
                executeCreateCalendarEventOrReminder(title, startIso, durationMin)
            }

            "control_smart_device", "get_device_status" -> {
                ExecutionReport(
                    false,
                    "Smart home controls are currently disabled while focusing on core device & AI skills."
                )
            }

            "ui_open_and_scan" -> {
                val appName = call.args["app_name"] ?: "Settings"
                val scan = NovaAccessibilityService.uiOpenAndScan(context, appName)
                val shot = if (scan.accessibilityEnabled) {
                    NovaAccessibilityService.instance?.captureScreenshotJpeg()
                } else null
                ExecutionReport(
                    success = scan.accessibilityEnabled,
                    summary = scan.message,
                    nodesScanned = scan.nodes,
                    capturedScreenshotBytes = shot
                )
            }

            "ui_act" -> {
                val action = call.args["action"] ?: "CLICK"
                val nodeId = call.args["node_id"] ?: ""
                val text = call.args["text"]
                val liveSvc = NovaAccessibilityService.instance
                    ?: return ExecutionReport(
                        false,
                        "Accessibility Service is not enabled. Enable Nova in Android Accessibility Settings first."
                    )
                val outcome = liveSvc.performUiAct(action, nodeId, text)
                ExecutionReport(
                    success = outcome.success,
                    summary = outcome.message,
                    nodesScanned = outcome.updatedNodes
                )
            }

            "screen_control_app" -> {
                val appName = call.args["app_name"].orEmpty()
                val goal = call.args["goal"] ?: "open"
                val rawQueryText = call.args["query_text"]
                val isPlayGoal = goal.contains("play", ignoreCase = true)
                val isSearchOrPlayGoal = goal.contains("search", ignoreCase = true) || isPlayGoal
                val effectiveQueryText = if (isSearchOrPlayGoal && !rawQueryText.isNullOrBlank() &&
                    RandomSongCatalog.isGenericOrRandomMusicQuery(rawQueryText)
                ) {
                    RandomSongCatalog.pickRandomTrack()
                } else {
                    rawQueryText
                }
                val wasRandomized = effectiveQueryText != rawQueryText

                val match = AppCapabilityRegistry.findBestMatch(context, appName)
                if (match == null) {
                    ExecutionReport(false, "I couldn't find an installed app called '$appName'.")
                } else if (NovaAccessibilityService.instance != null) {
                    val outcome = NovaAccessibilityService.performScreenAutomation(
                        context = context,
                        appName = match.appName,
                        goal = goal,
                        queryText = effectiveQueryText,
                        skipLaunchIfForeground = true
                    )
                    val isReadChoicesGoal = (goal.equals("search_on_screen", ignoreCase = true) ||
                        goal.equals("search", ignoreCase = true) ||
                        goal.startsWith("search ", ignoreCase = true)) && !isPlayGoal
                    val choiceDocs = if (isReadChoicesGoal) {
                        extractScreenChoiceDocs(
                            packageName = match.packageName,
                            nodes = outcome.updatedNodes
                        )
                    } else {
                        emptyList()
                    }
                    val msg = if (wasRandomized && outcome.success && !outcome.message.contains("random", ignoreCase = true)) {
                        "Playing random song '$effectiveQueryText' in ${match.appName}."
                    } else if (isReadChoicesGoal && choiceDocs.size > 1) {
                        formatMultipleChoices("results in ${match.appName}", choiceDocs) { it.title }
                    } else {
                        outcome.message
                    }
                    ExecutionReport(
                        success = outcome.success,
                        summary = msg,
                        nodesScanned = outcome.updatedNodes,
                        ftsResults = choiceDocs
                    )
                } else {
                    val cleanQuery = effectiveQueryText?.trim().orEmpty()
                    val isSearchGoal = goal.equals("search_on_screen", ignoreCase = true) ||
                        goal.equals("search", ignoreCase = true) ||
                        goal.equals("search_and_play", ignoreCase = true)
                    if (isSearchGoal && cleanQuery.isNotEmpty() && tryDispatchAppSearchIntent(match, cleanQuery)) {
                        val prefix = if (wasRandomized) "Playing random song '$cleanQuery'" else "Searching '$cleanQuery'"
                        ExecutionReport(
                            true,
                            "$prefix in ${match.appName} via search intent (enable Nova Accessibility for live UI automation)."
                        )
                    } else {
                        val outcome = NovaAccessibilityService.performScreenAutomation(
                            context = context,
                            appName = match.appName,
                            goal = goal,
                            queryText = effectiveQueryText,
                            skipLaunchIfForeground = true
                        )
                        ExecutionReport(
                            success = outcome.success,
                            summary = outcome.message,
                            nodesScanned = outcome.updatedNodes
                        )
                    }
                }
            }

            "ui_find_and_tap" -> {
                val label = call.args["target_label"].orEmpty()
                val actionType = call.args["action_type"] ?: "click"
                val inputText = call.args["input_text"]
                val liveSvc = NovaAccessibilityService.instance
                    ?: return ExecutionReport(
                        false,
                        "Screen control is turned off right now. Enable Nova in Android Accessibility Settings so I can tap '$label'."
                    )
                val outcome = liveSvc.performFindAndAct(
                    targetLabel = label,
                    actionType = actionType,
                    inputText = inputText
                )
                ExecutionReport(
                    success = outcome.success,
                    summary = outcome.message,
                    nodesScanned = outcome.updatedNodes
                )
            }

            "ui_vision_fallback", "capture_current_screen" -> {
                val svc = NovaAccessibilityService.instance
                if (svc == null) {
                    ExecutionReport(
                        false,
                        "Screen control is turned off right now. Enable Nova in your phone's Accessibility Settings so I can read and control apps."
                    )
                } else {
                    val nodes = svc.scanActiveScreenNodes()
                    val wantShot = call.toolName == "ui_vision_fallback" ||
                        call.args["include_screenshot"]?.equals("true", ignoreCase = true) == true
                    val shotBytes = if (wantShot || nodes.isEmpty()) svc.captureScreenshotJpeg() else null
                    val directWebSnippet = com.example.engine.ScreenTextSanitizer.extractDirectWebAnswer(
                        nodes = nodes,
                        query = call.args["question"].orEmpty()
                    )
                    val visibleText = com.example.engine.ScreenTextSanitizer.extractScreenSummaryLines(nodes, maxLines = 5)
                    val msg = when {
                        !directWebSnippet.isNullOrBlank() -> directWebSnippet
                        visibleText.isNotEmpty() -> "Looking at your screen, I see: ${visibleText.joinToString(", ")}."
                        else -> "I captured what's on your screen."
                    }
                    ExecutionReport(
                        success = true,
                        summary = msg,
                        nodesScanned = nodes,
                        capturedScreenshotBytes = shotBytes
                    )
                }
            }

            "store_turn_artifact" -> {
                val key = call.args["key"]?.trim().orEmpty().ifEmpty { "artifact" }
                val value = call.args["value"].orEmpty()
                turnArtifacts[key] = value
                ExecutionReport(true, "Saved '$key' for the workflow.")
            }

            "get_routine_steps" -> {
                val routineName = call.args["routine_name"]?.trim().orEmpty().ifEmpty { "sync" }
                executeNamedOrRecordedRoutine(routineName)
            }

            "run_recorded_routine" -> {
                val routineName = call.args["routine_name"]?.trim().orEmpty()
                val scopeApp = call.args["scope_app"]?.trim()?.takeIf { it.isNotEmpty() }
                val overrideDelayMs = call.args["override_delay_ms"]?.toLongOrNull()?.coerceIn(0L, 30_000L)
                executeRecordedRoutine(routineName, scopeApp, overrideDelayMs)
            }

            "control_routine_recorder" -> {
                val action = call.args["action"]?.trim()?.lowercase().orEmpty()
                val routineName = call.args["routine_name"]?.trim().orEmpty()
                val targetScope = call.args["target_scope"]?.trim().orEmpty()
                executeRoutineRecorderControl(action, routineName, targetScope)
            }

            "list_installed_apps" -> {
                val filter = call.args["filter"] ?: "all"
                val matches = AppCapabilityRegistry.findMatchingProfiles(context, filter, limit = 6)
                val appDocs = matches.map { app ->
                    LocalIndexDocument(
                        docId = "app_${app.packageName}",
                        category = "apps",
                        title = app.appName,
                        body = app.categoryLabel.substringBefore('/'),
                        uriOrRef = "package://${app.packageName}"
                    )
                }
                val listing = AppCapabilityRegistry.formatFilteredList(context, filter)
                ExecutionReport(true, listing, ftsResults = appDocs)
            }

            "launch_app" -> {
                val rawAppQuery = call.args["app_name"].orEmpty().trim()
                val systemShortcut = resolveSystemSettingsFeature(rawAppQuery)
                if (systemShortcut != null) {
                    return openSystemSettingsPage(systemShortcut)
                }
                val (cleanAppQuery, wantsBrowser) = stripBrowserHint(rawAppQuery)
                if (wantsBrowser && isYouTubeFamilyName(cleanAppQuery)) {
                    return launchYouTubeHomeInBrowser(isYtMusic = isYtMusicName(cleanAppQuery))
                }
                val match = AppCapabilityRegistry.findBestMatch(context, cleanAppQuery)
                if (match == null) {
                    if (isYouTubeFamilyName(cleanAppQuery)) {
                        return launchYouTubeHomeInBrowser(isYtMusic = isYtMusicName(cleanAppQuery))
                    }
                    ExecutionReport(
                        false,
                        "I couldn't find an installed app called '$cleanAppQuery'."
                    )
                } else {
                    val launch = context.packageManager.getLaunchIntentForPackage(match.packageName)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val ok = launch != null && runCatching { context.startActivity(launch) }.isSuccess
                    ExecutionReport(
                        ok,
                        if (ok) "Opening ${match.appName}." else "I couldn't open ${match.appName}."
                    )
                }
            }

            "get_app_skills" -> {
                val appQuery = call.args["app_name"].orEmpty()
                val match = AppCapabilityRegistry.findBestMatch(context, appQuery)
                if (match == null) {
                    ExecutionReport(
                        false,
                        "I couldn't find an installed app called '$appQuery'."
                    )
                } else {
                    ExecutionReport(true, AppCapabilityRegistry.describeAppSkills(match))
                }
            }

            "play_in_app" -> {
                executePlayInApp(
                    appName = call.args["app_name"].orEmpty(),
                    rawQuery = call.args["query"].orEmpty(),
                    mediaType = call.args["media_type"].orEmpty(),
                    useBrowser = call.args["use_browser"].equals("true", ignoreCase = true)
                )
            }

            "search_in_app" -> {
                executeSearchInApp(
                    appName = call.args["app_name"].orEmpty(),
                    query = call.args["query"].orEmpty(),
                    useBrowser = call.args["use_browser"].equals("true", ignoreCase = true)
                )
            }

            "navigate_in_maps" -> {
                executeNavigateInMaps(
                    destination = call.args["destination"].orEmpty(),
                    appName = call.args["app_name"].orEmpty()
                )
            }

            "manage_list_item" -> {
                executeListManagement(
                    listName = call.args["list_name"] ?: "shopping",
                    action = call.args["action"] ?: "list",
                    item = call.args["item"].orEmpty()
                )
            }

            "get_device_briefing" -> {
                val topic = call.args["topic"] ?: "morning"
                val query = call.args["query"].orEmpty()
                ExecutionReport(true, buildDeviceBriefing(topic, query))
            }

            "manage_agentic_memory" -> {
                executeManageAgenticMemory(
                    action = call.args["action"].orEmpty(),
                    target = call.args["target"].orEmpty(),
                    detail = call.args["detail"].orEmpty()
                )
            }

            "answer_web_question" -> {
                val query = call.args["query"].orEmpty().trim()
                if (query.isBlank()) {
                    ExecutionReport(false, "Tell me what question you'd like me to answer.")
                } else {
                    val direct = com.example.engine.WebKnowledgeResolver.fetchCleanAnswer(query)
                    if (!direct.isNullOrBlank()) {
                        ExecutionReport(true, direct)
                    } else {
                        ExecutionReport(
                            false,
                            "I couldn't reach live web facts right now to look up '$query'."
                        )
                    }
                }
            }

            else -> ExecutionReport(false, "Unsupported tool: ${call.toolName}")
        }
    }

    private suspend fun executePlayInApp(
        appName: String,
        rawQuery: String,
        mediaType: String,
        wasRandomized: Boolean? = null,
        useBrowser: Boolean = false
    ): ExecutionReport {
        val (effectiveQuery, isRandomSong) = if (wasRandomized != null) {
            rawQuery.trim().ifEmpty { RandomSongCatalog.pickRandomTrack() } to wasRandomized
        } else {
            RandomSongCatalog.resolveEffectiveSongQuery(rawQuery)
        }
        val allowGenericMediaKeyResume = rawQuery.trim().lowercase() in setOf(
            "", "music", "some music", "any music", "song", "a song", "songs",
            "random", "a random song", "random song"
        )
        val audioMgrAtStart = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val wasMusicAlreadyPlaying = runCatching { audioMgrAtStart?.isMusicActive == true }.getOrDefault(false)

        val (cleanAppName, inferredBrowser) = stripBrowserHint(appName)
        val rawPreferredMusicApp = agenticMemoryEngine?.getPreferredMusicApp().orEmpty()
        val sanitizedPreferredMusicApp = stripBrowserHint(rawPreferredMusicApp).first.takeUnless { pref ->
            val lower = pref.lowercase()
            lower.isBlank() ||
                lower.contains("browser") ||
                lower.contains("chrome") ||
                lower.contains("firefox") ||
                lower.contains("edge") ||
                lower.contains("brave") ||
                lower.contains("opera")
        }.orEmpty()
        val effectiveAppName = cleanAppName.ifBlank { sanitizedPreferredMusicApp }
        val forceBrowser = useBrowser || inferredBrowser
        val hasInternet = com.example.engine.WebKnowledgeResolver.isInternetAvailable(context)

        val pickRandomOnline = !isRandomSong && RandomSongCatalog.isDescriptiveGenreOrMoodQuery(rawQuery)
        val resolvedOnlineTrack = if (hasInternet) {
            com.example.engine.WebKnowledgeResolver.resolveMusicTrack(effectiveQuery, pickRandom = pickRandomOnline)
        } else {
            null
        }
        val playbackQuery = resolvedOnlineTrack?.searchQueryForPlayback ?: effectiveQuery
        val displayTrackLabel = resolvedOnlineTrack?.displayTitleAndArtist ?: effectiveQuery

        val matchedCandidate = if (effectiveAppName.isNotBlank()) {
            AppCapabilityRegistry.findBestMatch(context, effectiveAppName)
        } else {
            AppCapabilityRegistry.findPreferredMediaApp(context)
        }
        val match = if (!forceBrowser &&
            (matchedCandidate == null || (cleanAppName.isBlank() && AppCapabilityRegistry.isBrowserApp(matchedCandidate)))
        ) {
            if (cleanAppName.isBlank()) {
                AppCapabilityRegistry.findPreferredMediaApp(context)
            } else {
                matchedCandidate
            }
        } else {
            matchedCandidate
        }

        val isYtTarget = isYouTubeFamilyName(effectiveAppName) ||
            (match != null && match.packageName.contains("youtube", ignoreCase = true))

        if (forceBrowser && isYtTarget) {
            return executeYouTubeBrowserFlow(
                isYtMusic = isYtMusicName(effectiveAppName) ||
                    match?.packageName == "com.google.android.apps.youtube.music" ||
                    (cleanAppName.isBlank() && mediaType.equals("song", ignoreCase = true)),
                query = playbackQuery,
                autoPlay = true,
                isRandomSong = isRandomSong,
                directVideoId = resolvedOnlineTrack?.youtubeVideoId,
                displayLabel = displayTrackLabel,
                wasAlreadyPlaying = wasMusicAlreadyPlaying,
                allowGenericMediaKeyResume = allowGenericMediaKeyResume
            )
        }

        val trackPhrase = if (isRandomSong) "random song '$displayTrackLabel'" else "'$displayTrackLabel'"
        val pm = context.packageManager

        if (match == null) {
            if (cleanAppName.isNotBlank()) {
                if (isYtTarget) {
                    return executeYouTubeBrowserFlow(
                        isYtMusic = isYtMusicName(cleanAppName),
                        query = playbackQuery,
                        autoPlay = true,
                        isRandomSong = isRandomSong,
                        directVideoId = resolvedOnlineTrack?.youtubeVideoId,
                        displayLabel = displayTrackLabel,
                        wasAlreadyPlaying = wasMusicAlreadyPlaying,
                        allowGenericMediaKeyResume = allowGenericMediaKeyResume
                    )
                }
                return ExecutionReport(false, "I couldn't find '$cleanAppName' installed on your phone.")
            }

            // 1. Try system-wide MEDIA_PLAY_FROM_SEARCH (rich + minimal) targeting any installed music handler
            val richFallbackIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                putExtra(android.app.SearchManager.QUERY, playbackQuery)
                putExtra(MediaStore.EXTRA_MEDIA_TITLE, resolvedOnlineTrack?.title ?: playbackQuery)
                if (!resolvedOnlineTrack?.artist.isNullOrBlank()) {
                    putExtra(MediaStore.EXTRA_MEDIA_ARTIST, resolvedOnlineTrack?.artist)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val minimalFallbackIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(android.app.SearchManager.QUERY, playbackQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val nonBrowserMediaHandler = runCatching {
                pm.queryIntentActivities(minimalFallbackIntent, 0)
                    .mapNotNull { it.activityInfo?.packageName }
                    .firstOrNull { pkg ->
                        val lower = pkg.lowercase()
                        !lower.contains("chrome") && !lower.contains("browser") &&
                            !lower.contains("firefox") && !lower.contains("edge") &&
                            !lower.contains("brave") && !lower.contains("opera")
                    }
            }.getOrNull()
            if (nonBrowserMediaHandler != null) {
                richFallbackIntent.setPackage(nonBrowserMediaHandler)
                minimalFallbackIntent.setPackage(nonBrowserMediaHandler)
                val started = (richFallbackIntent.resolveActivity(pm) != null &&
                    runCatching { context.startActivity(richFallbackIntent) }.isSuccess) ||
                    (minimalFallbackIntent.resolveActivity(pm) != null &&
                        runCatching { context.startActivity(minimalFallbackIntent) }.isSuccess)
                if (started) {
                    onControlUpdated("ctrl_media", true, null, "Playing")
                    return ExecutionReport(true, "Playing $trackPhrase in your music app.")
                }
            } else if (richFallbackIntent.resolveActivity(pm) != null &&
                runCatching { context.startActivity(richFallbackIntent) }.isSuccess
            ) {
                onControlUpdated("ctrl_media", true, null, "Playing")
                return ExecutionReport(true, "Playing $trackPhrase.")
            }

            // 2. Try device's default CATEGORY_APP_MUSIC app directly before ever falling back to a browser
            val musicCategoryPkg = runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0)
                    .mapNotNull { it.activityInfo?.packageName }
                    .firstOrNull { it != context.packageName }
            }.getOrNull()
            if (musicCategoryPkg != null) {
                val musicLaunch = pm.getLaunchIntentForPackage(musicCategoryPkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (musicLaunch != null && runCatching { context.startActivity(musicLaunch) }.isSuccess) {
                    delay(500L)
                    val acc = NovaAccessibilityService.instance
                    if (acc != null) {
                        val screenOutcome = acc.performLocalSearchAndPlay(
                            queryText = playbackQuery,
                            playFirstResult = true,
                            appHint = "Music"
                        )
                        if (screenOutcome.success) {
                            onControlUpdated("ctrl_media", true, null, "Playing")
                            return ExecutionReport(
                                true,
                                "Opened your music app and started playing $trackPhrase.",
                                nodesScanned = screenOutcome.updatedNodes
                            )
                        }
                    }
                    if (allowGenericMediaKeyResume) {
                        dispatchRealMediaKey("play")
                    }
                    onControlUpdated("ctrl_media", true, null, "Playing")
                    return ExecutionReport(true, "Opened your music app to play $trackPhrase.")
                }
            }

            if (!hasInternet) {
                return ExecutionReport(
                    false,
                    "You're offline right now — there's no internet connection to search online for '$effectiveQuery'."
                )
            }

            return executeYouTubeBrowserFlow(
                isYtMusic = isYtMusicName(effectiveAppName) ||
                    (cleanAppName.isBlank() && mediaType.equals("song", ignoreCase = true)),
                query = playbackQuery,
                autoPlay = true,
                isRandomSong = isRandomSong,
                directVideoId = resolvedOnlineTrack?.youtubeVideoId,
                displayLabel = displayTrackLabel,
                wasAlreadyPlaying = wasMusicAlreadyPlaying,
                allowGenericMediaKeyResume = allowGenericMediaKeyResume
            )
        }

        // Direct YouTube / YouTube Music watch URL when a direct video ID resolved online (specific OR randomized tracks)
        if (isYtTarget && resolvedOnlineTrack?.youtubeVideoId != null) {
            val ytPkg = match.packageName
            val isYtMusicPkg = ytPkg == "com.google.android.apps.youtube.music" || isYtMusicName(effectiveAppName)
            val candidateWatchUrls = if (isYtMusicPkg) {
                listOf(
                    "https://music.youtube.com/watch?v=${resolvedOnlineTrack.youtubeVideoId}",
                    "https://www.youtube.com/watch?v=${resolvedOnlineTrack.youtubeVideoId}"
                )
            } else {
                listOf("https://www.youtube.com/watch?v=${resolvedOnlineTrack.youtubeVideoId}")
            }
            for (watchUrl in candidateWatchUrls) {
                val watchIntent = Intent(Intent.ACTION_VIEW, Uri.parse(watchUrl)).apply {
                    setPackage(ytPkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (watchIntent.resolveActivity(pm) != null &&
                    runCatching { context.startActivity(watchIntent) }.isSuccess
                ) {
                    onControlUpdated("ctrl_media", true, null, "Playing")
                    return ExecutionReport(true, "Playing $trackPhrase in ${match.appName}.")
                }
            }
        }

        if (match.categoryLabel == "Audio/Music") {
            val playSearchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(match.packageName)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/audio")
                putExtra(android.app.SearchManager.QUERY, playbackQuery)
                putExtra(MediaStore.EXTRA_MEDIA_TITLE, resolvedOnlineTrack?.title ?: playbackQuery)
                if (!resolvedOnlineTrack?.artist.isNullOrBlank()) {
                    putExtra(MediaStore.EXTRA_MEDIA_ARTIST, resolvedOnlineTrack?.artist)
                }
                if (mediaType.isNotBlank()) {
                    putExtra("media_type", mediaType)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val minimalPlaySearchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(match.packageName)
                putExtra(android.app.SearchManager.QUERY, playbackQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val startedPlaySearch = (playSearchIntent.resolveActivity(pm) != null &&
                runCatching { context.startActivity(playSearchIntent) }.isSuccess) ||
                (minimalPlaySearchIntent.resolveActivity(pm) != null &&
                    runCatching { context.startActivity(minimalPlaySearchIntent) }.isSuccess)
            if (startedPlaySearch) {
                onControlUpdated("ctrl_media", true, null, "Playing")
                val acc = NovaAccessibilityService.instance
                if (acc != null) {
                    val verifyOutcome = acc.verifyAndTriggerMediaPlayback(
                        targetPkg = match.packageName,
                        queryText = playbackQuery,
                        appName = match.appName,
                        wasAlreadyPlaying = wasMusicAlreadyPlaying
                    )
                    val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                    if (allowGenericMediaKeyResume && !verifyOutcome.success && audioMgr?.isMusicActive != true) {
                        dispatchRealMediaKey("play")
                    }
                    val finalMsg = if (isRandomSong) {
                        "Playing $trackPhrase in ${match.appName}."
                    } else {
                        verifyOutcome.message.ifBlank { "Playing $trackPhrase in ${match.appName}." }
                    }
                    return ExecutionReport(
                        success = true,
                        summary = finalMsg,
                        nodesScanned = verifyOutcome.updatedNodes
                    )
                }
                delay(700L)
                val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (allowGenericMediaKeyResume && audioMgr?.isMusicActive != true) {
                    dispatchRealMediaKey("play")
                }
                return ExecutionReport(true, "Playing $trackPhrase in ${match.appName}.")
            }
        }

        val deepLinks = buildAppSearchIntents(match.packageName, match.categoryLabel, playbackQuery)
        for (deepIntent in deepLinks) {
            if (deepIntent.resolveActivity(pm) != null &&
                runCatching { context.startActivity(deepIntent) }.isSuccess
            ) {
                onControlUpdated("ctrl_media", true, null, "Playing")
                val acc = NovaAccessibilityService.instance
                if (acc != null) {
                    val verifyOutcome = acc.verifyAndTriggerMediaPlayback(
                        targetPkg = match.packageName,
                        queryText = playbackQuery,
                        appName = match.appName,
                        wasAlreadyPlaying = wasMusicAlreadyPlaying
                    )
                    if (verifyOutcome.success) {
                        val finalMsg = if (isRandomSong) {
                            "Playing $trackPhrase in ${match.appName}."
                        } else {
                            verifyOutcome.message
                        }
                        return ExecutionReport(
                            true,
                            finalMsg,
                            nodesScanned = verifyOutcome.updatedNodes
                        )
                    }
                }
                delay(650L)
                if (allowGenericMediaKeyResume) {
                    dispatchRealMediaKey("play")
                }
                return ExecutionReport(true, "Opened ${match.appName} to play $trackPhrase.")
            }
        }

        val inAppSearch = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(match.packageName)
            putExtra(android.app.SearchManager.QUERY, playbackQuery)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (inAppSearch.resolveActivity(pm) != null &&
            runCatching { context.startActivity(inAppSearch) }.isSuccess
        ) {
            onControlUpdated("ctrl_media", true, null, "Playing")
            val acc = NovaAccessibilityService.instance
            if (acc != null) {
                val verifyOutcome = acc.verifyAndTriggerMediaPlayback(
                    targetPkg = match.packageName,
                    queryText = playbackQuery,
                    appName = match.appName,
                    wasAlreadyPlaying = wasMusicAlreadyPlaying
                )
                return ExecutionReport(
                    true,
                    verifyOutcome.message.ifBlank { "Playing $trackPhrase in ${match.appName}." },
                    nodesScanned = verifyOutcome.updatedNodes
                )
            }
            return ExecutionReport(true, "Opened ${match.appName} to play $trackPhrase.")
        }

        val launch = pm.getLaunchIntentForPackage(match.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launch != null && runCatching { context.startActivity(launch) }.isSuccess) {
            delay(500L)
            val acc = NovaAccessibilityService.instance
            if (acc != null) {
                val screenOutcome = acc.performLocalSearchAndPlay(
                    queryText = playbackQuery,
                    playFirstResult = true,
                    appHint = match.appName
                )
                if (screenOutcome.success) {
                    onControlUpdated("ctrl_media", true, null, "Playing")
                    return ExecutionReport(
                        true,
                        "Opened ${match.appName} and started playing $trackPhrase via local screen control.",
                        nodesScanned = screenOutcome.updatedNodes
                    )
                }
            }
            if (allowGenericMediaKeyResume) {
                dispatchRealMediaKey("play")
            }
            onControlUpdated("ctrl_media", true, null, "Playing")
            return ExecutionReport(
                true,
                "Opened ${match.appName} to play $trackPhrase."
            )
        }

        if (isYtTarget) {
            return executeYouTubeBrowserFlow(
                isYtMusic = isYtMusicName(cleanAppName) ||
                    match.packageName == "com.google.android.apps.youtube.music",
                query = playbackQuery,
                autoPlay = true,
                isRandomSong = isRandomSong,
                directVideoId = resolvedOnlineTrack?.youtubeVideoId,
                displayLabel = displayTrackLabel,
                wasAlreadyPlaying = wasMusicAlreadyPlaying,
                allowGenericMediaKeyResume = allowGenericMediaKeyResume
            )
        }

        return ExecutionReport(false, "I found ${match.appName}, but couldn't start playback in it.")
    }

    private suspend fun executeSearchInApp(
        appName: String,
        query: String,
        useBrowser: Boolean = false
    ): ExecutionReport {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return ExecutionReport(false, "Tell me what you'd like me to search for.")
        }
        val (cleanAppName, inferredBrowser) = stripBrowserHint(appName)
        val forceBrowser = useBrowser || inferredBrowser
        val match = AppCapabilityRegistry.findBestMatch(context, cleanAppName)
        val isYtTarget = isYouTubeFamilyName(cleanAppName) ||
            (match != null && match.packageName.contains("youtube", ignoreCase = true))
        val isWebSearchTarget = !isYtTarget && (
            cleanAppName.equals("google", ignoreCase = true) ||
                cleanAppName.equals("google search", ignoreCase = true) ||
                cleanAppName.equals("web", ignoreCase = true) ||
                match?.categoryLabel == "Browser/Web" ||
                match?.packageName == "com.google.android.googlequicksearchbox"
            )

        if (isWebSearchTarget && !forceBrowser &&
            com.example.engine.WebKnowledgeResolver.isGeneralKnowledgeQuestion(cleanQuery)
        ) {
            val directKnowledge = com.example.engine.WebKnowledgeResolver.fetchCleanAnswer(cleanQuery)
            if (!directKnowledge.isNullOrBlank()) {
                return ExecutionReport(true, directKnowledge)
            }
        }

        if ((forceBrowser || match == null) && isYtTarget) {
            return executeYouTubeBrowserFlow(
                isYtMusic = isYtMusicName(cleanAppName) ||
                    match?.packageName == "com.google.android.apps.youtube.music",
                query = cleanQuery,
                autoPlay = false,
                isRandomSong = false
            )
        }

        if (match == null) {
            return ExecutionReport(false, "I couldn't find an installed app called '$cleanAppName'.")
        }

        val pm = context.packageManager

        if (tryDispatchAppSearchIntent(match, cleanQuery)) {
            val acc = NovaAccessibilityService.instance
            if (acc != null) {
                acc.waitForTargetAppWindow(match.packageName, timeoutMs = 2400L)
                delay(1100L)
                repeat(2) {
                    acc.tryAutoResolveNonBlockingDialog(
                        recordedAccount = null,
                        appHint = match.appName,
                        onAccountDetected = {}
                    )
                }
                var liveNodes = acc.scanActiveScreenNodes(match.packageName)
                var directAnswer = if (isWebSearchTarget) {
                    com.example.engine.ScreenTextSanitizer.extractDirectWebAnswer(liveNodes, cleanQuery)
                } else null
                var choices = extractScreenChoiceDocs(match.packageName, liveNodes)
                if (directAnswer == null && choices.isEmpty()) {
                    delay(800L)
                    liveNodes = acc.scanActiveScreenNodes(match.packageName)
                    directAnswer = if (isWebSearchTarget) {
                        com.example.engine.ScreenTextSanitizer.extractDirectWebAnswer(liveNodes, cleanQuery)
                    } else null
                    choices = extractScreenChoiceDocs(match.packageName, liveNodes)
                }
                if (!directAnswer.isNullOrBlank()) {
                    return ExecutionReport(
                        success = true,
                        summary = directAnswer,
                        nodesScanned = liveNodes
                    )
                }
                if (choices.size > 1) {
                    return ExecutionReport(
                        success = true,
                        summary = formatMultipleChoices("results in ${match.appName}", choices) { it.title },
                        nodesScanned = liveNodes,
                        ftsResults = choices
                    )
                }
                if (choices.size == 1) {
                    return ExecutionReport(
                        success = true,
                        summary = "I found one result for '$cleanQuery' in ${match.appName}: ${choices.first().title}. Would you like me to open it?",
                        nodesScanned = liveNodes,
                        ftsResults = choices
                    )
                }
            }
            return ExecutionReport(
                true,
                if (NovaAccessibilityService.instance == null) {
                    "Searching for '$cleanQuery' in ${match.appName}. Enable Nova Accessibility in Settings so I can read the result choices to you."
                } else {
                    "Searching for '$cleanQuery' in ${match.appName}."
                }
            )
        }

        // 3. No search intent supported (e.g., Gallery, Google Photos, Social/Shopping apps) ->
        //    Launch the app and proceed with Local Screen Control Automation!
        val acc = NovaAccessibilityService.instance
        val alreadyOnTargetApp = acc?.rootInActiveWindow?.packageName?.toString() == match.packageName
        val launch = pm.getLaunchIntentForPackage(match.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launched = !alreadyOnTargetApp &&
            launch != null &&
            runCatching { context.startActivity(launch) }.isSuccess
        if (launched || alreadyOnTargetApp) {
            if (acc != null) {
                if (!alreadyOnTargetApp) delay(500L)
                val screenOutcome = acc.performLocalSearchAndPlay(
                    queryText = cleanQuery,
                    playFirstResult = false,
                    appHint = match.appName
                )
                val directAnswer = if (isWebSearchTarget) {
                    com.example.engine.ScreenTextSanitizer.extractDirectWebAnswer(screenOutcome.updatedNodes, cleanQuery)
                } else null
                if (!directAnswer.isNullOrBlank()) {
                    return ExecutionReport(
                        success = true,
                        summary = directAnswer,
                        nodesScanned = screenOutcome.updatedNodes
                    )
                }
                val choices = extractScreenChoiceDocs(match.packageName, screenOutcome.updatedNodes)
                if (screenOutcome.success) {
                    val summary = when {
                        choices.size > 1 -> formatMultipleChoices("results in ${match.appName}", choices) { it.title }
                        choices.size == 1 -> "I found one result for '$cleanQuery' in ${match.appName}: ${choices.first().title}. Would you like me to open it?"
                        else -> screenOutcome.message
                    }
                    return ExecutionReport(
                        success = true,
                        summary = summary,
                        nodesScanned = screenOutcome.updatedNodes,
                        ftsResults = choices
                    )
                }
                return ExecutionReport(
                    true,
                    screenOutcome.message,
                    nodesScanned = screenOutcome.updatedNodes,
                    ftsResults = choices
                )
            }
            return ExecutionReport(
                true,
                "Opened ${match.appName}. Enable Nova Accessibility in Settings so I can search '$cleanQuery' and read the choices to you."
            )
        }

        if (isYtTarget) {
            return executeYouTubeBrowserFlow(
                isYtMusic = isYtMusicName(cleanAppName) ||
                    match.packageName == "com.google.android.apps.youtube.music",
                query = cleanQuery,
                autoPlay = false,
                isRandomSong = false
            )
        }

        return ExecutionReport(false, "I couldn't open ${match.appName} to search for '$cleanQuery'.")
    }

    private suspend fun executeYouTubeBrowserFlow(
        isYtMusic: Boolean,
        query: String,
        autoPlay: Boolean,
        isRandomSong: Boolean,
        directVideoId: String? = null,
        displayLabel: String = query,
        wasAlreadyPlaying: Boolean = false,
        allowGenericMediaKeyResume: Boolean = false
    ): ExecutionReport {
        val displayName = if (isYtMusic) "YouTube Music" else "YouTube"
        val hasInternet = com.example.engine.WebKnowledgeResolver.isInternetAvailable(context)
        if (!hasInternet) {
            return ExecutionReport(
                false,
                "I couldn't check online for '$displayLabel' because there is no internet connection right now."
            )
        }

        val resolvedTrack = if (autoPlay && directVideoId == null) {
            com.example.engine.WebKnowledgeResolver.resolveMusicTrack(query)
        } else null
        val effectiveVideoId = directVideoId ?: resolvedTrack?.youtubeVideoId
        val effectiveDisplay = resolvedTrack?.displayTitleAndArtist ?: displayLabel
        val encoded = Uri.encode(resolvedTrack?.searchQueryForPlayback ?: query)
        val candidateUrls = when {
            !effectiveVideoId.isNullOrBlank() && isYtMusic -> listOf(
                "https://music.youtube.com/watch?v=$effectiveVideoId",
                "https://www.youtube.com/watch?v=$effectiveVideoId"
            )
            !effectiveVideoId.isNullOrBlank() -> listOf(
                "https://www.youtube.com/watch?v=$effectiveVideoId"
            )
            isYtMusic -> listOf(
                "https://music.youtube.com/search?q=$encoded",
                "https://www.youtube.com/results?search_query=$encoded"
            )
            else -> listOf(
                "https://www.youtube.com/results?search_query=$encoded"
            )
        }
        val pm = context.packageManager
        val browser = AppCapabilityRegistry.discoverInstalledApps(context)
            .firstOrNull { it.categoryLabel == "Browser/Web" }
        var resolvedIntent: Intent? = null
        for (candidateUrl in candidateUrls) {
            val attempt = Intent(Intent.ACTION_VIEW, Uri.parse(candidateUrl)).apply {
                if (browser != null) setPackage(browser.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val ok = attempt.resolveActivity(pm) != null || run {
                attempt.setPackage(null)
                attempt.resolveActivity(pm) != null
            }
            if (ok) {
                resolvedIntent = attempt
                break
            }
        }
        val webIntent = resolvedIntent
        if (webIntent == null || runCatching { context.startActivity(webIntent) }.isFailure) {
            return ExecutionReport(
                false,
                "No web browser app is available to open $displayName for '$effectiveDisplay'."
            )
        }

        val targetBrowserPkg = webIntent.`package` ?: browser?.packageName
        val browserLabel = browser?.appName ?: "Browser"
        if (autoPlay) {
            onControlUpdated("ctrl_media", true, null, "Playing")
        }

        val acc = NovaAccessibilityService.instance
        if (acc != null) {
            acc.waitForTargetAppWindow(targetBrowserPkg, timeoutMs = 2800L)
            delay(950L)
            repeat(2) {
                acc.tryAutoResolveNonBlockingDialog(
                    recordedAccount = null,
                    appHint = displayName,
                    onAccountDetected = {}
                )
            }

            if (autoPlay) {
                if (!effectiveVideoId.isNullOrBlank()) {
                    return ExecutionReport(
                        success = true,
                        summary = "Playing '$effectiveDisplay' on $displayName in $browserLabel."
                    )
                }
                val verifyOutcome = acc.verifyAndTriggerMediaPlayback(
                    targetPkg = targetBrowserPkg,
                    queryText = query,
                    appName = "$displayName ($browserLabel)",
                    wasAlreadyPlaying = wasAlreadyPlaying
                )
                val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                if (allowGenericMediaKeyResume && !verifyOutcome.success && audioMgr?.isMusicActive != true) {
                    dispatchRealMediaKey("play")
                }
                val trackPhrase = if (isRandomSong) "random song '$effectiveDisplay'" else "'$effectiveDisplay'"
                val finalMsg = if (isRandomSong) {
                    "Playing $trackPhrase on $displayName in $browserLabel."
                } else {
                    verifyOutcome.message.ifBlank {
                        "Opened $displayName in $browserLabel and started '$effectiveDisplay'."
                    }
                }
                return ExecutionReport(
                    success = true,
                    summary = finalMsg,
                    nodesScanned = verifyOutcome.updatedNodes
                )
            } else {
                val pkgKey = targetBrowserPkg ?: "browser"
                var liveNodes = acc.scanActiveScreenNodes(targetBrowserPkg)
                var choices = extractScreenChoiceDocs(pkgKey, liveNodes)
                if (choices.isEmpty()) {
                    delay(850L)
                    liveNodes = acc.scanActiveScreenNodes(targetBrowserPkg)
                    choices = extractScreenChoiceDocs(pkgKey, liveNodes)
                }
                if (choices.size > 1) {
                    return ExecutionReport(
                        success = true,
                        summary = formatMultipleChoices("results on $displayName ($browserLabel)", choices) { it.title },
                        nodesScanned = liveNodes,
                        ftsResults = choices
                    )
                }
                if (choices.size == 1) {
                    return ExecutionReport(
                        success = true,
                        summary = "I found one result for '$effectiveDisplay' on $displayName in $browserLabel: ${choices.first().title}. Would you like me to open it?",
                        nodesScanned = liveNodes,
                        ftsResults = choices
                    )
                }
                return ExecutionReport(
                    success = true,
                    summary = "Opened $displayName search for '$effectiveDisplay' in $browserLabel, and checked the page for results.",
                    nodesScanned = liveNodes
                )
            }
        }

        val trackPhrase = if (isRandomSong) "random song '$effectiveDisplay'" else "'$effectiveDisplay'"
        return ExecutionReport(
            success = true,
            summary = if (autoPlay) {
                if (!effectiveVideoId.isNullOrBlank()) {
                    "Playing $trackPhrase on $displayName in $browserLabel."
                } else {
                    "Opened $trackPhrase on $displayName in $browserLabel."
                }
            } else {
                "Opened $displayName search for '$effectiveDisplay' in $browserLabel. Enable Nova Accessibility so I can check and read the page choices to you."
            }
        )
    }

    private fun launchYouTubeHomeInBrowser(isYtMusic: Boolean): ExecutionReport {
        val url = if (isYtMusic) "https://music.youtube.com" else "https://www.youtube.com"
        val displayName = if (isYtMusic) "YouTube Music" else "YouTube"
        val browser = AppCapabilityRegistry.discoverInstalledApps(context)
            .firstOrNull { it.categoryLabel == "Browser/Web" }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (browser != null) setPackage(browser.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pm = context.packageManager
        val canResolve = intent.resolveActivity(pm) != null || run {
            intent.setPackage(null)
            intent.resolveActivity(pm) != null
        }
        val ok = canResolve && runCatching { context.startActivity(intent) }.isSuccess
        return ExecutionReport(
            ok,
            if (ok) "Opening $displayName in ${browser?.appName ?: "browser"}."
            else "No web browser is available to open $displayName."
        )
    }

    private fun stripBrowserHint(rawApp: String): Pair<String, Boolean> {
        val browserSuffix = Regex("\\s+(?:via|in|on|using)\\s+(?:the\\s+)?(?:web\\s+)?(?:browser|chrome|web)$", RegexOption.IGNORE_CASE)
        val wantsBrowser = browserSuffix.containsMatchIn(rawApp)
        val clean = rawApp.replace(browserSuffix, "")
            .removeSuffix(" app")
            .removeSuffix(" App")
            .trim()
        return clean to wantsBrowser
    }

    private fun isYouTubeFamilyName(rawName: String): Boolean {
        val l = rawName.trim().lowercase()
        return l == "youtube" || l == "yt" || l == "youtube music" || l == "yt music" || l == "ytm" || l == "youitube music"
    }

    private fun isYtMusicName(rawName: String): Boolean {
        val l = rawName.trim().lowercase()
        return l.contains("music") || l == "ytm"
    }

    private fun resolveSystemSettingsFeature(rawAppQuery: String): String? {
        val clean = rawAppQuery.trim().lowercase()
            .removePrefix("the ")
            .removeSuffix(" settings")
            .removeSuffix(" panel")
            .removeSuffix(" page")
            .trim()
        return when (clean) {
            "bluetooth" -> "bluetooth"
            "wifi", "wi-fi" -> "wifi"
            "location" -> "location"
            "display", "brightness", "screen" -> "display"
            "sound", "volume", "audio" -> "sound"
            "battery" -> "battery"
            "accessibility" -> "accessibility"
            else -> null
        }
    }

    private fun openSystemSettingsPage(feature: String): ExecutionReport {
        val clean = feature.trim().lowercase()
        val action = when (clean) {
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "wifi", "wi-fi" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Settings.Panel.ACTION_WIFI
            } else {
                Settings.ACTION_WIFI_SETTINGS
            }
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "notifications" -> Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val opened = runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        val label = clean.replaceFirstChar { it.uppercase() }
        return ExecutionReport(
            opened,
            if (opened) "Opened $label Settings." else "Couldn't open $label Settings."
        )
    }

    private fun extractScreenChoiceDocs(
        packageName: String,
        nodes: List<UiNodeSnapshot>
    ): List<LocalIndexDocument> {
        val picked = com.example.engine.ScreenTextSanitizer.filterAndCleanChoices(nodes, maxChoices = 4)
        return picked.map { node ->
            LocalIndexDocument(
                docId = node.id,
                category = "screen_choice",
                title = node.label,
                body = node.nodeType,
                uriOrRef = "screen://$packageName/${node.id}"
            )
        }
    }

    private fun tryDispatchAppSearchIntent(
        match: AppCapabilityRegistry.InstalledAppProfile,
        cleanQuery: String
    ): Boolean {
        val pm = context.packageManager
        val candidateIntents = buildAppSearchIntents(match.packageName, match.categoryLabel, cleanQuery)
        for (intent in candidateIntents) {
            if (intent.resolveActivity(pm) != null && runCatching { context.startActivity(intent) }.isSuccess) {
                return true
            }
        }

        val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(match.packageName)
            putExtra(android.app.SearchManager.QUERY, cleanQuery)
            putExtra("query", cleanQuery)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (searchIntent.resolveActivity(pm) != null &&
            runCatching { context.startActivity(searchIntent) }.isSuccess
        ) {
            return true
        }

        val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            setPackage(match.packageName)
            putExtra(android.app.SearchManager.QUERY, cleanQuery)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return webSearchIntent.resolveActivity(pm) != null &&
            runCatching { context.startActivity(webSearchIntent) }.isSuccess
    }

    private fun executeNavigateInMaps(destination: String, appName: String): ExecutionReport {
        val cleanDest = destination.trim()
        if (cleanDest.isBlank()) {
            return ExecutionReport(false, "Tell me where you want directions to.")
        }
        val preferredApp = appName.trim().ifBlank {
            agenticMemoryEngine?.getPreferredMapsApp().orEmpty()
        }
        val match = if (preferredApp.isNotBlank()) {
            AppCapabilityRegistry.findBestMatch(context, preferredApp)
        } else {
            AppCapabilityRegistry.findMatchingProfiles(context, "Maps/Navigation", limit = 1).firstOrNull()
        }

        val pm = context.packageManager
        val navIntent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${Uri.encode(cleanDest)}")).apply {
            if (match != null) setPackage(match.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (navIntent.resolveActivity(pm) != null &&
            runCatching { context.startActivity(navIntent) }.isSuccess
        ) {
            val inApp = match?.let { " in ${it.appName}" }.orEmpty()
            return ExecutionReport(true, "Starting directions to $cleanDest$inApp.")
        }

        val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(cleanDest)}")).apply {
            if (match != null) setPackage(match.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(geoIntent) }.isSuccess) {
            val inApp = match?.let { " in ${it.appName}" }.orEmpty()
            return ExecutionReport(true, "Opening directions to $cleanDest$inApp.")
        }

        return ExecutionReport(false, "No maps or navigation app was found on this phone.")
    }

    @Volatile
    private var isTorchOnPlaceholder: Boolean = false

    @SuppressLint("MissingPermission")
    private suspend fun executeBluetoothToggle(state: String): ExecutionReport {
        if (state.equals("settings", ignoreCase = true)) {
            openBluetoothSettings()
            return ExecutionReport(true, "Opened Bluetooth Settings.")
        }

        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return ExecutionReport(false, "Bluetooth hardware adapter unavailable on this device.")

        val canInspect = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

        if (canInspect) {
            val currentlyOn = runCatching { adapter.isEnabled }.getOrNull()
            val desiredOn = when (state) {
                "on" -> true
                "off" -> false
                else -> !(currentlyOn ?: false)
            }
            if (currentlyOn == desiredOn) {
                onControlUpdated("ctrl_bt", desiredOn, null, null)
                return ExecutionReport(true, "Bluetooth is already ${if (desiredOn) "ON" else "OFF"}")
            }

            // Direct OS toggle works on API <= 32 (and privileged devices)
            @Suppress("DEPRECATION")
            val directAttempt = runCatching {
                if (desiredOn) adapter.enable() else adapter.disable()
            }.getOrDefault(false)

            if (directAttempt) {
                onControlUpdated("ctrl_bt", desiredOn, null, null)
                return ExecutionReport(true, "Bluetooth turned ${if (desiredOn) "ON" else "OFF"}")
            }

            // On Android 13+, AOSP blocks Adapter.enable()/disable() for non-system apps.
            // Use Nova's Accessibility Service hands-free Switch flipper if connected!
            val acc = NovaAccessibilityService.instance
            if (acc != null) {
                openBluetoothSettings()
                val toggled = acc.autoToggleSettingsSwitch(
                    keywords = listOf("use bluetooth", "bluetooth"),
                    desiredOn = desiredOn,
                    targetPkg = "com.android.settings"
                )
                val settledOn = pollBluetoothSettledState(adapter, desiredOn)
                onControlUpdated("ctrl_bt", settledOn, null, null)
                if (toggled && settledOn == desiredOn) {
                    return ExecutionReport(
                        true,
                        "Bluetooth turned ${if (settledOn) "ON" else "OFF"} automatically via Accessibility control"
                    )
                }
                openBluetoothSettings()
                return ExecutionReport(
                    false,
                    "Bluetooth still appears ${if (settledOn) "ON" else "OFF"} — I opened Bluetooth Settings so the switch can finish toggling."
                )
            }

            // Last-resort prompt if API 33+ and Accessibility Service isn't enabled yet
            if (desiredOn) {
                val reqEnable = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val opened = runCatching { context.startActivity(reqEnable) }.isSuccess
                if (!opened) openBluetoothSettings()
            } else {
                openBluetoothSettings()
            }
            return ExecutionReport(
                true,
                "Bluetooth prompt opened (enable Nova Accessibility Service in Settings for automatic hands-free toggle on Android 13+)"
            )
        } else {
            // No BLUETOOTH_CONNECT yet: use Accessibility if connected, or open Bluetooth settings
            val desiredOn = state != "off"
            val acc = NovaAccessibilityService.instance
            if (acc != null) {
                openBluetoothSettings()
                val toggled = acc.autoToggleSettingsSwitch(
                    keywords = listOf("use bluetooth", "bluetooth"),
                    desiredOn = desiredOn,
                    targetPkg = "com.android.settings"
                )
                if (toggled) {
                    onControlUpdated("ctrl_bt", desiredOn, null, null)
                    return ExecutionReport(true, "Bluetooth turned ${if (desiredOn) "ON" else "OFF"} via Accessibility control")
                }
            }
            openBluetoothSettings()
            return ExecutionReport(
                true,
                "Opened Bluetooth Settings — grant Nearby Devices / Accessibility access for one-step toggling."
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun pollBluetoothSettledState(
        adapter: BluetoothAdapter,
        desiredOn: Boolean,
        maxWaitMs: Long = 1600L
    ): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        var latest = runCatching { adapter.isEnabled }.getOrDefault(desiredOn)
        while (System.currentTimeMillis() < deadline) {
            val adapterState = runCatching { adapter.state }.getOrDefault(
                if (latest) BluetoothAdapter.STATE_ON else BluetoothAdapter.STATE_OFF
            )
            val isTransitioning = adapterState == BluetoothAdapter.STATE_TURNING_OFF ||
                adapterState == BluetoothAdapter.STATE_TURNING_ON
            latest = when (adapterState) {
                BluetoothAdapter.STATE_ON -> true
                BluetoothAdapter.STATE_OFF -> false
                else -> runCatching { adapter.isEnabled }.getOrDefault(latest)
            }
            if (!isTransitioning && latest == desiredOn) {
                return latest
            }
            delay(200L)
        }
        return runCatching { adapter.isEnabled }.getOrDefault(latest)
    }

    @SuppressLint("MissingPermission")
    private suspend fun executeWifiToggle(state: String): ExecutionReport {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val currentlyOn = runCatching { wm?.isWifiEnabled }.getOrNull()
        val desiredOn = when (state) {
            "on" -> true
            "off" -> false
            else -> !(currentlyOn ?: true)
        }
        if (currentlyOn == desiredOn) {
            onControlUpdated("ctrl_wifi", desiredOn, null, null)
            return ExecutionReport(true, "Wi-Fi is already ${if (desiredOn) "ON" else "OFF"}")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && wm != null) {
            @Suppress("DEPRECATION")
            val ok = runCatching { wm.setWifiEnabled(desiredOn) }.getOrDefault(false)
            if (ok) {
                onControlUpdated("ctrl_wifi", desiredOn, null, null)
                return ExecutionReport(true, "Wi-Fi turned ${if (desiredOn) "ON" else "OFF"}")
            }
        }
        val acc = NovaAccessibilityService.instance
        if (acc != null) {
            openWifiSettingsPanel()
            val toggled = acc.autoToggleSettingsSwitch(
                keywords = listOf("wi-fi", "wifi", "internet"),
                desiredOn = desiredOn
            )
            if (toggled) {
                val finalState = runCatching { wm?.isWifiEnabled }.getOrNull() ?: desiredOn
                onControlUpdated("ctrl_wifi", finalState, null, null)
                return ExecutionReport(true, "Wi-Fi turned ${if (finalState) "ON" else "OFF"} via Accessibility control")
            }
        }
        openWifiSettingsPanel()
        onControlUpdated("ctrl_wifi", desiredOn, null, null)
        return ExecutionReport(true, "Opened Wi-Fi quick panel")
    }

    private fun executeDndToggle(state: String): ExecutionReport {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return ExecutionReport(false, "NotificationManager unavailable")
        val currentlyDnd = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        val desiredOn = when (state) {
            "on" -> true
            "off" -> false
            else -> !currentlyDnd
        }
        if (!nm.isNotificationPolicyAccessGranted) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return ExecutionReport(
                false,
                "Grant Do Not Disturb access to Nova in the system screen that just opened."
            )
        }
        nm.setInterruptionFilter(
            if (desiredOn) NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        onControlUpdated("ctrl_dnd", desiredOn, null, null)
        return ExecutionReport(true, "Do Not Disturb turned ${if (desiredOn) "ON" else "OFF"}")
    }

    private fun setScreenBrightnessPercent(percent: Int): Boolean {
        if (!Settings.System.canWrite(context)) {
            runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
            return false
        }
        val value = (percent * 255 / 100).coerceIn(5, 255)
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        return Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
    }

    private suspend fun executeListManagement(listName: String, action: String, item: String): ExecutionReport {
        val cleanList = if (listName.contains("shop", ignoreCase = true)) "shopping" else "todo"
        val docPrefix = "list_${cleanList}_"
        return when (action.lowercase()) {
            "add" -> {
                if (item.isBlank()) return ExecutionReport(false, "Specify an item to add to your $cleanList list.")
                val id = "$docPrefix${System.currentTimeMillis()}"
                val listTitle = "${cleanList.replaceFirstChar { it.uppercase() }} List"
                val cleanItem = item.trim()
                val vec = embeddingRuntime?.embedDocument(listTitle, cleanItem)
                ftsHelper.upsertDocument(
                    docId = id,
                    category = "lists",
                    title = listTitle,
                    body = cleanItem,
                    uriOrRef = "list://$cleanList/$id",
                    embedding = vec
                )
                ExecutionReport(true, "Added '$cleanItem' to your $cleanList list.")
            }
            "remove" -> {
                val matches = ftsHelper.getDocumentsByCategory("lists", limit = 50)
                    .filter { it.docId.startsWith(docPrefix) && it.body.contains(item, ignoreCase = true) }
                matches.forEach { ftsHelper.deleteDocument(it.docId) }
                ExecutionReport(
                    true,
                    if (matches.isNotEmpty()) "Removed '${matches.first().body}' from your $cleanList list."
                    else "Could not find '$item' on your $cleanList list."
                )
            }
            "clear" -> {
                val items = ftsHelper.getDocumentsByCategory("lists", limit = 100)
                    .filter { it.docId.startsWith(docPrefix) }
                items.forEach { ftsHelper.deleteDocument(it.docId) }
                ExecutionReport(true, "Cleared ${items.size} item(s) from your $cleanList list.")
            }
            else -> {
                val items = ftsHelper.getDocumentsByCategory("lists", limit = 30)
                    .filter { it.docId.startsWith(docPrefix) }
                val summary = if (items.isEmpty()) {
                    "Your $cleanList list is currently empty."
                } else {
                    "Your $cleanList list (${items.size}): " + items.joinToString(", ") { it.body }
                }
                ExecutionReport(true, summary, ftsResults = items)
            }
        }
    }

    private fun executeReadRecentMessages(
        contactFilter: String,
        scope: String,
        action: String,
        markReadOnDelivery: Boolean,
        limit: Int
    ): ExecutionReport {
        val now = System.currentTimeMillis()
        val isCurrentScope = scope == "current" || scope == "current_important"
        val isCurrentOrToday = scope == "current_or_today"
        val reconciliation = NovaNotificationListenerService.reconcileActiveNotificationsWithArchive(
            ftsHelper = ftsHelper,
            nowMs = now,
            includeGeneralActive = isCurrentScope || isCurrentOrToday,
            context = context
        )
        val auditBefore = reconciliation.audit

        if (action == "mark_all_read" || action == "mark_read" ||
            scope == "mark_all_read" || scope == "mark_read"
        ) {
            val marked = ftsHelper.markAllNotificationsRead(now)
            val updatedAudit = ftsHelper.runBackgroundNotificationAudit(now)
            NovaNotificationListenerService.updatePublishedAudit(updatedAudit)
            val msg = if (marked > 0) {
                "Marked $marked notification${if (marked == 1) "" else "s"} as read."
            } else {
                "All saved notifications are already marked as read."
            }
            return ExecutionReport(true, msg)
        }

        val todayStart = ImportantNotificationFilter.startOfDayMillis(now)
        val retentionStart = now - LocalFtsIndexHelper.NOTIFICATION_RETENTION_MS
        val weekStart = now - LocalFtsIndexHelper.WEEK_MS
        val normalizedContact = contactFilter.takeIf {
            it.isNotBlank() && !it.equals("all", ignoreCase = true)
        }

        val effectiveSince = when (scope) {
            "unread", "archive", "all", "important" -> retentionStart
            "week" -> weekStart
            "24h" -> now - LocalFtsIndexHelper.DAY_MS
            else -> todayStart
        }

        val liveSortedByImportance = reconciliation.liveActiveRecords.sortedWith(
            compareByDescending<LocalFtsIndexHelper.ArchivedNotificationRecord> { it.importanceScore }
                .thenBy { it.isRead }
                .thenByDescending { it.receivedAt }
        )

        var usedLiveShade = false
        val rawCandidates = when (scope) {
            "current" -> {
                usedLiveShade = true
                reconciliation.liveActiveRecords
            }
            "current_important" -> {
                if (liveSortedByImportance.isNotEmpty()) {
                    usedLiveShade = true
                    liveSortedByImportance
                } else {
                    ftsHelper.queryArchivedNotifications(
                        sinceMs = now - LocalFtsIndexHelper.DAY_MS,
                        unreadOnly = false,
                        minTier = null,
                        prioritizeUnread = true,
                        limit = 30
                    ).ifEmpty {
                        ftsHelper.queryArchivedNotifications(
                            sinceMs = retentionStart,
                            unreadOnly = true,
                            minTier = ImportantNotificationFilter.NotificationImportanceTier.HIGH,
                            prioritizeUnread = true,
                            limit = 15
                        )
                    }.sortedWith(
                        compareByDescending<LocalFtsIndexHelper.ArchivedNotificationRecord> { it.importanceScore }
                            .thenBy { it.isRead }
                            .thenByDescending { it.receivedAt }
                    )
                }
            }
            "current_or_today" -> {
                if (reconciliation.liveActiveRecords.isNotEmpty()) {
                    usedLiveShade = true
                    reconciliation.liveActiveRecords
                } else {
                    ftsHelper.queryArchivedNotifications(
                        sinceMs = todayStart,
                        unreadOnly = false,
                        minTier = null,
                        prioritizeUnread = true,
                        limit = 30
                    )
                }
            }
            else -> {
                ftsHelper.queryArchivedNotifications(
                    sinceMs = effectiveSince,
                    unreadOnly = scope == "unread",
                    minTier = if (scope == "important") ImportantNotificationFilter.NotificationImportanceTier.HIGH else null,
                    prioritizeUnread = true,
                    limit = 30
                )
            }
        }

        val filtered = if (normalizedContact != null) {
            rawCandidates.filter { rec ->
                rec.title.contains(normalizedContact, ignoreCase = true) ||
                    rec.body.contains(normalizedContact, ignoreCase = true) ||
                    rec.appLabel.contains(normalizedContact, ignoreCase = true)
            }
        } else {
            rawCandidates
        }.take(limit)

        val carryOver = if ((scope == "today" || scope == "current" || scope == "current_or_today") && normalizedContact == null) {
            auditBefore.carryOverImportant.filter { carry ->
                filtered.none { it.alertId == carry.alertId }
            }
        } else {
            emptyList()
        }

        val listenerGranted = NovaNotificationListenerService.isPermissionGranted(context)
        if (filtered.isEmpty() && carryOver.isEmpty()) {
            val emptyMsg = when {
                reconciliation.serviceRebinding && (isCurrentScope || isCurrentOrToday) ->
                    "Notification Access is enabled, and I just asked Android to reconnect to your live notification shade. Try asking again in a second, or toggle Nova off and back on under Notification Access if needed."
                !listenerGranted && !reconciliation.liveChecked && auditBefore.totalSaved14Days == 0 ->
                    "Notification Access is disabled. Turn on Notification Access in Settings so I can read your current notifications, messages, missed calls, and reminders."
                isCurrentScope && !listenerGranted && !reconciliation.liveChecked ->
                    "Notification Access is disabled right now, so I can't inspect your live notification shade. Enable Notification Access in Settings to read current notifications."
                isCurrentScope && auditBefore.unreadToday > 0 ->
                    "You have no active notifications in your shade right now. You do have ${auditBefore.unreadToday} saved unread notification${if (auditBefore.unreadToday == 1) "" else "s"} from earlier today — ask for today's notifications to hear them."
                isCurrentScope && auditBefore.totalSaved14Days > 0 ->
                    "You have no active notifications in your shade right now. Your 2-week archive has ${auditBefore.totalSaved14Days} saved notification${if (auditBefore.totalSaved14Days == 1) "" else "s"} (${auditBefore.unreadTotal} unread)."
                isCurrentScope ->
                    "You have no current notifications in your shade right now."
                scope == "unread" ->
                    "You have no unread notifications right now."
                (scope == "today" || isCurrentOrToday) && auditBefore.totalSaved14Days > 0 ->
                    "You have no active notifications right now or saved from today. You do have ${auditBefore.totalSaved14Days} saved notification${if (auditBefore.totalSaved14Days == 1) "" else "s"} from the past two weeks (${auditBefore.unreadTotal} unread). Ask for this week's notifications if you want to hear them."
                scope == "today" || isCurrentOrToday ->
                    "You have no active notifications right now and no saved notifications from today."
                else ->
                    "You have no saved notifications in that timeframe."
            }
            return ExecutionReport(true, emptyMsg)
        }

        val toDoc: (LocalFtsIndexHelper.ArchivedNotificationRecord) -> LocalIndexDocument = { rec ->
            val timeStr = ImportantNotificationFilter.formatRelativeTimestamp(rec.receivedAt, now)
            val statusStr = if (rec.isRead) "Already read" else "Unread"
            val prioStr = if (rec.importanceTier != ImportantNotificationFilter.NotificationImportanceTier.NORMAL) {
                " • ${rec.importanceTier.label}"
            } else ""
            LocalIndexDocument(
                docId = rec.alertId,
                category = "messages",
                title = "[${rec.notifType.label}] ${rec.appLabel} • ${rec.title}",
                body = rec.body.ifEmpty { rec.title } + " ($statusStr • $timeStr$prioStr)",
                uriOrRef = "notif://${rec.packageName}/${rec.alertId}"
            )
        }

        val cleanSnippet: (String) -> String = { raw ->
            val collapsed = raw
                .replace("\r\n", " ")
                .replace("\n", " ")
                .replace(Regex("\\s+"), " ")
                .replace(Regex("(?<!\\.)\\.\\.(?!\\.)"), ".")
                .trim()
            if (collapsed.length <= 180) {
                collapsed
            } else {
                val cut = collapsed.take(180).substringBeforeLast(' ', collapsed.take(180)).trimEnd('.', ',', ';', ':')
                "$cut..."
            }
        }

        val ensureSingleEndPunctuation: (String) -> String = { raw ->
            val trimmed = raw
                .replace(Regex("(?<!\\.)\\.\\.(?!\\.)"), ".")
                .replace("?.", "?")
                .replace("!.", "!")
                .trim()
            if (trimmed.isEmpty()) {
                ""
            } else if (trimmed.last() in setOf('.', '?', '!')) {
                trimmed
            } else {
                "$trimmed."
            }
        }

        val formatRecordForSpeech: (LocalFtsIndexHelper.ArchivedNotificationRecord) -> String = { rec ->
            val timeStr = ImportantNotificationFilter.formatRelativeTimestamp(rec.receivedAt, now)
            val cleanTitle = rec.title.replace(Regex("\\s+"), " ").trim()
            val cleanBody = cleanSnippet(rec.body)
            val metaParts = buildList {
                when (rec.importanceTier) {
                    ImportantNotificationFilter.NotificationImportanceTier.CRITICAL ->
                        if (!cleanTitle.contains("urgent", ignoreCase = true)) add("Urgent")
                    ImportantNotificationFilter.NotificationImportanceTier.HIGH ->
                        if (!cleanTitle.contains("important", ignoreCase = true)) add("Important")
                    ImportantNotificationFilter.NotificationImportanceTier.NORMAL -> Unit
                }
                if (rec.isRead) add("Already read")
                if (timeStr.isNotBlank()) add(timeStr)
            }
            val meta = if (metaParts.isNotEmpty()) " (${metaParts.joinToString(", ")})" else ""
            val titleDuplicatesApp = cleanTitle.isBlank() || cleanTitle.equals(rec.appLabel, ignoreCase = true)
            val bodyDuplicatesTitle = cleanBody.isBlank() || cleanBody.equals(cleanTitle, ignoreCase = true)
            val headline = when (rec.notifType) {
                ImportantNotificationFilter.ImportantNotificationType.MISSED_CALL ->
                    "Missed call from ${cleanTitle.ifBlank { rec.appLabel }}"
                ImportantNotificationFilter.ImportantNotificationType.REMINDER ->
                    "Reminder — ${cleanTitle.ifBlank { rec.appLabel }}"
                ImportantNotificationFilter.ImportantNotificationType.MESSAGE,
                ImportantNotificationFilter.ImportantNotificationType.GENERAL ->
                    if (titleDuplicatesApp) rec.appLabel else "${rec.appLabel} — $cleanTitle"
            }
            val line = if (bodyDuplicatesTitle) {
                "$headline$meta"
            } else {
                "$headline$meta: $cleanBody"
            }
            ensureSingleEndPunctuation(line)
        }

        if (filtered.isEmpty() && carryOver.isNotEmpty()) {
            val carryDocs = carryOver.map(toDoc)
            ftsHelper.markNotificationsReminded(carryOver.map { it.alertId }, now)
            val updatedAudit = ftsHelper.runBackgroundNotificationAudit(now)
            NovaNotificationListenerService.updatePublishedAudit(updatedAudit)

            val emptyPrefix = if (isCurrentScope) {
                "You have no active notifications in your shade right now."
            } else {
                "You have no new notifications from today."
            }
            val summary = if (carryOver.size == 1) {
                "$emptyPrefix\n\nReminder of an earlier unread important alert:\n${formatRecordForSpeech(carryOver.first())}\n\nWould you like me to open it?"
            } else {
                val numberedCarry = carryOver.mapIndexed { idx, rec ->
                    "${idx + 1}. ${formatRecordForSpeech(rec)}"
                }.joinToString("\n")
                "$emptyPrefix\n\nYou have ${carryOver.size} earlier unread important notifications:\n\n$numberedCarry\n\nWhich one would you like to open? Say a number or app name."
            }
            return ExecutionReport(true, summary, ftsResults = carryDocs)
        }

        val unreadInList = filtered.count { !it.isRead }
        val readInList = filtered.count { it.isRead }
        val header = when {
            scope == "current_important" && usedLiveShade && filtered.size == 1 ->
                "Your most important current notification right now (out of ${reconciliation.liveActiveRecords.size} active):"
            scope == "current_important" && usedLiveShade ->
                "Your top ${filtered.size} most important current notifications right now:"
            scope == "current_important" && filtered.size == 1 ->
                "You have no active notifications in your shade right now, so here is your most important recent notification:"
            scope == "current_important" ->
                "Your top ${filtered.size} most important recent notifications:"
            usedLiveShade -> {
                val diffParts = buildList {
                    if (reconciliation.newlySavedCount > 0) add("${reconciliation.newlySavedCount} new")
                    if (reconciliation.alreadySavedUnreadCount > 0) add("${reconciliation.alreadySavedUnreadCount} already saved unread")
                    if (reconciliation.alreadyReadCount > 0) add("${reconciliation.alreadyReadCount} already read")
                }
                val diffSummary = if (diffParts.isNotEmpty()) " (${diffParts.joinToString(", ")})" else ""
                "Right now you have ${filtered.size} current notification${if (filtered.size == 1) "" else "s"}$diffSummary:"
            }
            scope == "today" || isCurrentOrToday -> when {
                unreadInList > 0 && readInList > 0 ->
                    "Today you have $unreadInList unread and $readInList already read notification${if (filtered.size == 1) "" else "s"}:"
                unreadInList > 0 ->
                    "Today's $unreadInList unread notification${if (unreadInList == 1) "" else "s"}:"
                readInList == 1 ->
                    "Today's notification was already read:"
                else ->
                    "All $readInList of today's notifications were already read:"
            }
            scope == "unread" -> "${filtered.size} unread notification${if (filtered.size == 1) "" else "s"}:"
            scope == "week" -> "Latest ${filtered.size} notification${if (filtered.size == 1) "" else "s"} from this week ($unreadInList unread, $readInList read):"
            scope == "24h" -> "Last 24 hours (${filtered.size} notifications, $unreadInList unread, $readInList read):"
            else -> "Latest ${filtered.size} saved notification${if (filtered.size == 1) "" else "s"} ($unreadInList unread, $readInList read):"
        }

        val bodyBlock = if (filtered.size == 1) {
            formatRecordForSpeech(filtered.first())
        } else {
            filtered.mapIndexed { idx, rec ->
                "${idx + 1}. ${formatRecordForSpeech(rec)}"
            }.joinToString("\n")
        }

        val carryOverNote = if (carryOver.isNotEmpty()) {
            ftsHelper.markNotificationsReminded(carryOver.map { it.alertId }, now)
            val top = carryOver.first()
            val whenLabel = ImportantNotificationFilter.formatRelativeTimestamp(top.receivedAt, now)
            "Reminder of an earlier unread important alert: ${top.notifType.label} from ${top.title} ($whenLabel)."
        } else ""

        if (markReadOnDelivery) {
            val newlyReadIds = filtered.filter { !it.isRead }.map { it.alertId }
            if (newlyReadIds.isNotEmpty()) {
                ftsHelper.markNotificationsRead(newlyReadIds, now)
            }
        }
        val updatedAudit = ftsHelper.runBackgroundNotificationAudit(now)
        NovaNotificationListenerService.updatePublishedAudit(updatedAudit)

        val docs = (filtered + carryOver).distinctBy { it.alertId }.map(toDoc)
        val sections = buildList {
            add(header)
            add(bodyBlock)
            if (carryOverNote.isNotBlank()) add(carryOverNote)
            if (filtered.size > 1) add("Which one would you like to open? Say a number or app name.")
        }
        val summary = sections.joinToString("\n\n").trim()
        return ExecutionReport(true, summary, ftsResults = docs)
    }

    @SuppressLint("MissingPermission")
    private suspend fun buildDeviceBriefing(topic: String, query: String = ""): String {
        com.example.engine.AlexaUtilityEngine.executeUtility(topic, query, context)?.let { return it }

        val lowerTopic = topic.lowercase()
        if (lowerTopic in setOf("self_status", "sensors", "thermal_sensor", "light_sensor", "motion_sensor")) {
            val sensorSnap = com.example.engine.DeviceSensorTelemetry.readSnapshot(context)
            return if (lowerTopic == "self_status") {
                val activeLlm = LlmModelManager(context).getActiveModelFile(ModelSlot.LLM)
                com.example.engine.DeviceSensorTelemetry.formatFirstPersonStatus(
                    snapshot = sensorSnap,
                    hardware = readLiveHardwareSnapshot(),
                    modelLoaded = activeLlm != null,
                    modelName = activeLlm?.name
                )
            } else {
                com.example.engine.DeviceSensorTelemetry.formatSensorFocusReply(lowerTopic, sensorSnap)
            }
        }

        val nowMs = System.currentTimeMillis()
        val now = Date(nowMs)
        val timeStr = SimpleDateFormat("h:mm a, EEEE MMM d", Locale.getDefault()).format(now)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val batteryStr = if (pct >= 0) "$pct%${if (isCharging) " (Charging)" else ""}" else "unavailable"

        return when (lowerTopic) {
            "battery" -> "Battery is at $batteryStr."
            "connectivity" -> {
                val snap = readLiveHardwareSnapshot()
                "Bluetooth is ${if (snap.bluetoothOn) "on" else "off"}, Wi-Fi is ${if (snap.wifiOn) "on" else "off"}, Do Not Disturb is ${if (snap.dndOn) "on" else "off"}, and media volume is ${snap.volumePercent}%."
            }
            else -> {
                val audit = NovaNotificationListenerService.syncActiveNotificationsToArchive(
                    ftsHelper = ftsHelper,
                    nowMs = nowMs,
                    context = context
                )

                ftsHelper.syncCalendarEventsWithEmbeddings(context, embeddingRuntime)
                val upcomingEvents = ftsHelper.queryDocumentsByTimeRange(
                    category = "calendar",
                    sinceMs = nowMs - 15L * 60_000L,
                    untilMs = nowMs + LocalFtsIndexHelper.WEEK_MS,
                    ascending = true,
                    limit = 5
                )
                val recent24hEvents = ftsHelper.queryDocumentsByTimeRange(
                    category = "calendar",
                    sinceMs = nowMs - LocalFtsIndexHelper.DAY_MS,
                    untilMs = nowMs - 15L * 60_000L,
                    ascending = false,
                    limit = 3
                )
                val recentWeekEvents = ftsHelper.queryDocumentsByTimeRange(
                    category = "calendar",
                    sinceMs = nowMs - LocalFtsIndexHelper.WEEK_MS,
                    untilMs = nowMs - 15L * 60_000L,
                    ascending = false,
                    limit = 5
                )
                val listCount = ftsHelper.countDocuments("lists")

                val parts = mutableListOf<String>()
                val defaultOpening = when (topic.lowercase()) {
                    "evening", "tonight" -> "Good evening! Here's tonight's briefing. It's $timeStr. Battery is $batteryStr."
                    "afternoon" -> "Good afternoon! Here's your afternoon briefing. It's $timeStr. Battery is $batteryStr."
                    else -> "Good day! It's $timeStr. Battery is $batteryStr."
                }
                val opening = agenticMemoryEngine?.buildBriefingOpening(timeStr, batteryStr, nowMs)
                    ?: defaultOpening
                parts.add(opening)

                // Last 24h vs Week notification summary depending on activity & importance
                val recent24hUnreadHighlights = if (audit.last24hUnread > 0) {
                    ftsHelper.queryArchivedNotifications(
                        sinceMs = nowMs - LocalFtsIndexHelper.DAY_MS,
                        unreadOnly = true,
                        prioritizeUnread = true,
                        limit = 2
                    )
                } else emptyList()

                if (audit.last24hTotal > 0 && topic.lowercase() != "weekly") {
                    val read24h = audit.last24hTotal - audit.last24hUnread
                    val highlightStr = if (recent24hUnreadHighlights.isNotEmpty()) {
                        " Priority latest: " + recent24hUnreadHighlights.joinToString("; ") { rec ->
                            "${rec.notifType.label} from ${rec.title} (${rec.appLabel})"
                        } + "."
                    } else ""
                    parts.add(
                        "In the last 24 hours: ${audit.last24hTotal} important notification${if (audit.last24hTotal == 1) "" else "s"} " +
                            "(${audit.last24hUnread} unread, $read24h already read).$highlightStr"
                    )
                    if (audit.weekTotal > audit.last24hTotal && audit.weekHighOrCritical > 0) {
                        parts.add(
                            "Past 7 days total: ${audit.weekTotal} alerts (${audit.weekHighOrCritical} high-priority, ${audit.weekUnread} unread)."
                        )
                    }
                } else {
                    val weekSummary = when {
                        audit.weekTotal > 0 -> {
                            "No new alerts in the last 24 hours. Over the past week: ${audit.weekTotal} important notification${if (audit.weekTotal == 1) "" else "s"} " +
                                "(${audit.weekHighOrCritical} high-priority, ${audit.missedCallsWeek} missed call${if (audit.missedCallsWeek == 1) "" else "s"}, " +
                                "${audit.remindersWeek} reminder${if (audit.remindersWeek == 1) "" else "s"}; ${audit.weekUnread} unread)."
                        }
                        audit.totalSaved14Days > 0 ->
                            "No alerts in the last 7 days (${audit.totalSaved14Days} older item${if (audit.totalSaved14Days == 1) "" else "s"} in your 2-week archive, ${audit.unreadTotal} unread)."
                        else ->
                            "No unread important notifications in the last 24 hours or past week."
                    }
                    parts.add(weekSummary)
                }

                // Proactively remind about important unread carry-over alerts from earlier (up to 14 days)
                if (audit.carryOverImportant.isNotEmpty()) {
                    ftsHelper.markNotificationsReminded(audit.carryOverImportant.map { it.alertId }, nowMs)
                    val topCarry = audit.carryOverImportant.first()
                    val relTime = ImportantNotificationFilter.formatRelativeTimestamp(topCarry.receivedAt, nowMs)
                    val extraCount = audit.carryOverImportant.size - 1
                    val extraNote = if (extraCount > 0) " (+$extraCount more important unread)" else ""
                    parts.add(
                        "Important reminder from $relTime: ${topCarry.notifType.label} from ${topCarry.title} — ${topCarry.body.take(80)}$extraNote."
                    )
                }

                // Events & reminders summary across next/past 24h or week
                val calPart = when {
                    upcomingEvents.isNotEmpty() -> {
                        val next = upcomingEvents.first()
                        val more = if (upcomingEvents.size > 1) " (${upcomingEvents.size} events upcoming this week)" else ""
                        "Next event: ${next.title} (${next.body})$more."
                    }
                    recent24hEvents.isNotEmpty() -> {
                        val ev = recent24hEvents.first()
                        "Recent event in the last 24 hours: ${ev.title} (${ev.body})."
                    }
                    recentWeekEvents.isNotEmpty() -> {
                        "Events this past week: ${recentWeekEvents.size} (latest: ${recentWeekEvents.first().title})."
                    }
                    else -> "No upcoming calendar events."
                }
                parts.add(calPart)

                if (listCount > 0) {
                    parts.add("Saved list items: $listCount.")
                }
                val agenticInsights = agenticMemoryEngine?.buildAgenticBriefingInsights(nowMs).orEmpty()
                if (agenticInsights.isNotBlank()) {
                    parts.add(agenticInsights)
                }
                parts.joinToString(" ")
            }
        }
    }

    private suspend fun executeManageAgenticMemory(
        action: String,
        target: String,
        detail: String
    ): ExecutionReport {
        val engine = agenticMemoryEngine
        val nowMs = System.currentTimeMillis()
        return when (action.lowercase().trim()) {
            "inspect", "summary", "list" -> {
                val msg = engine?.formatLearnedSummary(nowMs)
                    ?: "Agentic memory is active, though no habits are recorded yet."
                ExecutionReport(true, msg)
            }

            "teach_process", "learn_process" -> {
                val stepList = detail.split(Regex("\\s*(?:;|->|\\band then\\b|\\bthen\\b)\\s*|\\s*,\\s*", RegexOption.IGNORE_CASE))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (engine == null) {
                    ExecutionReport(false, "Agentic memory engine is unavailable.")
                } else {
                    val saved = engine.teachExplicitProcess(
                        name = target.ifBlank { "Custom Workflow" },
                        steps = stepList,
                        nowMs = nowMs
                    )
                    ExecutionReport(
                        true,
                        "Learned workflow '${saved.name}' (${saved.timeBucket.label}): ${saved.steps.joinToString(", then ")}. Just ask me to run your ${saved.name} process anytime."
                    )
                }
            }

            "run_process" -> {
                val matched = ftsHelper.findLearnedProcessByName(target)
                    ?: if (target.isBlank()) engine?.findTopSuggestedProcessForBriefing(nowMs) else null
                if (matched == null) {
                    ExecutionReport(false, "I couldn't find a learned workflow matching '$target'.")
                } else {
                    ftsHelper.recordLearnedProcessExecution(matched.processId, nowMs)
                    val installedApps = discoverInstalledApps()
                    val stepSummaries = mutableListOf<String>()
                    matched.steps.forEach { stepCmd ->
                        val stepNorm = InputNormalizer.normalize(stepCmd)
                        val planned = MultiStageActionPlanner.plan(
                            normalized = stepNorm,
                            installedApps = installedApps,
                            hasLaunchIntent = { pkg -> hasLaunchIntent(pkg) }
                        )
                        if (planned != null) {
                            planned.stages.filter { it.toolCall.safetyTier != SafetyTier.RED }.forEach { st ->
                                val r = execute(st.toolCall)
                                stepSummaries.add(r.summary)
                            }
                        } else {
                            val fast = RegexFastPathEngine.match(stepNorm.cleanedFull, installedApps)
                            if (fast != null && fast.toolCall.safetyTier != SafetyTier.RED) {
                                val r = execute(fast.toolCall)
                                stepSummaries.add(r.summary)
                            } else {
                                stepSummaries.add("Prepared '$stepCmd'")
                            }
                        }
                    }
                    val joined = stepSummaries.joinToString(" • ").ifBlank {
                        "Executed ${matched.steps.size} steps."
                    }
                    ExecutionReport(true, "Ran '${matched.name}': $joined")
                }
            }

            "set_preference" -> {
                if (engine == null || target.isBlank()) {
                    ExecutionReport(false, "Tell me which app or option you prefer to use.")
                } else {
                    val (label, categoryDesc) = engine.applyExplicitCorrectionOrPreference(
                        rawTarget = target,
                        sourceQuery = detail.ifBlank { target },
                        nowMs = nowMs
                    )
                    val msg = if (categoryDesc != null) {
                        "Got it — I'll remember that you prefer $label for $categoryDesc."
                    } else {
                        "Got it — I saved your preference to use $label."
                    }
                    ExecutionReport(true, msg)
                }
            }

            "set_personality" -> {
                val persona = AssistantPersonality.fromIdOrName(target)
                if (persona == null) {
                    ExecutionReport(
                        false,
                        "Available personality modes: Warm Companion, Sharp Executive, Witty Co-Pilot, or Calm Minimalist. Which one would you like?"
                    )
                } else {
                    engine?.applyPersonality(persona, nowMs)
                    val reply = when (persona) {
                        AssistantPersonality.WARM_COMPANION ->
                            "Personality switched to Warm Companion. Happy to help alongside you!"
                        AssistantPersonality.SHARP_EXECUTIVE ->
                            "Personality set to Sharp Executive. Concise, action-first mode active."
                        AssistantPersonality.WITTY_COPILOT ->
                            "Switched to Witty Co-Pilot — ready to get things done with a little flair."
                        AssistantPersonality.CALM_MINIMALIST ->
                            "Minimalist mode set. Brief, quiet responses active."
                    }
                    ExecutionReport(true, reply)
                }
            }

            "set_name" -> {
                val cleanName = engine?.applyUserName(target, nowMs)
                    ?: target.trim().ifBlank { "Friend" }
                ExecutionReport(true, "Nice to meet you, $cleanName. I'll remember to greet you that way.")
            }

            "forget" -> {
                if (target.isBlank() || target.equals("all", ignoreCase = true) || target.equals("everything", ignoreCase = true)) {
                    ftsHelper.clearAllAgenticLearning()
                    ExecutionReport(true, "Cleared all learned preferences, workflows, and interaction episodes.")
                } else {
                    val removed = ftsHelper.deleteAgenticMemoryByKeyword(target)
                    if (removed > 0) {
                        ExecutionReport(true, "Forgot $removed learned item${if (removed == 1) "" else "s"} matching '$target'.")
                    } else {
                        ExecutionReport(false, "I didn't find any learned memory or process matching '$target'.")
                    }
                }
            }

            else -> ExecutionReport(false, "Unknown agentic memory action '$action'.")
        }
    }

    private suspend fun executeNamedRoutine(routineName: String): ExecutionReport {
        return when (routineName) {
            "morning" -> {
                val briefing = buildDeviceBriefing("morning")
                turnArtifacts["morning_briefing"] = briefing
                ExecutionReport(true, briefing)
            }
            "focus" -> {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val dndMsg = if (nm?.isNotificationPolicyAccessGranted == true) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    onControlUpdated("ctrl_dnd", true, null, null)
                    "Do Not Disturb is ON"
                } else {
                    "Grant DND access in Settings for silent focus"
                }
                launchSystemTimerIntent(1500, "Focus")
                onControlUpdated("ctrl_timer", true, 25f, "Focus")
                ExecutionReport(true, "Focus mode started: 25-minute timer running. $dndMsg.")
            }
            "bedtime" -> {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val dndStatus = if (nm?.isNotificationPolicyAccessGranted == true) {
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    onControlUpdated("ctrl_dnd", true, null, null)
                    "Do Not Disturb enabled"
                } else {
                    "Quiet mode ready"
                }
                setVolumePercent(20)
                onControlUpdated("ctrl_media", true, 20f, null)
                ftsHelper.syncCalendarEventsWithEmbeddings(context, embeddingRuntime)
                val nextEvent = ftsHelper.getDocumentsByCategory("calendar", limit = 1).firstOrNull()
                val calNote = if (nextEvent != null) " First upcoming event: ${nextEvent.title}." else ""
                ExecutionReport(true, "Bedtime routine complete: $dndStatus and media volume lowered to 20%.$calNote")
            }
            else -> {
                val syncedApps = AppCapabilityRegistry.syncInstalledAppsWithCapabilities(context, ftsHelper, embeddingRuntime)
                val syncedContacts = ftsHelper.syncContacts(context).coerceAtLeast(0)
                val syncedEvents = ftsHelper.syncCalendarEventsWithEmbeddings(context, embeddingRuntime).coerceAtLeast(0)
                ExecutionReport(
                    true,
                    "Updated your phone info: $syncedApps apps, $syncedContacts contacts, and $syncedEvents calendar events ready."
                )
            }
        }
    }

    private suspend fun executeNamedOrRecordedRoutine(routineName: String): ExecutionReport {
        val tempStore = if (routineStore == null) RecordedRoutineStore(context) else null
        val activeStore = routineStore ?: tempStore!!
        val matched = activeStore.findByName(routineName)
        tempStore?.close()
        if (matched != null) {
            return executeRecordedRoutine(matched.name, null, null)
        }
        return executeNamedRoutine(routineName.lowercase())
    }

    suspend fun executeRecordedRoutine(
        routineName: String,
        scopeApp: String? = null,
        overrideDelayMs: Long? = null
    ): ExecutionReport {
        if (routineName.isBlank()) {
            return ExecutionReport(false, "Tell me which recorded routine you'd like me to run.")
        }
        val tempStore = if (routineStore == null) RecordedRoutineStore(context) else null
        val activeStore = routineStore ?: tempStore!!
        val matched = activeStore.findById(routineName) ?: activeStore.findByName(routineName, scopeApp)
        val availableNames = if (matched == null) activeStore.getAll().map { it.name }.take(5) else emptyList()
        tempStore?.close()

        if (matched == null) {
            if (routineName.lowercase() in setOf("morning", "focus", "bedtime", "sync")) {
                return executeNamedRoutine(routineName.lowercase())
            }
            val hint = if (availableNames.isNotEmpty()) {
                " Saved routines: ${availableNames.joinToString(", ")}."
            } else {
                " Say 'show floating routine control' or open Routine Studio in the Routines tab to record one."
            }
            return ExecutionReport(false, "I couldn't find a routine named '$routineName'.$hint")
        }

        RoutineRecorderManager.setPlayingState(true, "Running '${matched.name}'...")
        val engine = RoutineExecutionEngine(context) { step ->
            val hwReport = execute(
                ToolCallRequest(
                    toolName = "set_hardware_state",
                    args = mapOf(
                        "feature" to step.targetLabel.lowercase().ifBlank { "flashlight" },
                        "state" to step.textValue.lowercase().ifBlank { "on" }
                    )
                )
            )
            hwReport.success to hwReport.summary
        }
        val result = engine.executeRoutine(matched, overrideDelayMs)
        RoutineRecorderManager.setPlayingState(false, result.summary)
        return ExecutionReport(result.success, result.summary)
    }

    private suspend fun executeRoutineRecorderControl(
        action: String,
        routineName: String,
        targetScope: String
    ): ExecutionReport {
        val tempStore = if (routineStore == null) RecordedRoutineStore(context) else null
        val activeStore = routineStore ?: tempStore!!

        when (action) {
            "list" -> {
                val recorded = activeStore.getAll()
                tempStore?.close()
                val learned = ftsHelper.getLearnedProcesses(limit = 10)

                val docs = buildList {
                    builtInRoutines.forEach { bi ->
                        add(
                            LocalIndexDocument(
                                docId = "builtin_${bi.key}",
                                category = "routines",
                                title = bi.name,
                                body = bi.summary,
                                uriOrRef = "nova://builtin_routine/${bi.key}"
                            )
                        )
                    }
                    recorded.forEach { rt ->
                        add(
                            LocalIndexDocument(
                                docId = "rt_${rt.id}",
                                category = "routines",
                                title = rt.name,
                                body = rt.summaryLine(),
                                uriOrRef = "nova://routine/${rt.id}"
                            )
                        )
                    }
                }

                val sections = buildList {
                    add(
                        "Built-in Routines (${builtInRoutines.size}):\n" +
                            builtInRoutines.mapIndexed { idx, bi ->
                                "${idx + 1}. ${bi.name} (say \"${bi.triggerPhrase}\") — ${bi.summary}"
                            }.joinToString("\n")
                    )
                    if (recorded.isNotEmpty()) {
                        add(
                            "Your Recorded Routines (${recorded.size}):\n" +
                                recorded.mapIndexed { idx, rt ->
                                    "${idx + 1}. ${rt.name} (${rt.summaryLine().replace(" • ", ", ")})"
                                }.joinToString("\n")
                        )
                    } else {
                        add("Your Recorded Routines (0): None yet. Say \"show floating routine control\" or tap + New Routine in the Routines tab to record one.")
                    }
                    if (learned.isNotEmpty()) {
                        add(
                            "Learned Workflows (${learned.size}):\n" +
                                learned.mapIndexed { idx, proc ->
                                    "${idx + 1}. ${proc.name} (${proc.steps.size} step${if (proc.steps.size == 1) "" else "s"})"
                                }.joinToString("\n")
                        )
                    }
                    add("Ask \"show steps in all routines\" or \"what are the steps in the Morning routine\" to see the step-by-step breakdown.")
                }
                return ExecutionReport(true, sections.joinToString("\n\n"), ftsResults = docs)
            }

            "list_steps", "steps", "inspect" -> {
                val recorded = activeStore.getAll()
                tempStore?.close()
                val learned = ftsHelper.getLearnedProcesses(limit = 10)
                val target = routineName.trim().lowercase()

                if (target.isNotBlank()) {
                    val matchedBuiltIn = builtInRoutines.firstOrNull { bi ->
                        bi.key == target ||
                            bi.name.lowercase().contains(target) ||
                            target.contains(bi.key) ||
                            bi.triggerPhrase.lowercase().contains(target)
                    }
                    if (matchedBuiltIn != null) {
                        val stepLines = matchedBuiltIn.steps.mapIndexed { idx, s -> "${idx + 1}. $s" }.joinToString("\n")
                        return ExecutionReport(
                            true,
                            "${matchedBuiltIn.name} (Built-in, trigger: \"${matchedBuiltIn.triggerPhrase}\"):\n\n$stepLines"
                        )
                    }
                    val matchedRecorded = recorded.firstOrNull { rt ->
                        rt.name.lowercase().contains(target) || target.contains(rt.name.lowercase())
                    }
                    if (matchedRecorded != null) {
                        val stepLines = formatRecordedRoutineSteps(matchedRecorded)
                        return ExecutionReport(
                            true,
                            "${matchedRecorded.name} (${matchedRecorded.summaryLine().replace(" • ", ", ")}):\n\n$stepLines"
                        )
                    }
                    val matchedLearned = learned.firstOrNull { proc ->
                        proc.name.lowercase().contains(target) || target.contains(proc.name.lowercase())
                    }
                    if (matchedLearned != null) {
                        val stepLines = matchedLearned.steps.mapIndexed { idx, s -> "${idx + 1}. $s" }.joinToString("\n")
                        return ExecutionReport(
                            true,
                            "${matchedLearned.name} (Learned workflow):\n\n$stepLines"
                        )
                    }
                    val allNames = (builtInRoutines.map { it.name } + recorded.map { it.name } + learned.map { it.name })
                        .joinToString(", ")
                    return ExecutionReport(
                        false,
                        "I couldn't find a routine matching '$routineName'. Available routines: $allNames."
                    )
                }

                val sections = buildList {
                    add(
                        "Built-in Routines & Steps:\n\n" +
                            builtInRoutines.joinToString("\n\n") { bi ->
                                val lines = bi.steps.mapIndexed { idx, s -> "  ${idx + 1}. $s" }.joinToString("\n")
                                "${bi.name} (say \"${bi.triggerPhrase}\"):\n$lines"
                            }
                    )
                    if (recorded.isNotEmpty()) {
                        add(
                            "Your Recorded Routines & Steps:\n\n" +
                                recorded.joinToString("\n\n") { rt ->
                                    "${rt.name} (${rt.summaryLine().replace(" • ", ", ")}):\n${formatRecordedRoutineSteps(rt, indent = "  ")}"
                                }
                        )
                    }
                    if (learned.isNotEmpty()) {
                        add(
                            "Learned Workflows & Steps:\n\n" +
                                learned.joinToString("\n\n") { proc ->
                                    val lines = proc.steps.mapIndexed { idx, s -> "  ${idx + 1}. $s" }.joinToString("\n")
                                    "${proc.name}:\n$lines"
                                }
                        )
                    }
                }
                return ExecutionReport(true, sections.joinToString("\n\n"))
            }

            "hide_floating" -> {
                tempStore?.close()
                RoutineRecorderManager.hideFloatingControl()
                return ExecutionReport(true, "Floating routine control hidden.")
            }

            "stop" -> {
                val saved = RoutineRecorderManager.stopAndSaveRecording(
                    context = context,
                    routineName = routineName.ifBlank { null },
                    ftsHelper = ftsHelper,
                    embeddingRuntime = embeddingRuntime
                )
                tempStore?.close()
                return if (saved != null) {
                    ExecutionReport(
                        true,
                        "Saved routine '${saved.name}' with ${saved.steps.size} step${if (saved.steps.size == 1) "" else "s"} (${saved.summaryLine()})."
                    )
                } else {
                    ExecutionReport(false, "Stopped recording, but no taps, swipes, or conditions were captured yet.")
                }
            }

            else -> {
                tempStore?.close()
                val accReady = NovaAccessibilityService.instance != null ||
                    NovaAccessibilityService.isServiceEnabledInSystem(context)
                if (!accReady) {
                    return ExecutionReport(
                        false,
                        "Accessibility Service is required to show the floating routine overlay and capture taps/swipes. Enable Nova in Android Accessibility Settings first."
                    )
                }

                val resolvedApp = if (targetScope.isNotBlank() && !targetScope.equals("global", ignoreCase = true)) {
                    AppCapabilityRegistry.findBestMatch(context, targetScope)
                } else null

                val scope = if (resolvedApp != null) RoutineScope.PER_APP else RoutineScope.GLOBAL
                val autoStart = action == "start"
                if (autoStart) {
                    RoutineRecorderManager.startRecording(
                        scope = scope,
                        targetAppName = resolvedApp?.appName ?: targetScope.takeIf { scope == RoutineScope.PER_APP },
                        targetPackage = resolvedApp?.packageName,
                        draftName = routineName
                    )
                    if (resolvedApp != null) {
                        context.packageManager.getLaunchIntentForPackage(resolvedApp.packageName)?.apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }?.let { runCatching { context.startActivity(it) } }
                    }
                    return ExecutionReport(
                        true,
                        if (resolvedApp != null) {
                            "Opened ${resolvedApp.appName} with the floating routine recorder active. Tap Save on the floating bar when done."
                        } else {
                            "Global routine recording started with floating control. Perform taps/swipes or use + Tap / + Swipe / + IF on the floating bar."
                        }
                    )
                } else {
                    RoutineRecorderManager.showFloatingControl(
                        scope = scope,
                        targetAppName = resolvedApp?.appName,
                        targetPackage = resolvedApp?.packageName,
                        autoStartRecording = false
                    )
                    return ExecutionReport(
                        true,
                        "Floating routine control is visible. Use ▶ Start to run a routine or ● Record to capture taps, swipes, and IF conditions."
                    )
                }
            }
        }
    }

    private data class BuiltInRoutineSpec(
        val key: String,
        val name: String,
        val triggerPhrase: String,
        val summary: String,
        val steps: List<String>
    )

    private val builtInRoutines: List<BuiltInRoutineSpec> = listOf(
        BuiltInRoutineSpec(
            key = "morning",
            name = "Morning Routine",
            triggerPhrase = "Good morning",
            summary = "Time, battery, calendar schedule, notifications, and your morning briefing.",
            steps = listOf(
                "Check current time, date, and battery level.",
                "Sync live notifications with your local notification archive.",
                "Sync and read upcoming calendar events.",
                "Review to-do items and unread important alerts.",
                "Deliver your personalized morning briefing."
            )
        ),
        BuiltInRoutineSpec(
            key = "focus",
            name = "Focus Routine",
            triggerPhrase = "Start focus mode",
            summary = "Turns on Do Not Disturb (priority only) and starts a 25-minute focus timer.",
            steps = listOf(
                "Enable Do Not Disturb (Priority mode) if DND permission is granted.",
                "Launch a 25-minute system countdown timer labeled \"Focus\"."
            )
        ),
        BuiltInRoutineSpec(
            key = "bedtime",
            name = "Bedtime Routine",
            triggerPhrase = "Good night",
            summary = "Enables Do Not Disturb, lowers media volume to 20%, and checks tomorrow's first event.",
            steps = listOf(
                "Enable Do Not Disturb (Priority mode) if DND permission is granted.",
                "Lower device media volume to 20%.",
                "Sync calendar events and announce your next upcoming event."
            )
        ),
        BuiltInRoutineSpec(
            key = "sync",
            name = "Phone Info Sync Routine",
            triggerPhrase = "Sync my phone",
            summary = "Refreshes installed apps, contacts, and calendar events into Nova's local search index.",
            steps = listOf(
                "Scan installed apps and refresh their capabilities and aliases.",
                "Sync device contacts into local search memory.",
                "Sync calendar events and refresh local embeddings."
            )
        )
    )

    private fun formatRecordedRoutineSteps(routine: RecordedRoutine, indent: String = ""): String {
        if (routine.steps.isEmpty()) return "${indent}No steps saved in this routine yet."
        return routine.steps.mapIndexed { idx, step ->
            val desc = when (step.actionType) {
                RoutineActionType.TAP -> {
                    val label = step.targetLabel.ifBlank { step.title }
                    "Tap \"$label\""
                }
                RoutineActionType.SWIPE -> step.title.ifBlank { "Swipe on screen" }
                RoutineActionType.TYPE_TEXT -> {
                    val field = step.targetLabel.ifBlank { step.title }
                    "Type \"${step.textValue}\" into $field"
                }
                RoutineActionType.LAUNCH_APP -> "Open app ${step.targetLabel.ifBlank { step.title }}"
                RoutineActionType.HARDWARE_TOGGLE -> {
                    val feat = step.targetLabel.ifBlank { step.title }
                    val state = step.textValue.ifBlank { "toggle" }
                    "Set $feat to $state"
                }
                RoutineActionType.GLOBAL_ACTION -> "System action: ${step.title}"
                RoutineActionType.IF_CONDITION -> step.condition?.describe() ?: step.title
            }
            val condSuffix = if (step.actionType != RoutineActionType.IF_CONDITION && step.condition != null) {
                " [${step.condition.describe()}]"
            } else {
                ""
            }
            val delaySuffix = if (step.delayBeforeMs >= 1000L) {
                " (wait ${step.delayBeforeMs / 1000L}s first)"
            } else {
                ""
            }
            "$indent${idx + 1}. $desc$condSuffix$delaySuffix"
        }.joinToString("\n")
    }

    internal fun buildRoutineKnowledgeSummary(): String {
        val tempStore = if (routineStore == null) RecordedRoutineStore(context) else null
        val activeStore = routineStore ?: tempStore!!
        val recorded = runCatching { activeStore.getAll() }.getOrDefault(emptyList())
        tempStore?.close()
        val learned = runCatching { ftsHelper.getLearnedProcesses(limit = 8) }.getOrDefault(emptyList())

        return buildString {
            appendLine("[NOVA AVAILABLE ROUTINES & STEP-BY-STEP KNOWLEDGE]")
            appendLine("Built-in routines:")
            builtInRoutines.forEach { bi ->
                appendLine("- ${bi.name} (trigger: \"${bi.triggerPhrase}\"): ${bi.steps.joinToString(" -> ")}")
            }
            if (recorded.isNotEmpty()) {
                appendLine("User-recorded routines:")
                recorded.take(10).forEach { rt ->
                    val stepsInline = rt.steps.mapIndexed { i, s ->
                        val c = s.condition?.let { " [${it.describe()}]" }.orEmpty()
                        "${i + 1}) ${s.title.ifBlank { s.actionType.label }}$c"
                    }.joinToString(" -> ")
                    appendLine("- ${rt.name} (${rt.summaryLine()}): $stepsInline")
                }
            }
            if (learned.isNotEmpty()) {
                appendLine("Learned workflows:")
                learned.forEach { p ->
                    appendLine("- ${p.name}: ${p.steps.joinToString(" -> ")}")
                }
            }
        }.trim()
    }

    private fun readBrightnessPercent(): Int {
        return runCatching {
            val raw = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            ((raw * 100) / 255).coerceIn(5, 100)
        }.getOrDefault(50)
    }

    private fun resolveContactTarget(rawTarget: String): Pair<String, String> {
        val clean = rawTarget.trim()
        if (clean.isEmpty()) return "" to ""
        val looksLikeNumber = clean.count { it.isDigit() } >= 3 && !clean.any { it.isLetter() }
        if (looksLikeNumber) return clean to clean

        val liveMatch = queryDeviceContacts(clean).firstOrNull()
        if (liveMatch != null && liveMatch.body.isNotBlank()) {
            return liveMatch.body.trim() to liveMatch.title.ifBlank { clean }
        }
        val ftsMatch = ftsHelper.search(clean, "contacts", limit = 1).firstOrNull()
        if (ftsMatch != null && ftsMatch.body.isNotBlank()) {
            return ftsMatch.body.trim() to ftsMatch.title.ifBlank { clean }
        }
        return clean to clean
    }

    private fun dispatchChannelMessageIntent(target: String, body: String, channel: String): Boolean {
        val pm = context.packageManager
        val pkgHint = when (channel) {
            "whatsapp" -> listOf("com.whatsapp", "com.whatsapp.w4b")
            "messenger" -> listOf("com.facebook.orca")
            "telegram" -> listOf("org.telegram.messenger", "org.thunderdog.challegram")
            "viber" -> listOf("com.viber.voip")
            else -> emptyList()
        }
        for (pkg in pkgHint) {
            val direct = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(pkg)
                putExtra(Intent.EXTRA_TEXT, body)
                putExtra("jid", target)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (direct.resolveActivity(pm) != null && runCatching { context.startActivity(direct) }.isSuccess) {
                return true
            }
        }
        val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(target)}")).apply {
            putExtra("sms_body", body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(smsIntent) }.isSuccess
    }

    data class HardwareSnapshot(
        val bluetoothOn: Boolean,
        val wifiOn: Boolean,
        val dndOn: Boolean,
        val volumePercent: Int
    )

    @SuppressLint("MissingPermission")
    fun readLiveHardwareSnapshot(): HardwareSnapshot {
        val btAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val canReadBt = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        val btOn = if (canReadBt) runCatching { btAdapter?.isEnabled == true }.getOrDefault(false) else false

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiOn = runCatching { wm?.isWifiEnabled == true }.getOrDefault(false)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val dndOn = runCatching {
            nm != null && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        }.getOrDefault(false)

        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val volPct = if (am != null) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max).coerceIn(0, 100)
        } else 65

        return HardwareSnapshot(btOn, wifiOn, dndOn, volPct)
    }

    private fun dispatchRealMediaKey(cmd: String): Boolean {
        val keyCode = when (cmd.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        return runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
            true
        }.getOrDefault(false)
    }

    private fun queryDeviceContacts(query: String): List<LocalIndexDocument> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return emptyList()

        val results = mutableListOf<LocalIndexDocument>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%${query.trim()}%")
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                selection,
                args,
                null
            )?.use { c ->
                while (c.moveToNext() && results.size < 5) {
                    val cid = c.getString(0).orEmpty()
                    val name = c.getString(1).orEmpty()
                    val num = c.getString(2).orEmpty()
                    results.add(LocalIndexDocument("contact_$cid", "contacts", name, num, "tel:$num"))
                }
            }
        }
        return results
    }

    @Suppress("MissingPermission")
    private fun handleTelecomCallAction(action: String): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false to "Call control requires Android 9+."
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return false to "ANSWER_PHONE_CALLS permission not granted."
        }
        val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            ?: return false to "TelecomManager unavailable."
        return runCatching {
            if (action.equals("answer", ignoreCase = true)) {
                @Suppress("DEPRECATION")
                tm.acceptRingingCall()
                true to "Answered incoming call"
            } else {
                @Suppress("DEPRECATION")
                tm.endCall()
                true to "Declined/ended call"
            }
        }.getOrElse { false to (it.message ?: "Call action failed") }
    }

    private fun readUriSnippet(uriStr: String): String? {
        if (!uriStr.startsWith("content://")) return null
        return runCatching {
            val uri = Uri.parse(uriStr)
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                reader.readText().take(600).trim()
            }
        }.getOrNull()
    }

    private fun toggleTorch(enable: Boolean): Boolean {
        isTorchOnPlaceholder = enable
        return runCatching {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            cm.setTorchMode(cameraId, enable)
            true
        }.onFailure { Log.d(TAG, "Torch unavailable: ${it.message}") }
            .getOrDefault(false)
    }

    private fun openWifiSettingsPanel() {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Settings.Panel.ACTION_WIFI
        } else {
            Settings.ACTION_WIFI_SETTINGS
        }
        runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun openBluetoothSettings() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun setVolumePercent(percent: Int) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = (max * (percent / 100f)).toInt().coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }
    }

    private fun setAudioMute(mute: Boolean) {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                0
            )
        }
    }

    private fun launchSystemTimerIntent(seconds: Int, label: String): Boolean {
        return runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else false
        }.getOrDefault(false)
    }

    private fun launchSystemAlarmIntent(time24: String, label: String) {
        val parts = time24.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 7
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 30
        runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, h)
                putExtra(AlarmClock.EXTRA_MINUTES, m)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            }
        }
    }

    private suspend fun executeCreateCalendarEventOrReminder(
        title: String,
        startIso: String?,
        durationMin: Int
    ): ExecutionReport {
        val startMs = parseReminderTimeMillis(startIso)
        val endMs = startMs + durationMin * 60_000L
        val timeFmt = SimpleDateFormat("EEE h:mm a", Locale.US).format(Date(startMs))

        // 1. Direct CalendarContract write when Calendar read/write permissions & local calendar exist
        val hasReadCal = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED
        val hasWriteCal = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

        if (hasReadCal && hasWriteCal) {
            val savedToProvider = insertIntoCalendarProvider(title, startMs, endMs)
            if (savedToProvider) {
                indexSavedReminderInFts(title, startMs, timeFmt)
                return ExecutionReport(
                    true,
                    "Created and saved reminder '$title' for $timeFmt in your Calendar."
                )
            }
        }

        // 2. If Calendar app handles ACTION_INSERT AND Accessibility Service is active,
        // launch template and automatically tap Save / Done / Create without waiting for the user!
        val insertIntent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endMs)
            putExtra(CalendarContract.Events.HAS_ALARM, 1)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val acc = NovaAccessibilityService.instance
        if (acc != null && insertIntent.resolveActivity(context.packageManager) != null) {
            val launched = runCatching { context.startActivity(insertIntent) }.isSuccess
            if (launched) {
                val saveOutcome = acc.autoApproveSaveForm()
                if (saveOutcome.success) {
                    indexSavedReminderInFts(title, startMs, timeFmt)
                    return ExecutionReport(
                        success = true,
                        summary = "Created and automatically saved reminder '$title' for $timeFmt.",
                        nodesScanned = saveOutcome.updatedNodes
                    )
                }
            }
        }

        // 3. Autonomous local reminder scheduler fallback: NEVER leave a template hanging waiting for manual Save!
        val scheduled = NovaReminderScheduler.scheduleReminder(
            context = context,
            title = title,
            triggerAtMillis = startMs,
            formattedTimeLabel = timeFmt
        )
        indexSavedReminderInFts(title, startMs, timeFmt)
        return ExecutionReport(
            success = true,
            summary = if (scheduled) {
                "Created and saved reminder '$title' for $timeFmt — I'll prompt you when it's time."
            } else {
                "Saved reminder '$title' for $timeFmt to your local reminders."
            }
        )
    }

    private fun insertIntoCalendarProvider(title: String, startMs: Long, endMs: Long): Boolean {
        return runCatching {
            val calProjection = arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
            )
            var chosenCalendarId: Long? = null
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                calProjection,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                "${CalendarContract.Calendars.IS_PRIMARY} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    chosenCalendarId = cursor.getLong(0)
                }
            }
            val calId = chosenCalendarId ?: return false

            val eventValues = ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.HAS_ALARM, 1)
            }
            val eventUri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, eventValues)
                ?: return false
            val eventId = eventUri.lastPathSegment?.toLongOrNull()
            if (eventId != null) {
                val reminderValues = ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, 5)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
                runCatching {
                    context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, reminderValues)
                }
            }
            true
        }.getOrDefault(false)
    }

    private suspend fun indexSavedReminderInFts(title: String, startMs: Long, timeFmt: String) {
        val docId = "rem_${(title + "|" + startMs).hashCode() and 0x7FFFFFFF}"
        val body = "Reminder scheduled for $timeFmt"
        val vec = embeddingRuntime?.embedDocument(title, body)
        ftsHelper.upsertDocument(
            docId = docId,
            category = "calendar",
            title = title,
            body = body,
            uriOrRef = "nova://reminder/$docId",
            embedding = vec
        )
    }

    companion object {
        private const val TAG = "DeviceActionExecutor"
        private val ORDINALS = listOf("First", "Second", "Third", "Fourth")

        fun parseReminderTimeMillis(rawWhen: String?, nowMs: Long = System.currentTimeMillis()): Long {
            val clean = rawWhen?.trim().orEmpty().lowercase()
            if (clean.isEmpty()) return nowMs + 3600_000L

            // "in N minutes / hours / days"
            Regex("in\\s+(\\d+)\\s*(minute|minutes|min|mins|hour|hours|hr|hrs|day|days)").find(clean)?.let { m ->
                val count = m.groupValues[1].toLongOrNull() ?: 1L
                val unit = m.groupValues[2]
                val delta = when {
                    unit.startsWith("min") -> count * 60_000L
                    unit.startsWith("h") -> count * 3600_000L
                    unit.startsWith("d") -> count * 86_400_000L
                    else -> 3600_000L
                }
                return nowMs + delta
            }

            val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
            val hasTomorrow = clean.contains("tomorrow")
            if (hasTomorrow) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }

            // Explicit clock time like "5pm", "5:30 pm", "17:30"
            val timeMatch = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\b").find(clean)
            if (timeMatch != null && (clean.contains("at ") || timeMatch.groupValues[2].isNotEmpty() || timeMatch.groupValues[3].isNotEmpty())) {
                var hour = timeMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 23) ?: 9
                val minute = timeMatch.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: 0
                val ampm = timeMatch.groupValues[3]
                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0

                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, minute)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                if (!hasTomorrow && cal.timeInMillis <= nowMs) {
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
                return cal.timeInMillis
            }

            if (hasTomorrow) {
                cal.set(Calendar.HOUR_OF_DAY, 9)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                return cal.timeInMillis
            }
            if (clean.contains("tonight") || clean.contains("this evening")) {
                cal.set(Calendar.HOUR_OF_DAY, 19)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
                return cal.timeInMillis
            }
            if (clean.contains("this afternoon")) {
                cal.set(Calendar.HOUR_OF_DAY, 15)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                if (cal.timeInMillis <= nowMs) cal.add(Calendar.DAY_OF_YEAR, 1)
                return cal.timeInMillis
            }
            return nowMs + 3600_000L
        }

        fun buildAppSearchIntents(
            packageName: String,
            categoryLabel: String,
            query: String
        ): List<Intent> {
            val pkg = packageName.lowercase()
            val encoded = Uri.encode(query)
            val intents = mutableListOf<Intent>()
            when {
                pkg == "com.google.android.apps.youtube.music" || (pkg.contains("youtube") && pkg.contains("music")) -> {
                    intents.add(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$encoded")).apply {
                            setPackage(packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
                pkg == "com.google.android.youtube" || pkg.contains("youtube") -> {
                    intents.add(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                            setPackage(packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
                pkg == "com.android.vending" -> {
                    intents.add(
                        Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$encoded&c=apps")).apply {
                            setPackage(packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                    intents.add(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=$encoded&c=apps")).apply {
                            setPackage(packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
                pkg == "com.spotify.music" -> {
                    intents.add(
                        Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded")).apply {
                            setPackage(packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
                categoryLabel == "Maps/Navigation" || pkg == "com.google.android.apps.maps" || pkg == "com.waze" -> {
                    intents.add(
                        Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded")).apply {
                            setPackage(packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
                categoryLabel == "Browser/Web" || pkg == "com.android.chrome" -> {
                    intents.add(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$encoded")).apply {
                            setPackage(packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            }
            return intents
        }

        fun cleanDocumentForSpeech(doc: LocalIndexDocument): String {
            val cleanTitle = doc.title.removePrefix("Memory: ").trim()
            val cleanBody = doc.body
                .replace(Regex("Aliases:[^|]+\\|?"), "")
                .replace(Regex("Tags:[^|]+\\|?"), "")
                .replace(Regex("Package:\\s*[^|]+\\|?"), "")
                .replace(Regex("Capabilities:[^|]+\\|?"), "")
                .replace(Regex("How to control:.*"), "")
                .trim()
                .trim('|', ' ')
            return when {
                cleanBody.isBlank() -> cleanTitle
                cleanTitle.equals(cleanBody, ignoreCase = true) -> cleanTitle
                doc.category == "apps" -> "$cleanTitle (${cleanBody.removePrefix("Category:").trim()})"
                else -> "$cleanTitle — $cleanBody"
            }
        }

        fun formatMultipleChoices(
            nounPlural: String,
            items: List<LocalIndexDocument>,
            describe: (LocalIndexDocument) -> String
        ): String {
            val shown = items.take(ORDINALS.size)
            val spokenList = shown.mapIndexed { idx, doc ->
                "${ORDINALS[idx]}, ${describe(doc)}"
            }.joinToString(". ")
            val totalNote = if (items.size > shown.size) " (showing the top ${shown.size} of ${items.size})" else ""
            return "I found ${items.size} $nounPlural$totalNote. $spokenList. Which one would you like?"
        }
    }
}
