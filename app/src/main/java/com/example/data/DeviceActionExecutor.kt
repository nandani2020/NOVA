package com.example.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
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
import com.example.engine.AppCapabilityRegistry
import com.example.processA.EmbeddingGemmaRuntime
import com.example.processA.NovaAccessibilityService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Executes tool calls against real Android OS APIs, local SQLite FTS5 + EmbeddingGemma vectors,
 * and AccessibilityService UI automation/screenshots. Smart home controls remain disabled.
 */
class DeviceActionExecutor(
    private val context: Context,
    private val ftsHelper: LocalFtsIndexHelper,
    private val embeddingRuntime: EmbeddingGemmaRuntime? = null,
    private val onControlUpdated: (controlId: String, enabled: Boolean?, value: Float?, mode: String?) -> Unit
) {

    data class ExecutionReport(
        val success: Boolean,
        val summary: String,
        val nodesScanned: List<UiNodeSnapshot> = emptyList(),
        val ftsResults: List<LocalIndexDocument> = emptyList(),
        val capturedScreenshotBytes: ByteArray? = null
    )

    fun buildInstalledAppsContext(query: String): String =
        AppCapabilityRegistry.buildLlmAppContext(context, query)

    suspend fun execute(call: ToolCallRequest): ExecutionReport {
        return when (call.toolName) {
            "set_hardware_state" -> {
                val feature = call.args["feature"].orEmpty()
                val state = call.args["state"].orEmpty()
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
                val pct = call.args["percent"]?.toIntOrNull()?.coerceIn(0, 100) ?: 50
                if (target == "volume") {
                    setVolumePercent(pct)
                    onControlUpdated("ctrl_media", pct > 0, pct.toFloat(), null)
                    ExecutionReport(true, "Volume set to $pct%")
                } else if (target == "brightness") {
                    val ok = setScreenBrightnessPercent(pct)
                    ExecutionReport(
                        ok,
                        if (ok) "Screen brightness set to $pct%"
                        else "Opened 'Modify system settings' — grant permission so Nova can adjust brightness directly."
                    )
                } else {
                    ExecutionReport(true, "${target.replaceFirstChar { it.uppercase() }} set to $pct%")
                }
            }

            "create_timer" -> {
                val secs = call.args["duration_sec"]?.toIntOrNull()?.coerceAtLeast(5) ?: 300
                val label = call.args["label"] ?: "Nova Timer"
                val launched = launchSystemTimerIntent(secs, label)
                val mins = (secs / 60).coerceAtLeast(1).toFloat().coerceIn(1f, 60f)
                onControlUpdated("ctrl_timer", true, mins, "Focus")
                val suffix = if (launched) "" else " (clock app unavailable)"
                ExecutionReport(true, "Timer set for ${secs / 60}m ${secs % 60}s$suffix")
            }

            "create_alarm" -> {
                val time24 = call.args["time_24h"] ?: "07:30"
                val label = call.args["label"] ?: "Nova Alarm"
                launchSystemAlarmIntent(time24, label)
                ExecutionReport(true, "Alarm intent dispatched for $time24 ($label)")
            }

            "modify_clock_item" -> {
                val action = call.args["action"] ?: "stop"
                onControlUpdated("ctrl_timer", false, null, null)
                ExecutionReport(true, "Timer state updated ($action)")
            }

            "media_transport" -> {
                val cmd = call.args["command"] ?: "play"
                val dispatched = dispatchRealMediaKey(cmd)
                val isPlaying = cmd == "play" || cmd == "next" || cmd == "previous"
                onControlUpdated("ctrl_media", isPlaying, null, cmd.replaceFirstChar { it.uppercase() })
                ExecutionReport(
                    true,
                    "Media ${cmd.uppercase()} dispatched" + if (!dispatched) " (UI state)" else ""
                )
            }

            "play_media_query" -> {
                val q = call.args["query"] ?: "Music"
                val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    putExtra(android.app.SearchManager.QUERY, q)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val launched = runCatching { context.startActivity(intent) }.isSuccess
                onControlUpdated("ctrl_media", true, null, "Playing")
                ExecutionReport(
                    launched,
                    if (launched) "Dispatched media search for '$q'" else "No media player handled search '$q'"
                )
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
                val hits = ftsHelper.getDocumentsByCategory("messages", limit = 5)
                val summary = when {
                    hits.isEmpty() -> "You don't have any recent messages yet. Turn on Notification Access in Settings so I can read new messages for you."
                    hits.size == 1 -> "Message from ${hits.first().title}: ${hits.first().body}"
                    else -> formatMultipleChoices("recent messages", hits) { "From ${it.title}: ${it.body}" }
                }
                ExecutionReport(true, summary, ftsResults = hits)
            }

            "message_send" -> {
                val cid = call.args["contact_id"].orEmpty()
                val body = call.args["text"].orEmpty()
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(cid)}")).apply {
                    putExtra("sms_body", body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val opened = runCatching { context.startActivity(intent) }.isSuccess
                ExecutionReport(opened, if (opened) "Ready to send your message to $cid." else "No messaging app is available on this phone.")
            }

            "place_call" -> {
                val target = call.args["contact_id"].orEmpty()
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(target)}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val opened = runCatching { context.startActivity(dialIntent) }.isSuccess
                ExecutionReport(opened, if (opened) "Calling $target." else "Phone dialer is unavailable.")
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
                ftsHelper.syncCalendarEvents(context)
                val events = ftsHelper.getDocumentsByCategory("calendar", limit = 5)
                val summary = when {
                    events.isEmpty() -> "You have no upcoming calendar events."
                    events.size == 1 -> "Next on your calendar: ${events.first().title}, ${events.first().body}."
                    else -> formatMultipleChoices("upcoming events", events) { "${it.title}, ${it.body}" }
                }
                ExecutionReport(true, summary, ftsResults = events)
            }

            "create_calendar_event" -> {
                val title = call.args["title"] ?: "Nova Event"
                val durationMin = call.args["duration_min"]?.toIntOrNull()?.coerceAtLeast(15) ?: 30
                val now = System.currentTimeMillis() + 3600_000L
                val intent = Intent(Intent.ACTION_INSERT).apply {
                    data = CalendarContract.Events.CONTENT_URI
                    putExtra(CalendarContract.Events.TITLE, title)
                    putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, now)
                    putExtra(CalendarContract.EXTRA_EVENT_END_TIME, now + durationMin * 60_000L)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                val launched = runCatching { context.startActivity(intent) }.isSuccess
                ExecutionReport(
                    launched,
                    if (launched) "Opened Calendar event creator for '$title' (${durationMin}m)"
                    else "No Calendar app available"
                )
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
                    val visibleText = nodes
                        .map { it.label.replace(Regex("\\[(ON|OFF|[^\\]]+)\\]"), "").trim() }
                        .filter { it.isNotBlank() && !it.startsWith("Button (") }
                        .distinct()
                        .take(5)
                    val msg = if (visibleText.isNotEmpty()) {
                        "Looking at your screen, I see: ${visibleText.joinToString(", ")}."
                    } else {
                        "I captured what's on your screen."
                    }
                    ExecutionReport(
                        success = true,
                        summary = msg,
                        nodesScanned = nodes,
                        capturedScreenshotBytes = shotBytes
                    )
                }
            }

            "get_routine_steps" -> {
                val syncedApps = AppCapabilityRegistry.syncInstalledAppsWithCapabilities(context, ftsHelper, embeddingRuntime)
                val syncedContacts = ftsHelper.syncContacts(context).coerceAtLeast(0)
                val syncedEvents = ftsHelper.syncCalendarEvents(context).coerceAtLeast(0)
                ExecutionReport(
                    true,
                    "Updated your phone info: $syncedApps apps, $syncedContacts contacts, and $syncedEvents calendar events ready."
                )
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
                val appQuery = call.args["app_name"].orEmpty()
                val match = AppCapabilityRegistry.findBestMatch(context, appQuery)
                if (match == null) {
                    ExecutionReport(
                        false,
                        "I couldn't find an installed app called '$appQuery'."
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
                    mediaType = call.args["media_type"].orEmpty()
                )
            }

            "search_in_app" -> {
                executeSearchInApp(
                    appName = call.args["app_name"].orEmpty(),
                    query = call.args["query"].orEmpty()
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
                ExecutionReport(true, buildDeviceBriefing(topic))
            }

            else -> ExecutionReport(false, "Unsupported tool: ${call.toolName}")
        }
    }

    private suspend fun executePlayInApp(
        appName: String,
        rawQuery: String,
        mediaType: String
    ): ExecutionReport {
        val cleanedQuery = rawQuery.trim()
        val isGenericRequest = cleanedQuery.isBlank() || cleanedQuery.lowercase() in setOf(
            "a song", "song", "some music", "music", "track", "a track", "something", "audio", "playlist"
        )
        val effectiveQuery = if (isGenericRequest) "music" else cleanedQuery

        val match = if (appName.isNotBlank()) {
            AppCapabilityRegistry.findBestMatch(context, appName)
        } else {
            AppCapabilityRegistry.findMatchingProfiles(context, "media_search", limit = 1).firstOrNull()
        }

        if (match == null) {
            if (appName.isNotBlank()) {
                return ExecutionReport(false, "I couldn't find '$appName' installed on your phone.")
            }
            val fallbackIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                putExtra(android.app.SearchManager.QUERY, effectiveQuery)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val started = runCatching { context.startActivity(fallbackIntent) }.isSuccess
            if (started) onControlUpdated("ctrl_media", true, null, "Playing")
            return ExecutionReport(
                started,
                if (started) "Playing $effectiveQuery." else "No music or media app is available to play '$effectiveQuery'."
            )
        }

        val pm = context.packageManager
        val playSearchIntent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
            setPackage(match.packageName)
            putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
            putExtra(android.app.SearchManager.QUERY, effectiveQuery)
            if (mediaType.isNotBlank()) {
                putExtra(MediaStore.EXTRA_MEDIA_TITLE, effectiveQuery)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (playSearchIntent.resolveActivity(pm) != null &&
            runCatching { context.startActivity(playSearchIntent) }.isSuccess
        ) {
            onControlUpdated("ctrl_media", true, null, "Playing")
            val label = if (isGenericRequest) "music" else "'$effectiveQuery'"
            return ExecutionReport(true, "Opening ${match.appName} and playing $label.")
        }

        val inAppSearch = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(match.packageName)
            putExtra(android.app.SearchManager.QUERY, effectiveQuery)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (inAppSearch.resolveActivity(pm) != null &&
            runCatching { context.startActivity(inAppSearch) }.isSuccess
        ) {
            onControlUpdated("ctrl_media", true, null, "Playing")
            return ExecutionReport(true, "Opened ${match.appName} and searched for '$effectiveQuery'.")
        }

        val launch = pm.getLaunchIntentForPackage(match.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launch != null && runCatching { context.startActivity(launch) }.isSuccess) {
            delay(350L)
            dispatchRealMediaKey("play")
            onControlUpdated("ctrl_media", true, null, "Playing")
            return ExecutionReport(
                true,
                "Opened ${match.appName} and started playback."
            )
        }

        return ExecutionReport(false, "I found ${match.appName}, but couldn't start playback in it.")
    }

    private fun executeSearchInApp(appName: String, query: String): ExecutionReport {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            return ExecutionReport(false, "Tell me what you'd like me to search for.")
        }
        val match = AppCapabilityRegistry.findBestMatch(context, appName)
            ?: return ExecutionReport(false, "I couldn't find an installed app called '$appName'.")

        val pm = context.packageManager
        val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(match.packageName)
            putExtra(android.app.SearchManager.QUERY, cleanQuery)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (searchIntent.resolveActivity(pm) != null &&
            runCatching { context.startActivity(searchIntent) }.isSuccess
        ) {
            return ExecutionReport(true, "Searching for '$cleanQuery' in ${match.appName}.")
        }

        val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            setPackage(match.packageName)
            putExtra(android.app.SearchManager.QUERY, cleanQuery)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (webSearchIntent.resolveActivity(pm) != null &&
            runCatching { context.startActivity(webSearchIntent) }.isSuccess
        ) {
            return ExecutionReport(true, "Searching for '$cleanQuery' in ${match.appName}.")
        }

        if (match.packageName.contains("youtube", ignoreCase = true) &&
            !match.packageName.contains("music", ignoreCase = true)
        ) {
            val ytUrl = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(cleanQuery)}")
            val ytIntent = Intent(Intent.ACTION_VIEW, ytUrl).apply {
                setPackage(match.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (ytIntent.resolveActivity(pm) != null &&
                runCatching { context.startActivity(ytIntent) }.isSuccess
            ) {
                return ExecutionReport(true, "Searching for '$cleanQuery' in ${match.appName}.")
            }
        }

        val launch = pm.getLaunchIntentForPackage(match.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (launch != null && runCatching { context.startActivity(launch) }.isSuccess) {
            return ExecutionReport(true, "Opened ${match.appName} so you can view '$cleanQuery'.")
        }

        return ExecutionReport(false, "I couldn't open ${match.appName} to search for '$cleanQuery'.")
    }

    private fun executeNavigateInMaps(destination: String, appName: String): ExecutionReport {
        val cleanDest = destination.trim()
        if (cleanDest.isBlank()) {
            return ExecutionReport(false, "Tell me where you want directions to.")
        }
        val match = if (appName.isNotBlank()) {
            AppCapabilityRegistry.findBestMatch(context, appName)
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

            // On Android 13+, AOSP blocks Adapter.enable() for non-system apps.
            // Use Nova's Accessibility Service hands-free Switch flipper if connected!
            val acc = NovaAccessibilityService.instance
            if (acc != null) {
                openBluetoothSettings()
                val toggled = acc.autoToggleSettingsSwitch(
                    keywords = listOf("bluetooth", "use bluetooth"),
                    desiredOn = desiredOn
                )
                val verifiedOn = runCatching { adapter.isEnabled }.getOrDefault(desiredOn)
                onControlUpdated("ctrl_bt", verifiedOn, null, null)
                if (toggled) {
                    return ExecutionReport(
                        true,
                        "Bluetooth turned ${if (verifiedOn) "ON" else "OFF"} automatically via Accessibility control"
                    )
                }
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
                    keywords = listOf("bluetooth", "use bluetooth"),
                    desiredOn = desiredOn
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

    private fun executeListManagement(listName: String, action: String, item: String): ExecutionReport {
        val cleanList = if (listName.contains("shop", ignoreCase = true)) "shopping" else "todo"
        val docPrefix = "list_${cleanList}_"
        return when (action.lowercase()) {
            "add" -> {
                if (item.isBlank()) return ExecutionReport(false, "Specify an item to add to your $cleanList list.")
                val id = "$docPrefix${System.currentTimeMillis()}"
                ftsHelper.upsertDocument(
                    docId = id,
                    category = "lists",
                    title = "${cleanList.replaceFirstChar { it.uppercase() }} List",
                    body = item.trim(),
                    uriOrRef = "list://$cleanList/$id"
                )
                ExecutionReport(true, "Added '${item.trim()}' to your $cleanList list.")
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

    @SuppressLint("MissingPermission")
    private fun buildDeviceBriefing(topic: String): String {
        val now = Date()
        val timeStr = SimpleDateFormat("h:mm a, EEEE MMM d", Locale.getDefault()).format(now)
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val batteryStr = if (pct >= 0) "$pct%${if (isCharging) " (Charging)" else ""}" else "unavailable"

        return when (topic.lowercase()) {
            "time_date" -> "It's $timeStr."
            "battery" -> "Battery is at $batteryStr."
            "connectivity" -> {
                val snap = readLiveHardwareSnapshot()
                "Bluetooth: ${if (snap.bluetoothOn) "ON" else "OFF"} • Wi-Fi: ${if (snap.wifiOn) "ON" else "OFF"} • DND: ${if (snap.dndOn) "ON" else "OFF"} • Media Volume: ${snap.volumePercent}%"
            }
            else -> {
                val snap = readLiveHardwareSnapshot()
                ftsHelper.syncCalendarEvents(context)
                val events = ftsHelper.getDocumentsByCategory("calendar", limit = 3)
                val unread = ftsHelper.countDocuments("messages")
                val calPart = if (events.isEmpty()) "No upcoming calendar events indexed"
                else "Next: ${events.first().title} (${events.first().body})"
                "Good day! It's $timeStr. Battery is $batteryStr. $calPart. Unread indexed notifications: $unread. Wi-Fi ${if (snap.wifiOn) "ON" else "OFF"}, Bluetooth ${if (snap.bluetoothOn) "ON" else "OFF"}."
            }
        }
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

    companion object {
        private const val TAG = "DeviceActionExecutor"
        private val ORDINALS = listOf("First", "Second", "Third", "Fourth")

        fun cleanDocumentForSpeech(doc: LocalIndexDocument): String {
            val cleanTitle = doc.title.removePrefix("Memory: ").trim()
            val cleanBody = doc.body
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
