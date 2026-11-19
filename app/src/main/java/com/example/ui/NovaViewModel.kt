package com.example.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Debug
import android.os.IBinder
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AgenticMemoryEntry
import com.example.data.AsrEngineOption
import com.example.data.AssistantPersonality
import com.example.data.ControlIconType
import com.example.data.DeviceAccountHelper
import com.example.data.DeviceActionExecutor
import com.example.data.HubCategory
import com.example.data.InstalledModelInfo
import com.example.data.LearnedProcessRecord
import com.example.data.LlmModelManager
import com.example.data.LlmModelPreset
import com.example.data.LocalFtsIndexHelper
import com.example.data.LocalIndexDocument
import com.example.data.ModelDownloadState
import com.example.data.ModelSlot
import com.example.data.OrbVisualMode
import com.example.data.PipelineStepPill
import com.example.data.ProcessHealthState
import com.example.data.RecordedRoutine
import com.example.data.RecordedRoutineStore
import com.example.data.RoutineRecorderState
import com.example.data.RoutineScope
import com.example.data.RoutingTier
import com.example.data.SmartControlItem
import com.example.data.StepStatus
import com.example.data.SystemTtsEngineInfo
import com.example.data.SystemTtsVoiceInfo
import com.example.data.TierRedConfirmation
import com.example.data.TtsEngineOption
import com.example.data.TurnExecutionOutcome
import com.example.data.WakeWordStatus
import com.example.engine.AgenticMemoryEngine
import com.example.engine.AppCapabilityRegistry
import com.example.engine.ImportantNotificationFilter.ImportantNotificationAlert
import com.example.engine.ImportantNotificationFilter.ImportantNotificationType
import com.example.engine.NovaPipelineRouter
import com.example.engine.RoutineExecutionEngine
import com.example.engine.SemanticLookupEngine
import com.example.processA.EmbeddingGemmaRuntime
import com.example.processA.LlmProcessBridge
import com.example.processA.NovaAccessibilityService
import com.example.processA.NovaNotificationListenerService
import com.example.processA.NovaVoiceForegroundService
import com.example.processA.RoutineRecorderManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Context.novaDataStore by preferencesDataStore(name = "nova_settings")

data class VoiceOverlayUiState(
    val isListening: Boolean = false,
    val showKeyboardInput: Boolean = false,
    val statusHeader: String = "SAY SOMETHING",
    val transcript: String = "",
    val assistantResponse: String = "Hardware reflexes (<5ms) are ready. Download Gemma 4 E2B in Settings for local AI.",
    val orbMode: OrbVisualMode = OrbVisualMode.IDLE,
    val amplitude: Float = 0.2f,
    val stepPills: List<PipelineStepPill> = emptyList(),
    val lastRoutingTier: RoutingTier? = null,
    val lastLatencyMs: Long? = null,
    val isSpeakingTts: Boolean = false,
    val requiresModelSetup: Boolean = false,
    val requiresAccessibilitySetup: Boolean = false,
    val isAwaitingFollowUp: Boolean = false,
    val followUpListenTrigger: Long = 0L,
    val followUpCancelTrigger: Long = 0L,
    val useServiceFollowUpMic: Boolean = false
)

data class NovaUiState(
    val userName: String = "NOVA USER",
    val recordedAccount: String? = null,
    val selectedCategory: HubCategory = HubCategory.FAVOURITES,
    val controls: List<SmartControlItem> = defaultControls(),
    val activeDetailControlId: String = "ctrl_timer",
    val voiceState: VoiceOverlayUiState = VoiceOverlayUiState(),
    val pendingConfirmation: TierRedConfirmation? = null,
    val processHealth: ProcessHealthState = ProcessHealthState(),
    val llmPreset: LlmModelPreset? = null,
    val llmPresets: List<LlmModelPreset> = emptyList(),
    val embeddingPreset: LlmModelPreset? = null,
    val asrPresets: List<LlmModelPreset> = emptyList(),
    val ttsPresets: List<LlmModelPreset> = emptyList(),
    val installedModelsBySlot: Map<ModelSlot, List<InstalledModelInfo>> = emptyMap(),
    val availableTtsEngines: List<SystemTtsEngineInfo> = emptyList(),
    val availableTtsVoices: List<SystemTtsVoiceInfo> = emptyList(),
    val downloadState: ModelDownloadState = ModelDownloadState.Idle,
    val ftsSearchQuery: String = "",
    val ftsSearchResults: List<LocalIndexDocument> = emptyList(),
    val showSettingsScreen: Boolean = false,
    val statusToast: String? = null,
    val openVoiceTrigger: Long = 0L,
    val autoListenOnOverlay: Boolean = false,
    val activeNotificationPrompt: ImportantNotificationAlert? = null,
    val recordedRoutines: List<RecordedRoutine> = emptyList(),
    val recorderState: RoutineRecorderState = RoutineRecorderState(),
    val editingRoutine: RecordedRoutine? = null,
    val installedAppsForRoutines: List<AppCapabilityRegistry.InstalledAppProfile> = emptyList(),
    val showHiddenReadme: Boolean = false,
    val assistantPersonality: AssistantPersonality = AssistantPersonality.WARM_COMPANION,
    val agenticMemories: List<AgenticMemoryEntry> = emptyList(),
    val learnedProcesses: List<LearnedProcessRecord> = emptyList()
)

private fun defaultControls(): List<SmartControlItem> = listOf(
    SmartControlItem(
        id = "ctrl_timer",
        title = "Focus Timer",
        subtitle = "25:00 • Focus",
        category = HubCategory.FAVOURITES,
        isEnabled = false,
        hasRadialDetail = true,
        primaryValue = 25f,
        minValue = 5f,
        maxValue = 60f,
        stepSize = 5f,
        unit = "min",
        modes = listOf("Focus", "Break", "Deep Work", "Custom"),
        selectedMode = "Focus",
        intensities = listOf("15m", "25m", "45m"),
        selectedIntensity = "25m",
        iconType = ControlIconType.TIMER
    ),
    SmartControlItem(
        id = "ctrl_media",
        title = "Media & Volume",
        subtitle = "System Stream",
        category = HubCategory.FAVOURITES,
        isEnabled = true,
        hasRadialDetail = true,
        primaryValue = 65f,
        minValue = 0f,
        maxValue = 100f,
        stepSize = 5f,
        unit = "%",
        modes = listOf("Play", "Pause", "Next", "Prev"),
        selectedMode = "Play",
        intensities = listOf("Quiet", "Balanced", "Boost"),
        selectedIntensity = "Balanced",
        iconType = ControlIconType.MEDIA
    ),
    SmartControlItem(
        id = "ctrl_flashlight",
        title = "Flashlight",
        subtitle = "Hardware Torch",
        category = HubCategory.FAVOURITES,
        isEnabled = false,
        hasRadialDetail = false,
        primaryValue = 0f,
        minValue = 0f,
        maxValue = 100f,
        unit = "%",
        iconType = ControlIconType.FLASHLIGHT
    ),
    SmartControlItem(
        id = "ctrl_wifi",
        title = "Wi-Fi Panel",
        subtitle = "System Connectivity",
        category = HubCategory.FAVOURITES,
        isEnabled = true,
        hasRadialDetail = false,
        primaryValue = 100f,
        minValue = 0f,
        maxValue = 100f,
        unit = "%",
        iconType = ControlIconType.WIFI
    ),
    SmartControlItem(
        id = "ctrl_bt",
        title = "Bluetooth",
        subtitle = "Device Settings",
        category = HubCategory.FAVOURITES,
        isEnabled = true,
        hasRadialDetail = false,
        primaryValue = 100f,
        minValue = 0f,
        maxValue = 100f,
        unit = "%",
        iconType = ControlIconType.BLUETOOTH
    ),
    SmartControlItem(
        id = "ctrl_dnd",
        title = "Do Not Disturb",
        subtitle = "Local Quiet State",
        category = HubCategory.FAVOURITES,
        isEnabled = false,
        hasRadialDetail = false,
        primaryValue = 0f,
        minValue = 0f,
        maxValue = 100f,
        unit = "%",
        iconType = ControlIconType.DND
    ),
    SmartControlItem(
        id = "app_screen",
        title = "Screen Context",
        subtitle = "Accessibility Tree Scan",
        category = HubCategory.PHONE_APPS,
        isEnabled = true,
        hasRadialDetail = false,
        primaryValue = 0f,
        minValue = 0f,
        maxValue = 100f,
        unit = "",
        iconType = ControlIconType.APP,
        packageHint = "What is on my screen"
    ),
    SmartControlItem(
        id = "app_dialer",
        title = "Phone Dialer",
        subtitle = "Direct Intent Reflex",
        category = HubCategory.PHONE_APPS,
        isEnabled = true,
        hasRadialDetail = false,
        primaryValue = 0f,
        minValue = 0f,
        maxValue = 100f,
        unit = "",
        iconType = ControlIconType.APP,
        packageHint = "Call voicemail"
    ),
    SmartControlItem(
        id = "rt_sync_apps",
        title = "Sync App Index",
        subtitle = "Index Installed Apps (FTS5)",
        category = HubCategory.ROUTINES,
        isEnabled = false,
        hasRadialDetail = false,
        primaryValue = 0f,
        minValue = 0f,
        maxValue = 100f,
        unit = "",
        iconType = ControlIconType.ROUTINE,
        packageHint = "Sync installed apps"
    )
)

class NovaViewModel(application: Application) : AndroidViewModel(application) {

    private val ftsHelper = LocalFtsIndexHelper(application)
    private val routineStore = RecordedRoutineStore(application)
    private val accountHelper = DeviceAccountHelper(application)
    val modelManager = LlmModelManager(application)
    private val embeddingRuntime = EmbeddingGemmaRuntime(application, modelManager)
    private val llmBridge = LlmProcessBridge(application, modelManager)

    private val _uiState = MutableStateFlow(
        NovaUiState(
            recordedAccount = accountHelper.getOrDetectSystemAccount(),
            llmPreset = modelManager.llmPreset,
            llmPresets = modelManager.llmPresets,
            embeddingPreset = modelManager.embeddingPreset,
            asrPresets = modelManager.asrPresets,
            ttsPresets = modelManager.ttsPresets,
            recordedRoutines = routineStore.getAll()
        )
    )
    val uiState: StateFlow<NovaUiState> = _uiState.asStateFlow()

    private val agenticMemoryEngine = AgenticMemoryEngine(
        ftsHelper = ftsHelper,
        embeddingRuntime = embeddingRuntime,
        personalityProvider = { _uiState.value.assistantPersonality },
        userNameProvider = { _uiState.value.userName },
        onPersonalityChanged = { mode -> setAssistantPersonality(mode) },
        onUserNameChanged = { newName -> updateUserName(newName) }
    )

    private val executor = DeviceActionExecutor(
        context = application,
        ftsHelper = ftsHelper,
        embeddingRuntime = embeddingRuntime,
        routineStore = routineStore,
        agenticMemoryEngine = agenticMemoryEngine,
        onControlUpdated = { id, enabled, value, mode ->
            applyControlPatch(id, enabled, value, mode)
        }
    )

    private val router = NovaPipelineRouter(
        llmBridge = llmBridge,
        executor = executor,
        embeddingRuntime = embeddingRuntime,
        accountHelper = accountHelper,
        agenticMemoryEngine = agenticMemoryEngine,
        onAccountRecorded = { saved ->
            _uiState.update { it.copy(recordedAccount = saved) }
        }
    )

    private var activeTurnJob: Job? = null
    private var followUpJob: Job? = null
    private var followUpFallbackJob: Job? = null
    private var followUpListenWindowMs: Long = FOLLOW_UP_TIMEOUT_MS
    private var downloadJob: Job? = null
    private var voiceService: NovaVoiceForegroundService? = null
    private var isVoiceServiceBound = false
    private var isAppInForeground = true
    private var isVoiceOverlayOpen = false
    @Volatile
    private var speechGenerationId = 0L
    @Volatile
    private var pendingPauseWakeTarget: Boolean? = null
    internal var testSpeakDispatcher: ((String, () -> Unit) -> Boolean)? = null
    internal var testServiceFollowUpDispatcher: ((Long, (String) -> Unit) -> Boolean)? = null

    private val fgsConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? NovaVoiceForegroundService.LocalBinder
            voiceService = binder?.getService()
            isVoiceServiceBound = true
            val health = _uiState.value.processHealth
            voiceService?.setProactiveNotificationPromptsEnabled(health.proactiveNotificationPrompts)
            voiceService?.setPauseWakeDuringMedia(health.pauseWakeDuringMedia)
            voiceService?.setAsrEngineOption(health.asrEngine)
            voiceService?.configureTts(
                enginePackage = health.selectedSystemTtsEnginePkg,
                voiceName = health.selectedSystemTtsVoiceName,
                speechRate = health.ttsSpeechRate,
                pitch = health.ttsPitch
            )
            voiceService?.setBargeInCallback { decision ->
                handleVoiceBargeInDecision(decision)
            }
            applyWakeWordServiceBinding(health.wakeWordEnabled)
            refreshInstalledModelsAndVoices()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService?.setBargeInCallback(null)
            voiceService = null
            isVoiceServiceBound = false
        }
    }

    init {
        refreshFtsQuery("")
        refreshAgenticLearningState()

        // Sync live hardware toggles (Bluetooth, Wi-Fi, DND, Media Volume) from real OS APIs
        val hwSnap = executor.readLiveHardwareSnapshot()
        applyControlPatch("ctrl_bt", hwSnap.bluetoothOn, null, null)
        applyControlPatch("ctrl_wifi", hwSnap.wifiOn, null, null)
        applyControlPatch("ctrl_dnd", hwSnap.dndOn, null, null)
        applyControlPatch("ctrl_media", hwSnap.volumePercent > 0, hwSnap.volumePercent.toFloat(), null)

        // Bind Process B (:llm) isolated inference runtime
        llmBridge.bindToLlmProcess()

        // Warm up EmbeddingGemma 2, pre-warm skill prototypes, refresh apps/tags, and backfill any null vectors
        viewModelScope.launch(Dispatchers.IO) {
            if (embeddingRuntime.isModelFilePresent()) {
                if (embeddingRuntime.warmup()) {
                    SemanticLookupEngine.warmPrototypes(embeddingRuntime)
                }
            }
            val discovered = AppCapabilityRegistry.syncInstalledAppsWithCapabilities(
                getApplication(),
                ftsHelper,
                embeddingRuntime
            )
            _uiState.update {
                it.copy(
                    installedAppsForRoutines = AppCapabilityRegistry.discoverInstalledApps(getApplication()).take(40)
                )
            }
            if (embeddingRuntime.isReady.value) {
                ftsHelper.backfillMissingEmbeddings(embeddingRuntime)
            }
            refreshFtsQuery(_uiState.value.ftsSearchQuery)
        }

        // Observe recorded routines & live floating recorder state
        viewModelScope.launch {
            routineStore.routines.collect { list ->
                _uiState.update { it.copy(recordedRoutines = list) }
            }
        }
        viewModelScope.launch {
            RoutineRecorderManager.state.collect { rec ->
                if (!rec.isRecording && rec.lastStatusMessage.startsWith("Saved '")) {
                    routineStore.reload()
                }
                _uiState.update { it.copy(recorderState = rec) }
            }
        }

        // Start & bind Process A foreground service
        NovaVoiceForegroundService.start(application)
        runCatching {
            application.bindService(
                Intent(application, NovaVoiceForegroundService::class.java),
                fgsConnection,
                Context.BIND_AUTO_CREATE
            )
        }

        // Load persisted preferences (display name, personality, wake-word, media protection, ASR/TTS engines & voice)
        viewModelScope.launch {
            application.novaDataStore.data
                .map { prefs ->
                    val micGranted = ContextCompat.checkSelfPermission(
                        application,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    val explicitWake = prefs[KEY_WAKE_WORD]
                    val wakeEnabled = explicitWake ?: micGranted
                    val persona = AssistantPersonality.fromIdOrName(prefs[KEY_PERSONALITY])
                        ?: AssistantPersonality.WARM_COMPANION
                    PrefSnapshot(
                        userName = prefs[KEY_USER_NAME] ?: "NOVA USER",
                        wakeEnabled = wakeEnabled,
                        preferNpu = prefs[KEY_PREFER_NPU] ?: false,
                        proactiveNotifPrompts = prefs[KEY_PROACTIVE_NOTIF_PROMPTS] ?: true,
                        thinkingEnabled = prefs[KEY_THINKING_ENABLED] ?: true,
                        personality = persona,
                        pauseWakeDuringMedia = prefs[KEY_PAUSE_WAKE_DURING_MEDIA] ?: false,
                        asrEngine = AsrEngineOption.fromId(prefs[KEY_ASR_ENGINE]),
                        ttsEngine = TtsEngineOption.fromId(prefs[KEY_TTS_ENGINE]),
                        systemTtsEnginePkg = prefs[KEY_SYSTEM_TTS_ENGINE_PKG] ?: "",
                        systemTtsVoiceName = prefs[KEY_SYSTEM_TTS_VOICE_NAME] ?: "",
                        ttsSpeechRate = (prefs[KEY_TTS_SPEECH_RATE] ?: 1.0f).coerceIn(0.6f, 1.8f),
                        ttsPitch = (prefs[KEY_TTS_PITCH] ?: 1.0f).coerceIn(0.6f, 1.5f)
                    )
                }
                .collect { snap ->
                    val effectivePauseWake = pendingPauseWakeTarget?.let { pending ->
                        if (snap.pauseWakeDuringMedia == pending) {
                            pendingPauseWakeTarget = null
                        }
                        pending
                    } ?: snap.pauseWakeDuringMedia
                    _uiState.update {
                        it.copy(
                            userName = snap.userName,
                            assistantPersonality = snap.personality,
                            processHealth = it.processHealth.copy(
                                wakeWordEnabled = snap.wakeEnabled,
                                preferNpuBackend = snap.preferNpu,
                                proactiveNotificationPrompts = snap.proactiveNotifPrompts,
                                thinkingEnabled = snap.thinkingEnabled,
                                assistantPersonality = snap.personality,
                                pauseWakeDuringMedia = effectivePauseWake,
                                asrEngine = snap.asrEngine,
                                ttsEngine = snap.ttsEngine,
                                selectedSystemTtsEnginePkg = snap.systemTtsEnginePkg,
                                selectedSystemTtsVoiceName = snap.systemTtsVoiceName,
                                ttsSpeechRate = snap.ttsSpeechRate,
                                ttsPitch = snap.ttsPitch
                            )
                        )
                    }
                    voiceService?.setProactiveNotificationPromptsEnabled(snap.proactiveNotifPrompts)
                    voiceService?.setPauseWakeDuringMedia(effectivePauseWake)
                    voiceService?.setAsrEngineOption(snap.asrEngine)
                    voiceService?.configureTts(
                        enginePackage = snap.systemTtsEnginePkg,
                        voiceName = snap.systemTtsVoiceName,
                        speechRate = snap.ttsSpeechRate,
                        pitch = snap.ttsPitch
                    )
                    applyWakeWordServiceBinding(snap.wakeEnabled)
                }
        }

        // Observe live external media playback state so UI reflects PAUSED_FOR_MEDIA cleanly
        viewModelScope.launch {
            NovaVoiceForegroundService.externalMediaPlaying.collect { playing ->
                _uiState.update { cur ->
                    cur.copy(
                        processHealth = cur.processHealth.copy(externalMediaPlaying = playing)
                    )
                }
            }
        }

        // Observe personalized "Hey Nova" wake-voice calibration progress and learned phonetic aliases
        viewModelScope.launch {
            NovaVoiceForegroundService.wakeCalibrationSamples.collect { samples ->
                _uiState.update { cur ->
                    cur.copy(
                        processHealth = cur.processHealth.copy(wakeCalibrationSamples = samples)
                    )
                }
            }
        }
        viewModelScope.launch {
            NovaVoiceForegroundService.wakeCalibrationAliases.collect { aliases ->
                _uiState.update { cur ->
                    cur.copy(
                        processHealth = cur.processHealth.copy(wakeCalibrationAliases = aliases)
                    )
                }
            }
        }

        // Collect real-time important notifications (Messages, Missed Calls, Reminders) for proactive visual prompting
        viewModelScope.launch {
            NovaNotificationListenerService.incomingImportantAlerts.collect { alert ->
                val unreadNow = ftsHelper.countUnreadNotifications()
                _uiState.update { cur ->
                    val showPrompt = cur.processHealth.proactiveNotificationPrompts
                    cur.copy(
                        activeNotificationPrompt = if (showPrompt) alert else cur.activeNotificationPrompt,
                        processHealth = cur.processHealth.copy(
                            unreadMessagesCount = unreadNow
                        )
                    )
                }
            }
        }

        // Register hardware Volume-Down / accessibility kill switch listener
        NovaAccessibilityService.registerAbortListener { reason ->
            handleKillSwitchAbort(reason)
        }

        // Observe model download progress
        viewModelScope.launch {
            modelManager.downloadState.collect { dlState ->
                _uiState.update { it.copy(downloadState = dlState) }
            }
        }

        // Observe Process A, EmbeddingGemma & Process B real health telemetry
        viewModelScope.launch {
            combine(
                llmBridge.state,
                embeddingRuntime.isReady,
                NovaAccessibilityService.isConnected,
                NovaNotificationListenerService.isConnected,
                NovaVoiceForegroundService.wakeWordStatus
            ) { bridge, embReady, accConnected, notifConnected, wakeStatus ->
                TelemetrySnapshot(bridge, embReady, accConnected, notifConnected, wakeStatus)
            }.collect { (bridge, embReady, accConnected, notifConnected, wakeStatus) ->
                val runtime = Runtime.getRuntime()
                val jvmUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
                val nativeUsedMb = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
                val totalProcAMb = (jvmUsedMb + nativeUsedMb).toInt()
                val embFile = modelManager.getActiveModelFile(ModelSlot.EMBEDDING)
                val asrFile = modelManager.getActiveModelFile(ModelSlot.ASR)
                val ttsFile = modelManager.getActiveModelFile(ModelSlot.TTS)
                val installedBySlot = ModelSlot.entries.associateWith { modelManager.listInstalledModels(it) }

                val (agenticMemCount, learnedProcCount) = ftsHelper.getAgenticMemoryCounts()
                _uiState.update { cur ->
                    cur.copy(
                        installedModelsBySlot = installedBySlot,
                        processHealth = cur.processHealth.copy(
                            processAStatus = "ACTIVE",
                            processAPid = Process.myPid(),
                            processAHeapMb = totalProcAMb,
                            processBStatus = bridge.processBStatus,
                            processBPid = bridge.processBPid,
                            isLlmReady = bridge.isLlmReady,
                            modelPresent = bridge.modelPresent,
                            activeModelName = bridge.activeModelName,
                            activeModelSizeMb = bridge.activeModelSizeMb,
                            activeLlmBackend = bridge.activeLlmBackend,
                            embeddingPresent = embFile != null,
                            embeddingReady = embReady,
                            activeEmbeddingName = embFile?.name,
                            activeEmbeddingSizeMb = modelManager.getActiveModelSizeBytes(ModelSlot.EMBEDDING) / (1024L * 1024L),
                            asrModelPresent = asrFile != null,
                            activeAsrModelName = asrFile?.name,
                            activeAsrModelSizeMb = modelManager.getActiveModelSizeBytes(ModelSlot.ASR) / (1024L * 1024L),
                            ttsModelPresent = ttsFile != null,
                            activeTtsModelName = ttsFile?.name,
                            activeTtsModelSizeMb = modelManager.getActiveModelSizeBytes(ModelSlot.TTS) / (1024L * 1024L),
                            respawnCount = bridge.respawnCount,
                            ftsMode = ftsHelper.activeFtsMode,
                            indexedDocCount = ftsHelper.countDocuments(),
                            unreadMessagesCount = ftsHelper.countUnreadNotifications(),
                            accessibilityConnected = accConnected,
                            notificationListenerConnected = notifConnected ||
                                NovaNotificationListenerService.isPermissionGranted(getApplication()),
                            wakeWordStatus = wakeStatus,
                            agenticMemoryCount = agenticMemCount,
                            learnedProcessCount = learnedProcCount
                        )
                    )
                }
            }
        }
    }

    private data class PrefSnapshot(
        val userName: String,
        val wakeEnabled: Boolean,
        val preferNpu: Boolean,
        val proactiveNotifPrompts: Boolean,
        val thinkingEnabled: Boolean,
        val personality: AssistantPersonality,
        val pauseWakeDuringMedia: Boolean,
        val asrEngine: AsrEngineOption,
        val ttsEngine: TtsEngineOption,
        val systemTtsEnginePkg: String,
        val systemTtsVoiceName: String,
        val ttsSpeechRate: Float,
        val ttsPitch: Float
    )

    private data class TelemetrySnapshot(
        val bridge: LlmProcessBridge.BridgeState,
        val embReady: Boolean,
        val accConnected: Boolean,
        val notifConnected: Boolean,
        val wakeStatus: WakeWordStatus
    )

    private fun applyWakeWordServiceBinding(enabled: Boolean) {
        voiceService?.setWakeWordListening(enabled) { command ->
            if (command.isNotBlank()) {
                _uiState.update {
                    it.copy(
                        showSettingsScreen = false,
                        openVoiceTrigger = System.currentTimeMillis(),
                        autoListenOnOverlay = false
                    )
                }
                submitCommand(command, speakResponse = true)
            } else {
                // Bare "Hey Nova": open Voice Overlay and immediately begin active command listening (10s window)
                voiceService?.setOverlayListeningPaused(true)
                _uiState.update {
                    it.copy(
                        showSettingsScreen = false,
                        openVoiceTrigger = System.currentTimeMillis(),
                        autoListenOnOverlay = false
                    )
                }
                startFollowUpListenWindow(
                    speakOnTimeout = true,
                    headerOverride = "HEY NOVA • LISTENING"
                )
                _uiState.update {
                    it.copy(
                        voiceState = it.voiceState.copy(
                            transcript = "",
                            stepPills = emptyList(),
                            assistantResponse = "I'm listening — go ahead with your command.",
                            requiresModelSetup = false,
                            requiresAccessibilitySetup = false
                        )
                    )
                }
            }
        }
    }

    fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        val isAssist = intent.action == Intent.ACTION_ASSIST ||
            intent.getBooleanExtra("open_voice_overlay", false)
        if (!isAssist) return

        val wakeCommand = intent.getStringExtra("wake_command")?.trim().orEmpty()
        val autoListen = intent.getBooleanExtra("auto_listen", wakeCommand.isEmpty())
        _uiState.update {
            it.copy(
                showSettingsScreen = false,
                openVoiceTrigger = System.currentTimeMillis(),
                autoListenOnOverlay = false
            )
        }
        if (wakeCommand.isNotEmpty()) {
            submitCommand(wakeCommand, speakResponse = true)
        } else if (autoListen) {
            voiceService?.setOverlayListeningPaused(true)
            startFollowUpListenWindow(
                speakOnTimeout = true,
                headerOverride = "HEY NOVA • LISTENING"
            )
        }
    }

    fun consumeAutoListen() {
        _uiState.update { it.copy(autoListenOnOverlay = false) }
    }

    fun onMicPermissionGranted() {
        val app = getApplication<Application>()
        NovaVoiceForegroundService.start(app)
        viewModelScope.launch {
            var shouldAutoEnable = false
            app.novaDataStore.edit { prefs ->
                if (prefs[KEY_WAKE_WORD] == null) {
                    prefs[KEY_WAKE_WORD] = true
                    shouldAutoEnable = true
                } else if (prefs[KEY_WAKE_WORD] == true) {
                    shouldAutoEnable = true
                }
            }
            if (shouldAutoEnable) {
                applyWakeWordServiceBinding(true)
            }
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        if (enabled) {
            NovaVoiceForegroundService.start(app)
        }
        viewModelScope.launch {
            app.novaDataStore.edit { prefs ->
                prefs[KEY_WAKE_WORD] = enabled
            }
            applyWakeWordServiceBinding(enabled)
        }
    }

    fun recordWakeCalibrationSample() {
        val app = getApplication<Application>()
        if (_uiState.value.processHealth.isCalibratingWakeVoice) return
        NovaVoiceForegroundService.start(app)
        if (!isVoiceServiceBound) {
            runCatching {
                app.bindService(
                    Intent(app, NovaVoiceForegroundService::class.java),
                    fgsConnection,
                    Context.BIND_AUTO_CREATE
                )
            }
        }
        viewModelScope.launch {
            var waited = 0L
            while (voiceService == null && waited < 1_500L) {
                kotlinx.coroutines.delay(100L)
                waited += 100L
            }
            val svc = voiceService
            if (svc == null) {
                showStatusToast("Voice service is warming up — tap Record Sample again in a moment.")
                return@launch
            }
            _uiState.update { cur ->
                cur.copy(processHealth = cur.processHealth.copy(isCalibratingWakeVoice = true))
            }
            showStatusToast("Listening... Say \"Hey Nova\" clearly in your normal voice.")
            svc.captureWakeCalibrationSample(
                onComplete = { sampleCount, learnedAliases, heardPrimary ->
                    _uiState.update { cur ->
                        cur.copy(
                            processHealth = cur.processHealth.copy(
                                isCalibratingWakeVoice = false,
                                wakeCalibrationSamples = sampleCount,
                                wakeCalibrationAliases = learnedAliases
                            )
                        )
                    }
                    val progressText = if (sampleCount >= 3) {
                        "Voice calibration complete (3/3)! Heard \"$heardPrimary\"."
                    } else {
                        "Sample $sampleCount/3 saved (heard \"$heardPrimary\"). Tap again for ${3 - sampleCount} more."
                    }
                    showStatusToast(progressText)
                },
                onError = { message ->
                    _uiState.update { cur ->
                        cur.copy(processHealth = cur.processHealth.copy(isCalibratingWakeVoice = false))
                    }
                    showStatusToast(message)
                }
            )
        }
    }

    fun resetWakeVoiceCalibration() {
        voiceService?.clearWakeVoiceCalibration()
        _uiState.update { cur ->
            cur.copy(
                processHealth = cur.processHealth.copy(
                    isCalibratingWakeVoice = false,
                    wakeCalibrationSamples = 0,
                    wakeCalibrationAliases = emptyList()
                )
            )
        }
        showStatusToast("Cleared personalized 'Hey Nova' voice calibration.")
    }

    fun setProactiveNotificationPrompts(enabled: Boolean) {
        voiceService?.setProactiveNotificationPromptsEnabled(enabled)
        viewModelScope.launch {
            getApplication<Application>().novaDataStore.edit { prefs ->
                prefs[KEY_PROACTIVE_NOTIF_PROMPTS] = enabled
            }
            if (!enabled) {
                _uiState.update { it.copy(activeNotificationPrompt = null) }
            }
        }
    }

    fun dismissNotificationPrompt() {
        _uiState.update { it.copy(activeNotificationPrompt = null) }
    }

    fun setHiddenReadmeVisible(visible: Boolean) {
        if (visible && _uiState.value.installedAppsForRoutines.isEmpty()) {
            val apps = runCatching {
                AppCapabilityRegistry.discoverInstalledApps(getApplication()).take(60)
            }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    showHiddenReadme = true,
                    installedAppsForRoutines = apps
                )
            }
        } else {
            _uiState.update { it.copy(showHiddenReadme = visible) }
        }
    }

    fun readActiveNotificationAloud() {
        val alert = _uiState.value.activeNotificationPrompt ?: return
        ftsHelper.markNotificationsRead(listOf(alert.id))
        val unreadNow = ftsHelper.countUnreadNotifications()
        _uiState.update { cur ->
            cur.copy(
                processHealth = cur.processHealth.copy(unreadMessagesCount = unreadNow),
                voiceState = cur.voiceState.copy(isSpeakingTts = true)
            )
        }
        val spoke = dispatchTtsSpeech(alert.spokenPrompt) {
            _uiState.update { cur ->
                cur.copy(voiceState = cur.voiceState.copy(isSpeakingTts = false))
            }
        }
        if (!spoke) {
            _uiState.update { cur ->
                cur.copy(voiceState = cur.voiceState.copy(isSpeakingTts = false))
            }
        }
    }

    fun actOnNotificationPrompt(alert: ImportantNotificationAlert) {
        ftsHelper.markNotificationsRead(listOf(alert.id))
        val unreadNow = ftsHelper.countUnreadNotifications()
        _uiState.update {
            it.copy(
                activeNotificationPrompt = null,
                processHealth = it.processHealth.copy(unreadMessagesCount = unreadNow)
            )
        }
        when (alert.type) {
            ImportantNotificationType.MISSED_CALL -> {
                val caller = alert.title
                    .replace(Regex("missed call(?: from)?", RegexOption.IGNORE_CASE), "")
                    .trim()
                    .ifEmpty { alert.body }
                if (caller.isNotBlank()) {
                    submitCommand("call $caller", speakResponse = true)
                } else {
                    NovaNotificationListenerService.openNotificationAlert(getApplication(), alert)
                }
            }
            ImportantNotificationType.MESSAGE,
            ImportantNotificationType.REMINDER,
            ImportantNotificationType.GENERAL -> {
                NovaNotificationListenerService.openNotificationAlert(getApplication(), alert)
            }
        }
    }

    fun setPreferNpuBackend(preferNpu: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().novaDataStore.edit { prefs ->
                prefs[KEY_PREFER_NPU] = preferNpu
            }
            if (modelManager.isModelPresent(ModelSlot.LLM)) {
                llmBridge.reloadModelInProcessB(preferNpu)
                _uiState.update {
                    it.copy(
                        statusToast = if (preferNpu) "Reloading :llm preferring NPU (fallback GPU -> CPU)..."
                        else "Reloading :llm on GPU (OpenCL)..."
                    )
                }
            }
        }
    }

    fun setThinkingEnabled(enabled: Boolean) {
        _uiState.update { cur ->
            cur.copy(
                processHealth = cur.processHealth.copy(thinkingEnabled = enabled),
                statusToast = if (enabled) {
                    "Thinking mode ON — deeper reasoning enabled"
                } else {
                    "Thinking mode OFF — faster direct responses"
                }
            )
        }
        viewModelScope.launch {
            getApplication<Application>().novaDataStore.edit { prefs ->
                prefs[KEY_THINKING_ENABLED] = enabled
            }
        }
    }

    fun selectCategory(category: HubCategory) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun toggleAmbientStandMode() {
        _uiState.update {
            it.copy(
                processHealth = it.processHealth.copy(
                    ambientStandMode = !it.processHealth.ambientStandMode
                )
            )
        }
    }

    fun refreshOsAccessStatus() {
        val app = getApplication<Application>()
        val accActive = NovaAccessibilityService.instance != null ||
            NovaAccessibilityService.isServiceEnabledInSystem(app)
        val notifActive = NovaNotificationListenerService.isConnected.value ||
            NovaNotificationListenerService.isPermissionGranted(app)
        if (notifActive) {
            NovaNotificationListenerService.requestRebindIfNeeded(app)
        }
        if (_uiState.value.processHealth.wakeWordEnabled) {
            applyWakeWordServiceBinding(true)
        }
        _uiState.update { cur ->
            cur.copy(
                processHealth = cur.processHealth.copy(
                    accessibilityConnected = accActive,
                    notificationListenerConnected = notifActive
                )
            )
        }
    }

    fun setSettingsVisible(visible: Boolean) {
        if (visible) {
            llmBridge.refreshPresenceOnly()
            refreshOsAccessStatus()
            refreshFtsQuery(_uiState.value.ftsSearchQuery)
            refreshAgenticLearningState()
            refreshInstalledModelsAndVoices()
        }
        _uiState.update { it.copy(showSettingsScreen = visible) }
    }

    fun setPauseWakeDuringMedia(enabled: Boolean) {
        pendingPauseWakeTarget = enabled
        _uiState.update { cur ->
            cur.copy(
                processHealth = cur.processHealth.copy(pauseWakeDuringMedia = enabled),
                statusToast = if (enabled) {
                    "Wake word will pause automatically during music playback"
                } else {
                    "Concurrent voice enabled: say 'Hey Nova' or tap mic while music plays"
                }
            )
        }
        voiceService?.setPauseWakeDuringMedia(enabled)
        viewModelScope.launch {
            getApplication<Application>().novaDataStore.edit { prefs ->
                prefs[KEY_PAUSE_WAKE_DURING_MEDIA] = enabled
            }
        }
    }

    fun selectAsrEngine(option: AsrEngineOption) {
        _uiState.update { cur ->
            cur.copy(
                processHealth = cur.processHealth.copy(asrEngine = option),
                statusToast = "ASR Engine: ${option.displayName}"
            )
        }
        voiceService?.setAsrEngineOption(option)
        viewModelScope.launch {
            getApplication<Application>().novaDataStore.edit { prefs ->
                prefs[KEY_ASR_ENGINE] = option.id
            }
        }
    }

    fun selectTtsEngine(option: TtsEngineOption) {
        _uiState.update { cur ->
            cur.copy(
                processHealth = cur.processHealth.copy(ttsEngine = option),
                statusToast = "TTS Mode: ${option.displayName}"
            )
        }
        viewModelScope.launch {
            getApplication<Application>().novaDataStore.edit { prefs ->
                prefs[KEY_TTS_ENGINE] = option.id
            }
        }
    }

    fun selectSystemTtsEngine(packageName: String) {
        val clean = packageName.trim()
        val health = _uiState.value.processHealth
        _uiState.update { cur ->
            cur.copy(
                processHealth = cur.processHealth.copy(
                    selectedSystemTtsEnginePkg = clean,
                    selectedSystemTtsVoiceName = ""
                ),
                statusToast = if (clean.isEmpty()) "Using Android default TTS engine" else "Switched TTS engine to $clean"
            )
        }
        voiceService?.configureTts(
            enginePackage = clean,
            voiceName = "",
            speechRate = health.ttsSpeechRate,
            pitch = health.ttsPitch
        )
        viewModelScope.launch {
            getApplication<Application>().novaDataStore.edit { prefs ->
                prefs[KEY_SYSTEM_TTS_ENGINE_PKG] = clean
                prefs[KEY_SYSTEM_TTS_VOICE_NAME] = ""
            }
            kotlinx.coroutines.delay(500L)
            refreshInstalledModelsAndVoices()
        }
    }

    fun selectSystemTtsVoice(voiceName: String) {
        val clean = voiceName.trim()
        val health = _uiState.value.processHealth
        _uiState.update { cur ->
            cur.copy(
                processHealth = cur.processHealth.copy(selectedSystemTtsVoiceName = clean),
                statusToast = if (clean.isEmpty()) "Using default voice" else "Selected voice: $clean"
            )
        }
        voiceService?.configureTts(
            enginePackage = health.selectedSystemTtsEnginePkg,
            voiceName = clean,
            speechRate = health.ttsSpeechRate,
            pitch = health.ttsPitch
        )
        viewModelScope.launch {
            getApplication<Application>().novaDataStore.edit { prefs ->
                prefs[KEY_SYSTEM_TTS_VOICE_NAME] = clean
            }
        }
    }

    fun setTtsSpeechRate(rate: Float) {
        val clamped = rate.coerceIn(0.6f, 1.8f)
        val health = _uiState.value.processHealth
        _uiState.update { cur ->
            cur.copy(processHealth = cur.processHealth.copy(ttsSpeechRate = clamped))
        }
        voiceService?.configureTts(
            enginePackage = health.selectedSystemTtsEnginePkg,
            voiceName = health.selectedSystemTtsVoiceName,
            speechRate = clamped,
            pitch = health.ttsPitch
        )
        viewModelScope.launch {
            getApplication<Application>().novaDataStore.edit { prefs ->
                prefs[KEY_TTS_SPEECH_RATE] = clamped
            }
        }
    }

    fun setTtsPitch(pitch: Float) {
        val clamped = pitch.coerceIn(0.6f, 1.5f)
        val health = _uiState.value.processHealth
        _uiState.update { cur ->
            cur.copy(processHealth = cur.processHealth.copy(ttsPitch = clamped))
        }
        voiceService?.configureTts(
            enginePackage = health.selectedSystemTtsEnginePkg,
            voiceName = health.selectedSystemTtsVoiceName,
            speechRate = health.ttsSpeechRate,
            pitch = clamped
        )
        viewModelScope.launch {
            getApplication<Application>().novaDataStore.edit { prefs ->
                prefs[KEY_TTS_PITCH] = clamped
            }
        }
    }

    fun testSpeakVoice() {
        val spoke = voiceService?.speakTestSample(
            "Hi! I'm Nova. Your voice and music playback settings are active."
        ) == true
        if (!spoke) {
            _uiState.update { it.copy(statusToast = "TTS engine is initializing — try Test Voice again in a moment.") }
        }
    }

    fun refreshInstalledModelsAndVoices() {
        val installed = ModelSlot.entries.associateWith { modelManager.listInstalledModels(it) }
        val embFile = modelManager.getActiveModelFile(ModelSlot.EMBEDDING)
        val asrFile = modelManager.getActiveModelFile(ModelSlot.ASR)
        val ttsFile = modelManager.getActiveModelFile(ModelSlot.TTS)
        val engines = voiceService?.getAvailableSystemTtsEngines().orEmpty()
        val voices = voiceService?.getAvailableSystemTtsVoices().orEmpty()
        _uiState.update { cur ->
            cur.copy(
                installedModelsBySlot = installed,
                availableTtsEngines = engines,
                availableTtsVoices = voices,
                processHealth = cur.processHealth.copy(
                    embeddingPresent = embFile != null,
                    activeEmbeddingName = embFile?.name,
                    activeEmbeddingSizeMb = modelManager.getActiveModelSizeBytes(ModelSlot.EMBEDDING) / (1024L * 1024L),
                    asrModelPresent = asrFile != null,
                    activeAsrModelName = asrFile?.name,
                    activeAsrModelSizeMb = modelManager.getActiveModelSizeBytes(ModelSlot.ASR) / (1024L * 1024L),
                    ttsModelPresent = ttsFile != null,
                    activeTtsModelName = ttsFile?.name,
                    activeTtsModelSizeMb = modelManager.getActiveModelSizeBytes(ModelSlot.TTS) / (1024L * 1024L)
                )
            )
        }
    }

    fun setAssistantPersonality(personality: AssistantPersonality) {
        _uiState.update { cur ->
            cur.copy(
                assistantPersonality = personality,
                processHealth = cur.processHealth.copy(assistantPersonality = personality),
                statusToast = "Personality: ${personality.displayName}"
            )
        }
        viewModelScope.launch {
            getApplication<Application>().novaDataStore.edit { prefs ->
                prefs[KEY_PERSONALITY] = personality.name
            }
        }
        refreshAgenticLearningState()
    }

    fun refreshAgenticLearningState() {
        val memories = ftsHelper.getAgenticMemories(limit = 20)
        val processes = ftsHelper.getLearnedProcesses(limit = 12)
        val (memCount, procCount) = ftsHelper.getAgenticMemoryCounts()
        _uiState.update { cur ->
            cur.copy(
                agenticMemories = memories,
                learnedProcesses = processes,
                processHealth = cur.processHealth.copy(
                    agenticMemoryCount = memCount,
                    learnedProcessCount = procCount
                )
            )
        }
    }

    fun deleteAgenticMemory(memoryId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ftsHelper.deleteAgenticMemory(memoryId)
            refreshAgenticLearningState()
            _uiState.update { it.copy(statusToast = "Removed learned memory") }
        }
    }

    fun deleteLearnedProcess(processId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            ftsHelper.deleteLearnedProcess(processId)
            refreshAgenticLearningState()
            _uiState.update { it.copy(statusToast = "Removed learned process") }
        }
    }

    fun deleteLearnedProcessStep(processId: String, stepIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            ftsHelper.deleteLearnedProcessStep(processId, stepIndex)
            refreshAgenticLearningState()
            _uiState.update { it.copy(statusToast = "Removed step from learned process") }
        }
    }

    fun clearAllAgenticLearning() {
        viewModelScope.launch(Dispatchers.IO) {
            ftsHelper.clearAllAgenticLearning()
            refreshAgenticLearningState()
            _uiState.update { it.copy(statusToast = "Cleared all agentic learning & habits") }
        }
    }

    fun runLearnedProcess(process: LearnedProcessRecord) {
        viewModelScope.launch {
            val outcome = router.executeLearnedProcessSteps(process)
            refreshAgenticLearningState()
            _uiState.update { it.copy(statusToast = outcome.responseText) }
        }
    }

    fun updateUserName(newName: String) {
        val clean = newName.trim().uppercase().take(24).ifEmpty { "NOVA USER" }
        _uiState.update { it.copy(userName = clean) }
        viewModelScope.launch {
            getApplication<Application>().novaDataStore.edit { prefs ->
                prefs[KEY_USER_NAME] = clean
            }
        }
    }

    fun saveDeviceAccount(rawAccount: String) {
        val clean = accountHelper.recordAccount(rawAccount)
        if (clean != null) {
            _uiState.update {
                it.copy(
                    recordedAccount = clean,
                    statusToast = "Saved active device account: $clean"
                )
            }
        } else {
            accountHelper.clearRecordedAccount()
            _uiState.update {
                it.copy(
                    recordedAccount = null,
                    statusToast = "Cleared saved device account"
                )
            }
        }
    }

    fun detectDeviceAccount() {
        val detected = accountHelper.getOrDetectSystemAccount()
        _uiState.update {
            it.copy(
                recordedAccount = detected,
                statusToast = if (detected != null) "Detected account: $detected"
                else "No device account found. Enter one manually or allow Accounts access."
            )
        }
    }

    fun startModelDownload(slot: ModelSlot, url: String, fileName: String, bearerToken: String = "") {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val result = modelManager.downloadModel(slot, url, fileName, bearerToken)
            result.onSuccess { file ->
                onModelInstalledOrChanged(slot, file.name, isImport = false)
            }.onFailure { ex ->
                _uiState.update {
                    it.copy(statusToast = ex.message ?: "Model download failed")
                }
            }
        }
    }

    fun cancelModelDownload() {
        modelManager.cancelDownload()
        downloadJob?.cancel()
    }

    fun importLocalModel(slot: ModelSlot, uri: Uri, displayName: String?) {
        viewModelScope.launch {
            val result = modelManager.importLocalModel(slot, uri, displayName)
            result.onSuccess { file ->
                onModelInstalledOrChanged(slot, file.name, isImport = true)
            }.onFailure { ex ->
                _uiState.update {
                    it.copy(statusToast = ex.message ?: "Model import failed")
                }
            }
        }
    }

    private suspend fun onModelInstalledOrChanged(slot: ModelSlot, fileName: String, isImport: Boolean) {
        val verb = if (isImport) "Imported" else "Downloaded"
        refreshInstalledModelsAndVoices()
        when (slot) {
            ModelSlot.LLM -> {
                llmBridge.reloadModelInProcessB(_uiState.value.processHealth.preferNpuBackend)
                _uiState.update {
                    it.copy(statusToast = "$verb $fileName. Initializing Gemma 4 :llm...")
                }
            }
            ModelSlot.EMBEDDING -> {
                SemanticLookupEngine.invalidateNeuralCache()
                val ok = embeddingRuntime.warmup()
                if (ok) {
                    viewModelScope.launch(Dispatchers.IO) {
                        SemanticLookupEngine.warmPrototypes(embeddingRuntime)
                        ftsHelper.backfillMissingEmbeddings(embeddingRuntime)
                    }
                }
                _uiState.update {
                    it.copy(
                        statusToast = if (ok) "$verb & loaded EmbeddingGemma ($fileName)."
                        else "$verb $fileName. Warmup: ${embeddingRuntime.lastError.value ?: "pending"}"
                    )
                }
            }
            ModelSlot.ASR -> {
                selectAsrEngine(AsrEngineOption.LOCAL_ASR_MODEL)
                _uiState.update {
                    it.copy(statusToast = "$verb ASR model '$fileName' and set active.")
                }
            }
            ModelSlot.TTS -> {
                val lower = fileName.lowercase()
                val preferredMode = when {
                    lower.contains("kokoro") -> TtsEngineOption.KOKORO_ONNX
                    lower.contains("piper") || lower.contains("vits") -> TtsEngineOption.PIPER_VITS
                    else -> TtsEngineOption.LOCAL_TTS_BUNDLE
                }
                selectTtsEngine(preferredMode)
                _uiState.update {
                    it.copy(statusToast = "$verb TTS model '$fileName' and set active.")
                }
            }
        }
    }

    fun selectInstalledModel(slot: ModelSlot, fileName: String) {
        viewModelScope.launch {
            if (!modelManager.setActiveModel(slot, fileName)) {
                _uiState.update { it.copy(statusToast = "Could not activate $fileName") }
                return@launch
            }
            refreshInstalledModelsAndVoices()
            when (slot) {
                ModelSlot.LLM -> {
                    llmBridge.reloadModelInProcessB(_uiState.value.processHealth.preferNpuBackend)
                    _uiState.update { it.copy(statusToast = "Switched LLM to $fileName") }
                }
                ModelSlot.EMBEDDING -> {
                    SemanticLookupEngine.invalidateNeuralCache()
                    embeddingRuntime.warmup()
                    _uiState.update { it.copy(statusToast = "Switched Embedding model to $fileName") }
                }
                ModelSlot.ASR -> {
                    selectAsrEngine(AsrEngineOption.LOCAL_ASR_MODEL)
                    _uiState.update { it.copy(statusToast = "Active ASR model: $fileName") }
                }
                ModelSlot.TTS -> {
                    val lower = fileName.lowercase()
                    val mode = when {
                        lower.contains("kokoro") -> TtsEngineOption.KOKORO_ONNX
                        lower.contains("piper") || lower.contains("vits") -> TtsEngineOption.PIPER_VITS
                        else -> TtsEngineOption.LOCAL_TTS_BUNDLE
                    }
                    selectTtsEngine(mode)
                    _uiState.update { it.copy(statusToast = "Active TTS model: $fileName") }
                }
            }
        }
    }

    fun deleteSpecificModel(slot: ModelSlot, fileName: String) {
        viewModelScope.launch {
            val wasActive = modelManager.getActiveModelFile(slot)?.name == fileName
            modelManager.deleteModel(slot, fileName)
            if (wasActive && slot == ModelSlot.LLM) {
                llmBridge.unloadModelInProcessB()
                llmBridge.refreshPresenceOnly()
            } else if (wasActive && slot == ModelSlot.EMBEDDING) {
                embeddingRuntime.unload()
                SemanticLookupEngine.invalidateNeuralCache()
            }
            refreshInstalledModelsAndVoices()
            _uiState.update { it.copy(statusToast = "Removed $fileName") }
        }
    }

    fun deleteInstalledModel(slot: ModelSlot) {
        viewModelScope.launch {
            when (slot) {
                ModelSlot.LLM -> {
                    llmBridge.unloadModelInProcessB()
                    modelManager.deleteActiveModel(ModelSlot.LLM)
                    llmBridge.refreshPresenceOnly()
                    _uiState.update { it.copy(statusToast = "Gemma 4 E2B model removed.") }
                }
                ModelSlot.EMBEDDING -> {
                    embeddingRuntime.unload()
                    SemanticLookupEngine.invalidateNeuralCache()
                    modelManager.deleteActiveModel(ModelSlot.EMBEDDING)
                    _uiState.update { it.copy(statusToast = "EmbeddingGemma 2 model removed.") }
                }
                ModelSlot.ASR -> {
                    modelManager.deleteActiveModel(ModelSlot.ASR)
                    _uiState.update { it.copy(statusToast = "Installed ASR models removed.") }
                }
                ModelSlot.TTS -> {
                    modelManager.deleteActiveModel(ModelSlot.TTS)
                    _uiState.update { it.copy(statusToast = "Installed TTS models removed.") }
                }
            }
            refreshInstalledModelsAndVoices()
        }
    }

    fun reloadModel(slot: ModelSlot) {
        when (slot) {
            ModelSlot.LLM -> llmBridge.reloadModelInProcessB(_uiState.value.processHealth.preferNpuBackend)
            ModelSlot.EMBEDDING -> {
                viewModelScope.launch(Dispatchers.IO) {
                    SemanticLookupEngine.invalidateNeuralCache()
                    if (embeddingRuntime.warmup()) {
                        SemanticLookupEngine.warmPrototypes(embeddingRuntime)
                        ftsHelper.backfillMissingEmbeddings(embeddingRuntime)
                    }
                }
            }
            ModelSlot.ASR, ModelSlot.TTS -> {
                refreshInstalledModelsAndVoices()
                _uiState.update { it.copy(statusToast = "Refreshed ${slot.name} model list.") }
            }
        }
    }

    fun syncInstalledAppsToFts() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = com.example.engine.AppCapabilityRegistry.syncInstalledAppsWithCapabilities(
                getApplication(),
                ftsHelper,
                embeddingRuntime
            )
            refreshFtsQuery(_uiState.value.ftsSearchQuery)
            _uiState.update {
                it.copy(statusToast = "Indexed $count installed apps & capabilities into ${ftsHelper.activeFtsMode}")
            }
        }
    }

    fun syncContactsToFts() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = ftsHelper.syncContactsWithEmbeddings(getApplication(), embeddingRuntime)
            refreshFtsQuery(_uiState.value.ftsSearchQuery)
            _uiState.update {
                it.copy(
                    statusToast = if (count >= 0) "Indexed $count contacts into ${ftsHelper.activeFtsMode}"
                    else "READ_CONTACTS permission required to sync contacts."
                )
            }
        }
    }

    fun syncCalendarToFts() {
        viewModelScope.launch(Dispatchers.IO) {
            val count = ftsHelper.syncCalendarEventsWithEmbeddings(getApplication(), embeddingRuntime)
            refreshFtsQuery(_uiState.value.ftsSearchQuery)
            _uiState.update {
                it.copy(
                    statusToast = if (count >= 0) "Indexed $count calendar events into ${ftsHelper.activeFtsMode}"
                    else "READ_CALENDAR permission required to sync calendar."
                )
            }
        }
    }

    fun indexDocumentUriToFts(uri: Uri, displayName: String?) {
        viewModelScope.launch {
            val previewDoc = ftsHelper.indexDocumentFromUri(getApplication(), uri, displayName)
            if (previewDoc != null) {
                val vec = embeddingRuntime.embedDocument(previewDoc.title, previewDoc.body)
                if (vec != null) {
                    ftsHelper.upsertDocument(
                        previewDoc.docId,
                        previewDoc.category,
                        previewDoc.title,
                        previewDoc.body,
                        previewDoc.uriOrRef,
                        vec
                    )
                }
                refreshFtsQuery(_uiState.value.ftsSearchQuery)
                _uiState.update {
                    it.copy(statusToast = "Indexed '${previewDoc.title}' into ${ftsHelper.activeFtsMode}")
                }
            }
        }
    }

    fun selectDetailControl(controlId: String) {
        _uiState.update { it.copy(activeDetailControlId = controlId) }
    }

    fun toggleControl(controlId: String) {
        val item = _uiState.value.controls.firstOrNull { it.id == controlId } ?: return
        val nextEnabled = !item.isEnabled
        if (controlId == "ctrl_flashlight" || controlId == "ctrl_wifi" || controlId == "ctrl_bt" || controlId == "ctrl_dnd") {
            val feature = when (controlId) {
                "ctrl_flashlight" -> "flashlight"
                "ctrl_wifi" -> "wifi"
                "ctrl_bt" -> "bluetooth"
                else -> "dnd"
            }
            submitCommand("${if (nextEnabled) "turn on" else "turn off"} $feature", speakResponse = false)
        } else {
            applyControlPatch(controlId, nextEnabled, null, null)
        }
    }

    fun updateControlValue(controlId: String, newValue: Float) {
        val item = _uiState.value.controls.firstOrNull { it.id == controlId } ?: return
        val clamped = newValue.coerceIn(item.minValue, item.maxValue)
        if (controlId == "ctrl_media") {
            submitCommand("set volume to ${clamped.toInt()}%", speakResponse = false)
        } else {
            applyControlPatch(controlId, true, clamped, null)
        }
    }

    fun updateControlMode(controlId: String, mode: String) {
        if (controlId == "ctrl_media") {
            val cmd = mode.lowercase()
            if (cmd in setOf("play", "pause", "next", "previous")) {
                submitCommand(cmd, speakResponse = false)
                return
            }
        }
        applyControlPatch(controlId, true, null, mode)
    }

    fun updateControlIntensity(controlId: String, intensity: String) {
        _uiState.update { state ->
            state.copy(
                controls = state.controls.map { item ->
                    if (item.id == controlId) {
                        val adjustedValue = when {
                            controlId == "ctrl_timer" && intensity == "15m" -> 15f
                            controlId == "ctrl_timer" && intensity == "25m" -> 25f
                            controlId == "ctrl_timer" && intensity == "45m" -> 45f
                            else -> item.primaryValue
                        }
                        item.copy(selectedIntensity = intensity, primaryValue = adjustedValue)
                    } else item
                }
            )
        }
    }

    private fun applyControlPatch(id: String, enabled: Boolean?, value: Float?, mode: String?) {
        _uiState.update { state ->
            state.copy(
                controls = state.controls.map { item ->
                    if (item.id != id) item
                    else {
                        val e = enabled ?: item.isEnabled
                        val v = value ?: item.primaryValue
                        val m = mode ?: item.selectedMode
                        val sub = buildSubtitle(item.id, e, v, m)
                        item.copy(isEnabled = e, primaryValue = v, selectedMode = m, subtitle = sub)
                    }
                }
            )
        }
    }

    private fun buildSubtitle(id: String, enabled: Boolean, value: Float, mode: String): String {
        return when (id) {
            "ctrl_timer" -> if (enabled) "${value.toInt()}:00 • $mode (Active)" else "${value.toInt()}:00 • $mode"
            "ctrl_media" -> if (enabled) "${value.toInt()}% • $mode" else "Muted"
            "ctrl_flashlight" -> if (enabled) "Hardware Torch • On" else "Hardware Torch • Off"
            "ctrl_dnd" -> if (enabled) "Silenced • Priority Only" else "Local Quiet State"
            "ctrl_wifi" -> "System Connectivity"
            "ctrl_bt" -> "Device Settings"
            else -> if (enabled) "Active" else "Standby"
        }
    }

    fun setVoiceListening(listening: Boolean, amplitude: Float = 0.4f) {
        if (listening) {
            voiceService?.beginConcurrentMicSession()
            voiceService?.setOverlayListeningPaused(true)
            voiceService?.stopSpeakingImmediate()
            llmBridge.sendBargeInAbort()
            _uiState.update { state ->
                state.copy(
                    voiceState = state.voiceState.copy(
                        isListening = true,
                        statusHeader = "LISTENING...",
                        orbMode = OrbVisualMode.LISTENING,
                        amplitude = amplitude,
                        isSpeakingTts = false
                    )
                )
            }
        } else {
            val cur = _uiState.value.voiceState
            val busyWithTurnOrTts = activeTurnJob?.isActive == true ||
                cur.isSpeakingTts ||
                cur.isAwaitingFollowUp ||
                (voiceService?.isCurrentlySpeaking() == true)
            if (!busyWithTurnOrTts) {
                voiceService?.setOverlayListeningPaused(false)
                voiceService?.endConcurrentMicSession(resumeInterruptedMediaIfNeeded = false)
            }
            _uiState.update { state ->
                val vs = state.voiceState
                val keepCurrentStatus = activeTurnJob?.isActive == true ||
                    vs.isSpeakingTts ||
                    vs.isAwaitingFollowUp
                state.copy(
                    voiceState = vs.copy(
                        isListening = false,
                        statusHeader = if (keepCurrentStatus) vs.statusHeader else "SAY SOMETHING",
                        orbMode = if (keepCurrentStatus) vs.orbMode else OrbVisualMode.IDLE,
                        amplitude = amplitude
                    )
                )
            }
        }
    }

    fun updateLiveTranscript(partial: String, rmsAmplitude: Float = 0.5f) {
        _uiState.update { state ->
            state.copy(
                voiceState = state.voiceState.copy(
                    transcript = partial,
                    amplitude = rmsAmplitude.coerceIn(0.15f, 1.0f)
                )
            )
        }
    }

    fun updateVoiceAmplitude(rmsAmplitude: Float) {
        _uiState.update { state ->
            state.copy(
                voiceState = state.voiceState.copy(
                    amplitude = rmsAmplitude.coerceIn(0.15f, 1.0f)
                )
            )
        }
    }

    fun toggleKeyboardInput() {
        _uiState.update { state ->
            state.copy(
                voiceState = state.voiceState.copy(
                    showKeyboardInput = !state.voiceState.showKeyboardInput
                )
            )
        }
    }

    fun submitCommand(rawQuery: String, speakResponse: Boolean = true) {
        val query = rawQuery.trim()
        if (query.isEmpty()) return

        if (query.equals("open settings", ignoreCase = true)) {
            setSettingsVisible(true)
            return
        }
        if (query.equals("sync installed apps", ignoreCase = true)) {
            syncInstalledAppsToFts()
            return
        }

        val activeAlert = _uiState.value.activeNotificationPrompt
        if (activeAlert != null) {
            val lower = query.lowercase()
            when {
                lower == "read it" || lower == "read notification" -> {
                    readActiveNotificationAloud()
                    return
                }
                lower == "open it" || lower == "open notification" -> {
                    actOnNotificationPrompt(activeAlert)
                    return
                }
                lower == "call back" && activeAlert.type == ImportantNotificationType.MISSED_CALL -> {
                    actOnNotificationPrompt(activeAlert)
                    return
                }
                lower == "dismiss" || lower == "dismiss notification" || lower == "ignore" -> {
                    dismissNotificationPrompt()
                    return
                }
            }
        }

        val pending = _uiState.value.pendingConfirmation
        if (pending != null) {
            val lower = query.lowercase()
            if (lower.contains("yes") || lower.contains("confirm") || lower.contains("approve") || lower.contains("send it")) {
                approvePendingTierRed()
                return
            } else if (lower.contains("no") || lower.contains("cancel") || lower.contains("stop") || lower.contains("abort")) {
                abortPendingTierRed()
                return
            }
        }

        cancelFollowUpWindow()
        activeTurnJob?.cancel()
        speechGenerationId++
        voiceService?.stopFollowUpCapture()
        voiceService?.setOverlayListeningPaused(true)
        voiceService?.stopSpeakingImmediate()
        NovaAccessibilityService.resetKillSwitch()

        activeTurnJob = viewModelScope.launch {
            val turnSpeechGen = speechGenerationId
            _uiState.update { state ->
                state.copy(
                    voiceState = state.voiceState.copy(
                        isListening = false,
                        transcript = query,
                        statusHeader = "ROUTING...",
                        orbMode = OrbVisualMode.LISTENING,
                        stepPills = emptyList(),
                        assistantResponse = "",
                        requiresModelSetup = false,
                        requiresAccessibilitySetup = false,
                        isAwaitingFollowUp = false,
                        isSpeakingTts = false
                    )
                )
            }

            try {
                val thinkingAllowed = _uiState.value.processHealth.thinkingEnabled
                val outcome = router.routeAndExecute(query, thinkingAllowed = thinkingAllowed) { pills, orbMode, header ->
                    _uiState.update { state ->
                        state.copy(
                            voiceState = state.voiceState.copy(
                                stepPills = pills,
                                orbMode = orbMode,
                                statusHeader = header,
                                amplitude = if (orbMode == OrbVisualMode.THINKING) 0.72f else 0.45f
                            )
                        )
                    }
                }

                val expectsAnswer = NovaPipelineRouter.shouldAwaitFollowUp(
                    outcome = outcome,
                    hasPendingChoices = router.getPendingChoices().size > 1
                )
                val startedMediaPlayback = isMediaPlaybackStartOutcome(outcome, expectsAnswer)
                if (startedMediaPlayback) {
                    voiceService?.notifyMediaPlaybackStarted(graceWindowMs = 5_000L)
                }
                val spokenText = outcome.pendingConfirmation?.summary ?: outcome.responseText
                val shouldSpeakTurn = speakResponse && !startedMediaPlayback && spokenText.isNotBlank()
                val willAttemptTts = shouldSpeakTurn &&
                    (testSpeakDispatcher != null || voiceService != null)

                val (memCountNow, procCountNow) = ftsHelper.getAgenticMemoryCounts()
                val memoriesNow = ftsHelper.getAgenticMemories(limit = 20)
                val processesNow = ftsHelper.getLearnedProcesses(limit = 12)

                _uiState.update { state ->
                    state.copy(
                        pendingConfirmation = outcome.pendingConfirmation,
                        agenticMemories = memoriesNow,
                        learnedProcesses = processesNow,
                        processHealth = state.processHealth.copy(
                            indexedDocCount = ftsHelper.countDocuments(),
                            unreadMessagesCount = ftsHelper.countUnreadNotifications(),
                            agenticMemoryCount = memCountNow,
                            learnedProcessCount = procCountNow
                        ),
                        voiceState = state.voiceState.copy(
                            statusHeader = when {
                                outcome.requiresModelSetup -> "LLM MODEL MISSING"
                                outcome.pendingConfirmation != null -> "CONFIRMATION REQUIRED"
                                startedMediaPlayback ->
                                    if (state.processHealth.pauseWakeDuringMedia) {
                                        "PLAYING MEDIA • MIC PAUSED"
                                    } else {
                                        "PLAYING MEDIA • SAY HEY NOVA OR TAP MIC"
                                    }
                                expectsAnswer && willAttemptTts -> "SPEAKING • TAP OR SAY STOP"
                                expectsAnswer -> "ASKING YOU..."
                                willAttemptTts -> "SPEAKING • TAP OR SAY STOP"
                                else -> outcome.routingTier.badge
                            },
                            orbMode = outcome.orbMode,
                            stepPills = outcome.steps,
                            assistantResponse = outcome.responseText,
                            lastRoutingTier = outcome.routingTier,
                            lastLatencyMs = outcome.latencyMs,
                            isListening = false,
                            isAwaitingFollowUp = false,
                            isSpeakingTts = willAttemptTts && outcome.pendingConfirmation == null,
                            requiresModelSetup = outcome.requiresModelSetup,
                            requiresAccessibilitySetup = outcome.requiresAccessibilitySetup
                        ),
                        statusToast = if (outcome.routingTier == RoutingTier.TIER_1_REGEX || startedMediaPlayback) {
                            "${outcome.routingTier.badge} • ${outcome.responseText}"
                        } else null
                    )
                }

                if (expectsAnswer) {
                    val triggered = java.util.concurrent.atomic.AtomicBoolean(false)
                    fun triggerListenOnce() {
                        if (speechGenerationId == turnSpeechGen && triggered.compareAndSet(false, true)) {
                            followUpFallbackJob?.cancel()
                            startFollowUpListenWindow(speakOnTimeout = speakResponse)
                        }
                    }

                    val spoke = if (shouldSpeakTurn) {
                        dispatchTtsSpeech(spokenText) { triggerListenOnce() }
                    } else {
                        false
                    }

                    if (spoke) {
                        followUpFallbackJob?.cancel()
                        followUpFallbackJob = viewModelScope.launch {
                            // Safety ceiling only in case TTS engine callback hangs; never cut off live TTS early
                            val maxWaitMs = (spokenText.length * 140L + 4000L).coerceIn(8_000L, 45_000L)
                            kotlinx.coroutines.delay(maxWaitMs)
                            var extraWaits = 0
                            while (voiceService?.isCurrentlySpeaking() == true && extraWaits < 30) {
                                kotlinx.coroutines.delay(500L)
                                extraWaits++
                            }
                            triggerListenOnce()
                        }
                    } else {
                        triggerListenOnce()
                    }
                } else if (shouldSpeakTurn) {
                    val spoke = dispatchTtsSpeech(spokenText) {
                        if (speechGenerationId == turnSpeechGen) {
                            onTurnTtsCompleted()
                        }
                    }
                    if (!spoke) {
                        onTurnTtsCompleted()
                    }
                } else {
                    onTurnTtsCompleted()
                }
            } catch (_: CancellationException) {
                if (speechGenerationId == turnSpeechGen) {
                    _uiState.update { cur ->
                        val vs = cur.voiceState
                        val stillWorking = vs.statusHeader in setOf("ROUTING...", "THINKING...", "WORKING...", "CHECKING SCREEN...", "LISTENING...")
                        cur.copy(
                            voiceState = vs.copy(
                                statusHeader = if (stillWorking) "STANDING BY" else vs.statusHeader,
                                orbMode = if (stillWorking) OrbVisualMode.IDLE else vs.orbMode,
                                isListening = false,
                                isAwaitingFollowUp = false,
                                isSpeakingTts = false
                            )
                        )
                    }
                    onTurnTtsCompleted()
                }
            } catch (ex: Exception) {
                if (speechGenerationId == turnSpeechGen) {
                    val safeMsg = ex.message?.takeIf { it.isNotBlank() && it.length < 180 }
                        ?: "Something went wrong while handling that request. Please try again."
                    _uiState.update { cur ->
                        cur.copy(
                            voiceState = cur.voiceState.copy(
                                statusHeader = "TRY AGAIN",
                                orbMode = OrbVisualMode.ERROR_ABORTED,
                                assistantResponse = safeMsg,
                                isListening = false,
                                isAwaitingFollowUp = false,
                                isSpeakingTts = false
                            ),
                            statusToast = safeMsg
                        )
                    }
                    onTurnTtsCompleted()
                }
            }
        }
    }

    private fun dispatchTtsSpeech(text: String, onDone: () -> Unit): Boolean {
        val custom = testSpeakDispatcher
        if (custom != null) {
            return custom.invoke(text, onDone)
        }
        return voiceService?.speak(text, onDone) == true
    }

    private fun resumeWakeWordIfIdle() {
        val vs = _uiState.value.voiceState
        val busyWithAudio = vs.isListening ||
            vs.isAwaitingFollowUp ||
            vs.isSpeakingTts ||
            activeTurnJob?.isActive == true ||
            voiceService?.isCurrentlySpeaking() == true
        if (!busyWithAudio) {
            voiceService?.setOverlayListeningPaused(false)
            voiceService?.ensureWakeLoopActiveIfIdle()
        }
    }

    private fun onTurnTtsCompleted() {
        _uiState.update { cur ->
            val vs = cur.voiceState
            val nextHeader = if (!vs.isAwaitingFollowUp && vs.statusHeader.startsWith("SPEAKING")) {
                vs.lastRoutingTier?.badge ?: "STANDING BY"
            } else {
                vs.statusHeader
            }
            if (!vs.isSpeakingTts && nextHeader == vs.statusHeader) cur
            else cur.copy(
                voiceState = vs.copy(
                    isSpeakingTts = false,
                    statusHeader = nextHeader
                )
            )
        }
        if (!_uiState.value.voiceState.statusHeader.startsWith("PLAYING MEDIA")) {
            voiceService?.endConcurrentMicSession(resumeInterruptedMediaIfNeeded = true)
        }
        resumeWakeWordIfIdle()
    }

    internal fun setFollowUpTimeoutForTest(timeoutMs: Long) {
        followUpListenWindowMs = timeoutMs.coerceAtLeast(10L)
    }

    fun setAppInForeground(inForeground: Boolean) {
        isAppInForeground = inForeground
        if (!inForeground) {
            val vs = _uiState.value.voiceState
            if (vs.isAwaitingFollowUp && !vs.isSpeakingTts && !vs.useServiceFollowUpMic) {
                _uiState.update { cur ->
                    cur.copy(voiceState = cur.voiceState.copy(useServiceFollowUpMic = true))
                }
                startServiceFollowUpCapture()
            }
        } else {
            resumeWakeWordIfIdle()
        }
    }

    fun setVoiceOverlayOpen(open: Boolean) {
        isVoiceOverlayOpen = open
        val vs = _uiState.value.voiceState
        if (open) {
            val busyWithAudio = vs.isListening || vs.isAwaitingFollowUp || vs.isSpeakingTts || activeTurnJob?.isActive == true
            if (busyWithAudio) {
                voiceService?.setOverlayListeningPaused(true)
            } else {
                // Keep ambient "Hey Nova" wake-word listening active even when overlay is standing by
                resumeWakeWordIfIdle()
            }
        } else {
            if (vs.isAwaitingFollowUp && !vs.isSpeakingTts && !vs.useServiceFollowUpMic) {
                _uiState.update { cur ->
                    cur.copy(voiceState = cur.voiceState.copy(useServiceFollowUpMic = true))
                }
                startServiceFollowUpCapture()
            } else {
                resumeWakeWordIfIdle()
            }
        }
    }

    fun requestServiceFollowUpFallback() {
        val vs = _uiState.value.voiceState
        if (!vs.isAwaitingFollowUp || vs.isSpeakingTts || vs.useServiceFollowUpMic) return
        _uiState.update { cur ->
            cur.copy(voiceState = cur.voiceState.copy(useServiceFollowUpMic = true))
        }
        startServiceFollowUpCapture()
    }

    fun requestServiceOneShotMicCapture() {
        val svc = voiceService
        if (svc == null) {
            _uiState.update { cur ->
                cur.copy(
                    voiceState = cur.voiceState.copy(
                        isListening = false,
                        orbMode = OrbVisualMode.IDLE,
                        statusHeader = "TAP MIC OR TYPE REQUEST",
                        showKeyboardInput = true
                    )
                )
            }
            return
        }
        svc.beginConcurrentMicSession()
        svc.setOverlayListeningPaused(true)
        svc.stopSpeakingImmediate()
        _uiState.update { cur ->
            cur.copy(
                voiceState = cur.voiceState.copy(
                    isListening = true,
                    orbMode = OrbVisualMode.LISTENING,
                    statusHeader = "LISTENING...",
                    isSpeakingTts = false
                )
            )
        }
        svc.startOneShotCommandCapture(
            onReady = {
                _uiState.update { cur ->
                    cur.copy(
                        voiceState = cur.voiceState.copy(
                            isListening = true,
                            orbMode = OrbVisualMode.LISTENING,
                            statusHeader = "LISTENING...",
                            amplitude = 0.35f
                        )
                    )
                }
            },
            onPartial = { partial -> updateLiveTranscript(partial, 0.6f) },
            onRms = { rms -> updateVoiceAmplitude(rms) },
            onResult = { spoken ->
                submitCommand(spoken, speakResponse = true)
            },
            onErrorFallback = { msg ->
                svc.endConcurrentMicSession(resumeInterruptedMediaIfNeeded = true)
                _uiState.update { cur ->
                    cur.copy(
                        voiceState = cur.voiceState.copy(
                            isListening = false,
                            orbMode = OrbVisualMode.IDLE,
                            statusHeader = "TAP MIC OR TYPE REQUEST",
                            showKeyboardInput = true
                        ),
                        statusToast = msg
                    )
                }
                resumeWakeWordIfIdle()
            }
        )
    }

    private fun startServiceFollowUpCapture() {
        val custom = testServiceFollowUpDispatcher
        if (custom != null) {
            custom.invoke(followUpListenWindowMs) { spoken ->
                if (spoken.isNotBlank()) submitCommand(spoken, speakResponse = true)
            }
            return
        }
        voiceService?.startFollowUpCapture(
            silenceTimeoutMs = followUpListenWindowMs,
            onReady = {
                _uiState.update { cur ->
                    if (!cur.voiceState.isAwaitingFollowUp) return@update cur
                    cur.copy(
                        voiceState = cur.voiceState.copy(
                            isListening = true,
                            orbMode = OrbVisualMode.LISTENING,
                            amplitude = 0.35f
                        )
                    )
                }
            },
            onPartial = { partial ->
                if (_uiState.value.voiceState.isAwaitingFollowUp) {
                    onFollowUpSpeechStarted()
                    updateLiveTranscript(partial, 0.6f)
                }
            },
            onRms = { rms ->
                updateVoiceAmplitude(rms)
            },
            onResult = { spoken ->
                submitCommand(spoken, speakResponse = true)
            },
            onNoMatchOrTimeout = {
                onFollowUpSpeechNoMatch()
            },
            onErrorFallback = { msg ->
                handleFollowUpMicErrorFallback(msg)
            }
        )
    }

    fun handleFollowUpMicErrorFallback(msg: String) {
        followUpFallbackJob?.cancel()
        followUpJob?.cancel()
        voiceService?.stopFollowUpCapture()
        _uiState.update { cur ->
            cur.copy(
                voiceState = cur.voiceState.copy(
                    isListening = false,
                    isAwaitingFollowUp = false,
                    useServiceFollowUpMic = false,
                    followUpCancelTrigger = System.currentTimeMillis(),
                    orbMode = OrbVisualMode.IDLE,
                    statusHeader = "TAP MIC OR TYPE REPLY",
                    showKeyboardInput = true
                ),
                statusToast = msg
            )
        }
        resumeWakeWordIfIdle()
    }

    fun interruptSpeaking(startListening: Boolean = true) {
        speechGenerationId++
        followUpFallbackJob?.cancel()
        followUpJob?.cancel()
        activeTurnJob?.cancel()
        voiceService?.stopFollowUpCapture()
        voiceService?.stopSpeakingImmediate()
        llmBridge.sendBargeInAbort()
        if (startListening) {
            startFollowUpListenWindow(
                speakOnTimeout = true,
                headerOverride = "INTERRUPTED • LISTENING..."
            )
        } else {
            _uiState.update { cur ->
                cur.copy(
                    voiceState = cur.voiceState.copy(
                        isSpeakingTts = false,
                        isListening = false,
                        isAwaitingFollowUp = false,
                        useServiceFollowUpMic = false,
                        followUpCancelTrigger = System.currentTimeMillis(),
                        orbMode = OrbVisualMode.IDLE,
                        statusHeader = "INTERRUPTED • STANDING BY"
                    )
                )
            }
            resumeWakeWordIfIdle()
        }
    }

    fun handleVoiceBargeInDecision(decision: NovaVoiceForegroundService.BargeInDecision) {
        when (decision.action) {
            NovaVoiceForegroundService.BargeInAction.STOP_ONLY -> interruptSpeaking(startListening = false)
            NovaVoiceForegroundService.BargeInAction.LISTEN_NOW -> interruptSpeaking(startListening = true)
            NovaVoiceForegroundService.BargeInAction.RUN_COMMAND -> {
                if (decision.command.isNotBlank()) {
                    submitCommand(decision.command, speakResponse = true)
                } else {
                    interruptSpeaking(startListening = true)
                }
            }
        }
    }

    fun startFollowUpListenWindow(
        speakOnTimeout: Boolean = true,
        headerOverride: String? = null
    ) {
        followUpFallbackJob?.cancel()
        followUpJob?.cancel()
        val now = System.currentTimeMillis()
        val secs = ((followUpListenWindowMs + 999L) / 1000L).coerceAtLeast(1L)
        val useServiceMic = !(isAppInForeground && isVoiceOverlayOpen)
        val headerPrefix = if (headerOverride?.contains("INTERRUPTED", ignoreCase = true) == true) {
            "INTERRUPTED • LISTENING"
        } else {
            "LISTENING FOR REPLY"
        }
        _uiState.update { cur ->
            cur.copy(
                voiceState = cur.voiceState.copy(
                    isAwaitingFollowUp = true,
                    followUpListenTrigger = now,
                    followUpCancelTrigger = 0L,
                    orbMode = OrbVisualMode.LISTENING,
                    isSpeakingTts = false,
                    useServiceFollowUpMic = useServiceMic,
                    statusHeader = headerOverride ?: "$headerPrefix (${secs}s)..."
                )
            )
        }
        voiceService?.setOverlayListeningPaused(true)
        if (useServiceMic) {
            startServiceFollowUpCapture()
        }
        scheduleFollowUpTimeoutCountdown(speakOnTimeout, headerPrefix)
    }

    private fun scheduleFollowUpTimeoutCountdown(
        speakOnTimeout: Boolean,
        headerPrefix: String = "LISTENING FOR REPLY"
    ) {
        followUpJob?.cancel()
        followUpJob = viewModelScope.launch {
            val totalMs = followUpListenWindowMs
            if (totalMs >= 2000L) {
                var remainingMs = totalMs
                while (remainingMs > 0L) {
                    val secs = ((remainingMs + 999L) / 1000L).coerceAtLeast(1L)
                    _uiState.update { cur ->
                        if (!cur.voiceState.isAwaitingFollowUp) return@update cur
                        cur.copy(
                            voiceState = cur.voiceState.copy(
                                statusHeader = "$headerPrefix (${secs}s)..."
                            )
                        )
                    }
                    val step = remainingMs.coerceAtMost(1000L)
                    kotlinx.coroutines.delay(step)
                    remainingMs -= step
                }
            } else {
                kotlinx.coroutines.delay(totalMs)
            }
            handleFollowUpTimeout(speakOnTimeout)
        }
    }

    private fun handleFollowUpTimeout(speakOnTimeout: Boolean) {
        if (!_uiState.value.voiceState.isAwaitingFollowUp) return
        voiceService?.stopFollowUpCapture()
        val timeoutReply = "Let me know what you need."
        _uiState.update { cur ->
            cur.copy(
                voiceState = cur.voiceState.copy(
                    isListening = false,
                    isAwaitingFollowUp = false,
                    useServiceFollowUpMic = false,
                    followUpCancelTrigger = System.currentTimeMillis(),
                    orbMode = OrbVisualMode.IDLE,
                    statusHeader = "STANDING BY",
                    assistantResponse = timeoutReply,
                    isSpeakingTts = speakOnTimeout
                )
            )
        }
        val spoke = if (speakOnTimeout) {
            dispatchTtsSpeech(timeoutReply) {
                onTurnTtsCompleted()
            }
        } else {
            false
        }
        if (!spoke) {
            _uiState.update { cur ->
                cur.copy(voiceState = cur.voiceState.copy(isSpeakingTts = false))
            }
            resumeWakeWordIfIdle()
        }
    }

    fun onFollowUpSpeechStarted() {
        followUpJob?.cancel()
        followUpFallbackJob?.cancel()
        _uiState.update { cur ->
            if (!cur.voiceState.isAwaitingFollowUp) return@update cur
            cur.copy(
                voiceState = cur.voiceState.copy(
                    statusHeader = "LISTENING..."
                )
            )
        }
    }

    fun onFollowUpSpeechNoMatch() {
        if (!_uiState.value.voiceState.isAwaitingFollowUp) return
        if (followUpJob?.isActive != true) {
            scheduleFollowUpTimeoutCountdown(speakOnTimeout = true)
        }
    }

    fun cancelFollowUpWindow() {
        followUpFallbackJob?.cancel()
        followUpJob?.cancel()
        voiceService?.stopFollowUpCapture()
        _uiState.update { cur ->
            if (!cur.voiceState.isAwaitingFollowUp && !cur.voiceState.useServiceFollowUpMic) return@update cur
            cur.copy(
                voiceState = cur.voiceState.copy(
                    isAwaitingFollowUp = false,
                    useServiceFollowUpMic = false,
                    followUpCancelTrigger = System.currentTimeMillis()
                )
            )
        }
        resumeWakeWordIfIdle()
    }

    fun approvePendingTierRed() {
        val pending = _uiState.value.pendingConfirmation ?: return
        viewModelScope.launch {
            val result = router.confirmTierRedAction(pending)
            _uiState.update { state ->
                val updatedPills = state.voiceState.stepPills.map {
                    if (it.status == StepStatus.BLOCKED) it.copy(status = StepStatus.DONE, label = "Approved [${pending.targetNodeId}]")
                    else it
                }
                state.copy(
                    pendingConfirmation = null,
                    voiceState = state.voiceState.copy(
                        statusHeader = "EXECUTED",
                        orbMode = OrbVisualMode.FAST_PATH_FLASH,
                        stepPills = updatedPills,
                        assistantResponse = result
                    ),
                    statusToast = result
                )
            }
            voiceService?.speak(result)
        }
    }

    fun abortPendingTierRed() {
        NovaAccessibilityService.abortAutomation("User aborted Tier Red action")
        _uiState.update { state ->
            val updatedPills = state.voiceState.stepPills.map {
                if (it.status == StepStatus.BLOCKED) it.copy(status = StepStatus.PENDING, label = "Aborted safely")
                else it
            }
            state.copy(
                pendingConfirmation = null,
                voiceState = state.voiceState.copy(
                    statusHeader = "ABORTED SAFELY",
                    orbMode = OrbVisualMode.ERROR_ABORTED,
                    stepPills = updatedPills,
                    assistantResponse = "Action cancelled safely."
                ),
                statusToast = "Tier Red action aborted safely"
            )
        }
    }

    fun killSwitchAbort() {
        cancelFollowUpWindow()
        activeTurnJob?.cancel()
        voiceService?.stopFollowUpCapture()
        llmBridge.sendBargeInAbort()
        voiceService?.stopSpeakingImmediate()
        NovaAccessibilityService.abortAutomation("Kill switch pressed")
        handleKillSwitchAbort("Stopped by user kill switch")
    }

    private fun handleKillSwitchAbort(reason: String) {
        cancelFollowUpWindow()
        activeTurnJob?.cancel()
        voiceService?.stopFollowUpCapture()
        voiceService?.stopSpeakingImmediate()
        _uiState.update { state ->
            state.copy(
                pendingConfirmation = null,
                voiceState = state.voiceState.copy(
                    isListening = false,
                    isAwaitingFollowUp = false,
                    useServiceFollowUpMic = false,
                    statusHeader = "STOPPED • KILL SWITCH",
                    orbMode = OrbVisualMode.ERROR_ABORTED,
                    assistantResponse = reason,
                    isSpeakingTts = false
                ),
                statusToast = reason
            )
        }
    }

    fun refreshFtsQuery(query: String) {
        viewModelScope.launch {
            val qVec = if (query.isNotBlank()) embeddingRuntime.embedQuery(query) else null
            val results = ftsHelper.hybridSearch(query, qVec, "all", limit = 12)
            _uiState.update {
                it.copy(
                    ftsSearchQuery = query,
                    ftsSearchResults = results,
                    processHealth = it.processHealth.copy(indexedDocCount = ftsHelper.countDocuments())
                )
            }
        }
    }

    fun addLocalMemoryNote(title: String, body: String) {
        if (title.isBlank() || body.isBlank()) return
        viewModelScope.launch {
            val id = "mem_${System.currentTimeMillis()}"
            val cleanTitle = title.trim()
            val cleanBody = body.trim()
            val vec = embeddingRuntime.embedDocument(cleanTitle, cleanBody)
            ftsHelper.upsertDocument(id, "notes", cleanTitle, cleanBody, "memory://$id", vec)
            refreshFtsQuery(_uiState.value.ftsSearchQuery)
        }
    }

    fun openRoutineEditor(routine: RecordedRoutine? = null) {
        val target = routine ?: RecordedRoutine(
            name = "My Routine ${_uiState.value.recordedRoutines.size + 1}",
            description = "Custom automated routine",
            scope = RoutineScope.GLOBAL
        )
        val apps = _uiState.value.installedAppsForRoutines.ifEmpty {
            AppCapabilityRegistry.discoverInstalledApps(getApplication()).take(40)
        }
        _uiState.update {
            it.copy(
                editingRoutine = target,
                installedAppsForRoutines = apps
            )
        }
    }

    fun closeRoutineEditor() {
        _uiState.update { it.copy(editingRoutine = null) }
    }

    fun saveRecordedRoutine(routine: RecordedRoutine) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = routineStore.saveRoutine(routine)
            runCatching {
                routineStore.syncRoutineIntoFts(saved, ftsHelper, embeddingRuntime)
            }
            RoutineRecorderManager.selectPlayRoutine(saved.id)
            refreshFtsQuery(_uiState.value.ftsSearchQuery)
            _uiState.update {
                it.copy(
                    editingRoutine = null,
                    statusToast = "Saved routine '${saved.name}' (${saved.steps.size} steps)"
                )
            }
        }
    }

    fun deleteRecordedRoutine(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            routineStore.deleteRoutine(id)
            runCatching { ftsHelper.deleteDocument("rt_$id") }
            refreshFtsQuery(_uiState.value.ftsSearchQuery)
            _uiState.update { cur ->
                cur.copy(
                    editingRoutine = if (cur.editingRoutine?.id == id) null else cur.editingRoutine,
                    statusToast = "Deleted routine"
                )
            }
        }
    }

    fun runRecordedRoutine(routine: RecordedRoutine, overrideDelayMs: Long? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            RoutineRecorderManager.selectPlayRoutine(routine.id)
            RoutineRecorderManager.setPlayingState(true, "Running '${routine.name}'...")
            val engine = RoutineExecutionEngine(getApplication()) { step ->
                val report = executor.execute(
                    com.example.data.ToolCallRequest(
                        toolName = "set_hardware_state",
                        args = mapOf(
                            "feature" to step.targetLabel.lowercase().ifBlank { "flashlight" },
                            "state" to step.textValue.lowercase().ifBlank { "on" }
                        )
                    )
                )
                report.success to report.summary
            }
            val result = engine.executeRoutine(routine, overrideDelayMs)
            RoutineRecorderManager.setPlayingState(false, result.summary)
            _uiState.update { it.copy(statusToast = result.summary) }
        }
    }

    fun launchFloatingRoutineControl(
        scope: RoutineScope = RoutineScope.GLOBAL,
        targetAppName: String? = null,
        targetPackage: String? = null,
        autoStartRecording: Boolean = false
    ) {
        val app = getApplication<Application>()
        val accReady = NovaAccessibilityService.instance != null ||
            NovaAccessibilityService.isServiceEnabledInSystem(app)
        if (!accReady) {
            _uiState.update {
                it.copy(
                    statusToast = "Enable Nova Accessibility Service in Android Settings to use Floating Routine Control."
                )
            }
            return
        }
        RoutineRecorderManager.showFloatingControl(
            scope = scope,
            targetAppName = targetAppName,
            targetPackage = targetPackage,
            selectedPlayRoutineId = _uiState.value.recordedRoutines.firstOrNull()?.id,
            autoStartRecording = autoStartRecording
        )
        if (scope == RoutineScope.PER_APP && !targetPackage.isNullOrBlank()) {
            app.packageManager.getLaunchIntentForPackage(targetPackage)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }?.let { runCatching { app.startActivity(it) } }
        }
        _uiState.update {
            it.copy(
                statusToast = if (autoStartRecording) {
                    "Floating recorder started — tap, swipe, or add + IF conditions on screen."
                } else {
                    "Floating routine bar active — use ▶ Start or ● Record over any app."
                }
            )
        }
    }

    fun hideFloatingRoutineControl() {
        RoutineRecorderManager.hideFloatingControl()
    }

    fun stopAndSaveFloatingRecording(customName: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val saved = RoutineRecorderManager.stopAndSaveRecording(
                context = getApplication(),
                routineName = customName,
                ftsHelper = ftsHelper,
                embeddingRuntime = embeddingRuntime
            )
            routineStore.reload()
            refreshFtsQuery(_uiState.value.ftsSearchQuery)
            _uiState.update {
                it.copy(
                    editingRoutine = saved ?: it.editingRoutine,
                    statusToast = saved?.let { rt -> "Saved '${rt.name}' (${rt.steps.size} steps) — customize delays & IF logic below." }
                        ?: "No steps recorded yet."
                )
            }
        }
    }

    fun showStatusToast(message: String) {
        _uiState.update { it.copy(statusToast = message) }
    }

    fun clearStatusToast() {
        _uiState.update { it.copy(statusToast = null) }
    }

    override fun onCleared() {
        if (isVoiceServiceBound) {
            runCatching { getApplication<Application>().unbindService(fgsConnection) }
        }
        routineStore.close()
        ftsHelper.close()
        embeddingRuntime.shutdown()
        llmBridge.unbind()
        super.onCleared()
    }

    companion object {
        const val FOLLOW_UP_TIMEOUT_MS = 10_000L
        private val KEY_USER_NAME = stringPreferencesKey("user_display_name")
        private val KEY_PERSONALITY = stringPreferencesKey("assistant_personality")
        private val KEY_WAKE_WORD = booleanPreferencesKey("always_on_wake_word")
        private val KEY_PREFER_NPU = booleanPreferencesKey("prefer_npu_backend")
        private val KEY_PROACTIVE_NOTIF_PROMPTS = booleanPreferencesKey("proactive_notif_prompts")
        private val KEY_THINKING_ENABLED = booleanPreferencesKey("llm_thinking_enabled")
        private val KEY_PAUSE_WAKE_DURING_MEDIA = booleanPreferencesKey("pause_wake_during_media")
        private val KEY_ASR_ENGINE = stringPreferencesKey("selected_asr_engine")
        private val KEY_TTS_ENGINE = stringPreferencesKey("selected_tts_engine")
        private val KEY_SYSTEM_TTS_ENGINE_PKG = stringPreferencesKey("selected_system_tts_engine_pkg")
        private val KEY_SYSTEM_TTS_VOICE_NAME = stringPreferencesKey("selected_system_tts_voice_name")
        private val KEY_TTS_SPEECH_RATE = floatPreferencesKey("tts_speech_rate")
        private val KEY_TTS_PITCH = floatPreferencesKey("tts_pitch")

        internal fun isMediaPlaybackStartOutcome(
            outcome: TurnExecutionOutcome,
            expectsAnswer: Boolean
        ): Boolean {
            if (expectsAnswer || outcome.pendingConfirmation != null) return false
            val lowerResp = outcome.responseText.lowercase()
            val failureMarkers = listOf("couldn't", "could not", "failed", "offline", "unavailable", "not installed", "no internet")
            if (failureMarkers.any { lowerResp.contains(it) }) return false
            val calledPlayTool = outcome.toolCalls.any { call ->
                call.toolName == "play_in_app" ||
                    (call.toolName == "media_control" && call.args["action"]?.lowercase() in setOf("play", "resume", "next", "previous"))
            }
            if (calledPlayTool) return true
            return lowerResp.startsWith("playing ") ||
                lowerResp.startsWith("resuming ") ||
                lowerResp.startsWith("now playing ")
        }
    }
}
