package com.example.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Debug
import android.os.IBinder
import android.os.Process
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ControlIconType
import com.example.data.DeviceAccountHelper
import com.example.data.DeviceActionExecutor
import com.example.data.HubCategory
import com.example.data.LlmModelManager
import com.example.data.LlmModelPreset
import com.example.data.LocalFtsIndexHelper
import com.example.data.LocalIndexDocument
import com.example.data.ModelDownloadState
import com.example.data.ModelSlot
import com.example.data.OrbVisualMode
import com.example.data.PipelineStepPill
import com.example.data.ProcessHealthState
import com.example.data.RoutingTier
import com.example.data.SmartControlItem
import com.example.data.StepStatus
import com.example.data.TierRedConfirmation
import com.example.engine.NovaPipelineRouter
import com.example.engine.SemanticLookupEngine
import com.example.processA.EmbeddingGemmaRuntime
import com.example.processA.LlmProcessBridge
import com.example.processA.NovaAccessibilityService
import com.example.processA.NovaNotificationListenerService
import com.example.processA.NovaVoiceForegroundService
import kotlinx.coroutines.CancellationException
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
    val requiresModelSetup: Boolean = false
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
    val embeddingPreset: LlmModelPreset? = null,
    val downloadState: ModelDownloadState = ModelDownloadState.Idle,
    val ftsSearchQuery: String = "",
    val ftsSearchResults: List<LocalIndexDocument> = emptyList(),
    val showSettingsScreen: Boolean = false,
    val statusToast: String? = null
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
    private val accountHelper = DeviceAccountHelper(application)
    val modelManager = LlmModelManager(application)
    private val embeddingRuntime = EmbeddingGemmaRuntime(application, modelManager)
    private val llmBridge = LlmProcessBridge(application, modelManager)

    private val _uiState = MutableStateFlow(
        NovaUiState(
            recordedAccount = accountHelper.getOrDetectSystemAccount(),
            llmPreset = modelManager.llmPreset,
            embeddingPreset = modelManager.embeddingPreset
        )
    )
    val uiState: StateFlow<NovaUiState> = _uiState.asStateFlow()

    private val executor = DeviceActionExecutor(
        context = application,
        ftsHelper = ftsHelper,
        embeddingRuntime = embeddingRuntime,
        onControlUpdated = { id, enabled, value, mode ->
            applyControlPatch(id, enabled, value, mode)
        }
    )

    private val router = NovaPipelineRouter(
        llmBridge = llmBridge,
        executor = executor,
        embeddingRuntime = embeddingRuntime,
        accountHelper = accountHelper,
        onAccountRecorded = { saved ->
            _uiState.update { it.copy(recordedAccount = saved) }
        }
    )

    private var activeTurnJob: Job? = null
    private var downloadJob: Job? = null
    private var voiceService: NovaVoiceForegroundService? = null
    private var isVoiceServiceBound = false

    private val fgsConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? NovaVoiceForegroundService.LocalBinder
            voiceService = binder?.getService()
            isVoiceServiceBound = true
            applyWakeWordServiceBinding(_uiState.value.processHealth.wakeWordEnabled)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            isVoiceServiceBound = false
        }
    }

    init {
        refreshFtsQuery("")

        // Sync live hardware toggles (Bluetooth, Wi-Fi, DND, Media Volume) from real OS APIs
        val hwSnap = executor.readLiveHardwareSnapshot()
        applyControlPatch("ctrl_bt", hwSnap.bluetoothOn, null, null)
        applyControlPatch("ctrl_wifi", hwSnap.wifiOn, null, null)
        applyControlPatch("ctrl_dnd", hwSnap.dndOn, null, null)
        applyControlPatch("ctrl_media", hwSnap.volumePercent > 0, hwSnap.volumePercent.toFloat(), null)

        // Bind Process B (:llm) isolated inference runtime
        llmBridge.bindToLlmProcess()

        // Warm up EmbeddingGemma 2 & ensure installed apps/capabilities are indexed
        viewModelScope.launch {
            if (embeddingRuntime.isModelFilePresent()) {
                embeddingRuntime.warmup()
            }
            if (ftsHelper.countDocuments("apps") == 0) {
                com.example.engine.AppCapabilityRegistry.syncInstalledAppsWithCapabilities(
                    getApplication(),
                    ftsHelper,
                    embeddingRuntime
                )
                refreshFtsQuery(_uiState.value.ftsSearchQuery)
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

        // Load persisted preferences (display name, wake-word toggle, NPU preference)
        viewModelScope.launch {
            application.novaDataStore.data
                .map { prefs ->
                    Triple(
                        prefs[KEY_USER_NAME] ?: "NOVA USER",
                        prefs[KEY_WAKE_WORD] ?: false,
                        prefs[KEY_PREFER_NPU] ?: false
                    )
                }
                .collect { (name, wakeEnabled, preferNpu) ->
                    _uiState.update {
                        it.copy(
                            userName = name,
                            processHealth = it.processHealth.copy(
                                wakeWordEnabled = wakeEnabled,
                                preferNpuBackend = preferNpu
                            )
                        )
                    }
                    applyWakeWordServiceBinding(wakeEnabled)
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
                NovaNotificationListenerService.isConnected
            ) { bridge, embReady, accConnected, notifConnected ->
                TelemetrySnapshot(bridge, embReady, accConnected, notifConnected)
            }.collect { (bridge, embReady, accConnected, notifConnected) ->
                val runtime = Runtime.getRuntime()
                val jvmUsedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
                val nativeUsedMb = Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)
                val totalProcAMb = (jvmUsedMb + nativeUsedMb).toInt()
                val embFile = modelManager.getActiveModelFile(ModelSlot.EMBEDDING)

                _uiState.update { cur ->
                    cur.copy(
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
                            activeEmbeddingSizeMb = (embFile?.length() ?: 0L) / (1024L * 1024L),
                            respawnCount = bridge.respawnCount,
                            ftsMode = ftsHelper.activeFtsMode,
                            indexedDocCount = ftsHelper.countDocuments(),
                            unreadMessagesCount = ftsHelper.countDocuments("messages"),
                            accessibilityConnected = accConnected,
                            notificationListenerConnected = notifConnected
                        )
                    )
                }
            }
        }
    }

    private data class TelemetrySnapshot(
        val bridge: LlmProcessBridge.BridgeState,
        val embReady: Boolean,
        val accConnected: Boolean,
        val notifConnected: Boolean
    )

    private fun applyWakeWordServiceBinding(enabled: Boolean) {
        voiceService?.setWakeWordListening(enabled) { command ->
            if (command.isNotBlank()) {
                submitCommand(command, speakResponse = true)
            }
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        viewModelScope.launch {
            getApplication<Application>().novaDataStore.edit { prefs ->
                prefs[KEY_WAKE_WORD] = enabled
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

    fun setSettingsVisible(visible: Boolean) {
        if (visible) {
            llmBridge.refreshPresenceOnly()
            refreshFtsQuery(_uiState.value.ftsSearchQuery)
        }
        _uiState.update { it.copy(showSettingsScreen = visible) }
    }

    fun updateUserName(newName: String) {
        val clean = newName.trim().uppercase().take(24).ifEmpty { "NOVA USER" }
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
                if (slot == ModelSlot.LLM) {
                    llmBridge.reloadModelInProcessB(_uiState.value.processHealth.preferNpuBackend)
                    _uiState.update {
                        it.copy(statusToast = "Downloaded ${file.name}. Initializing Gemma 4 :llm...")
                    }
                } else {
                    SemanticLookupEngine.invalidateNeuralCache()
                    val ok = embeddingRuntime.warmup()
                    _uiState.update {
                        it.copy(
                            statusToast = if (ok) "Downloaded & loaded EmbeddingGemma (${file.name})."
                            else "Downloaded ${file.name}. Warmup failed: ${embeddingRuntime.lastError.value}"
                        )
                    }
                }
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
                if (slot == ModelSlot.LLM) {
                    llmBridge.reloadModelInProcessB(_uiState.value.processHealth.preferNpuBackend)
                    _uiState.update {
                        it.copy(statusToast = "Imported ${file.name}. Loading into :llm...")
                    }
                } else {
                    SemanticLookupEngine.invalidateNeuralCache()
                    val ok = embeddingRuntime.warmup()
                    _uiState.update {
                        it.copy(
                            statusToast = if (ok) "Imported & loaded EmbeddingGemma (${file.name})."
                            else "Imported ${file.name}."
                        )
                    }
                }
            }.onFailure { ex ->
                _uiState.update {
                    it.copy(statusToast = ex.message ?: "Model import failed")
                }
            }
        }
    }

    fun deleteInstalledModel(slot: ModelSlot) {
        viewModelScope.launch {
            if (slot == ModelSlot.LLM) {
                llmBridge.unloadModelInProcessB()
                modelManager.deleteActiveModel(ModelSlot.LLM)
                llmBridge.refreshPresenceOnly()
                _uiState.update { it.copy(statusToast = "Gemma 4 E2B model removed.") }
            } else {
                embeddingRuntime.unload()
                SemanticLookupEngine.invalidateNeuralCache()
                modelManager.deleteActiveModel(ModelSlot.EMBEDDING)
                _uiState.update { it.copy(statusToast = "EmbeddingGemma 2 model removed.") }
            }
        }
    }

    fun reloadModel(slot: ModelSlot) {
        if (slot == ModelSlot.LLM) {
            llmBridge.reloadModelInProcessB(_uiState.value.processHealth.preferNpuBackend)
        } else {
            viewModelScope.launch {
                SemanticLookupEngine.invalidateNeuralCache()
                embeddingRuntime.warmup()
            }
        }
    }

    fun syncInstalledAppsToFts() {
        viewModelScope.launch {
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
        val count = ftsHelper.syncContacts(getApplication())
        refreshFtsQuery(_uiState.value.ftsSearchQuery)
        _uiState.update {
            it.copy(
                statusToast = if (count >= 0) "Indexed $count contacts into ${ftsHelper.activeFtsMode}"
                else "READ_CONTACTS permission required to sync contacts."
            )
        }
    }

    fun syncCalendarToFts() {
        val count = ftsHelper.syncCalendarEvents(getApplication())
        refreshFtsQuery(_uiState.value.ftsSearchQuery)
        _uiState.update {
            it.copy(
                statusToast = if (count >= 0) "Indexed $count calendar events into ${ftsHelper.activeFtsMode}"
                else "READ_CALENDAR permission required to sync calendar."
            )
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
            voiceService?.stopSpeakingImmediate()
            llmBridge.sendBargeInAbort()
        }
        _uiState.update { state ->
            state.copy(
                voiceState = state.voiceState.copy(
                    isListening = listening,
                    statusHeader = if (listening) "LISTENING..." else "SAY SOMETHING",
                    orbMode = if (listening) OrbVisualMode.LISTENING else OrbVisualMode.IDLE,
                    amplitude = amplitude,
                    isSpeakingTts = false
                )
            )
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

        activeTurnJob?.cancel()
        voiceService?.stopSpeakingImmediate()
        NovaAccessibilityService.resetKillSwitch()

        activeTurnJob = viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    voiceState = state.voiceState.copy(
                        isListening = false,
                        transcript = query,
                        statusHeader = "ROUTING...",
                        orbMode = OrbVisualMode.LISTENING,
                        stepPills = emptyList(),
                        assistantResponse = "",
                        requiresModelSetup = false
                    )
                )
            }

            try {
                val outcome = router.routeAndExecute(query) { pills, orbMode, header ->
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

                _uiState.update { state ->
                    state.copy(
                        pendingConfirmation = outcome.pendingConfirmation,
                        processHealth = state.processHealth.copy(
                            indexedDocCount = ftsHelper.countDocuments()
                        ),
                        voiceState = state.voiceState.copy(
                            statusHeader = when {
                                outcome.requiresModelSetup -> "LLM MODEL MISSING"
                                outcome.pendingConfirmation != null -> "CONFIRMATION REQUIRED"
                                else -> outcome.routingTier.badge
                            },
                            orbMode = outcome.orbMode,
                            stepPills = outcome.steps,
                            assistantResponse = outcome.responseText,
                            lastRoutingTier = outcome.routingTier,
                            lastLatencyMs = outcome.latencyMs,
                            isSpeakingTts = speakResponse && outcome.pendingConfirmation == null,
                            requiresModelSetup = outcome.requiresModelSetup
                        ),
                        statusToast = if (outcome.routingTier == RoutingTier.TIER_1_REGEX) {
                            "${outcome.routingTier.badge} • ${outcome.responseText}"
                        } else null
                    )
                }

                if (speakResponse) {
                    voiceService?.speak(
                        outcome.pendingConfirmation?.summary ?: outcome.responseText
                    )
                }
            } catch (_: CancellationException) {
                // Handled gracefully by killSwitchAbort
            }
        }
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
        activeTurnJob?.cancel()
        llmBridge.sendBargeInAbort()
        voiceService?.stopSpeakingImmediate()
        NovaAccessibilityService.abortAutomation("Kill switch pressed")
        handleKillSwitchAbort("Stopped by user kill switch")
    }

    private fun handleKillSwitchAbort(reason: String) {
        activeTurnJob?.cancel()
        voiceService?.stopSpeakingImmediate()
        _uiState.update { state ->
            state.copy(
                pendingConfirmation = null,
                voiceState = state.voiceState.copy(
                    isListening = false,
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

    fun clearStatusToast() {
        _uiState.update { it.copy(statusToast = null) }
    }

    override fun onCleared() {
        if (isVoiceServiceBound) {
            runCatching { getApplication<Application>().unbindService(fgsConnection) }
        }
        embeddingRuntime.shutdown()
        llmBridge.unbind()
        super.onCleared()
    }

    companion object {
        private val KEY_USER_NAME = stringPreferencesKey("user_display_name")
        private val KEY_WAKE_WORD = booleanPreferencesKey("always_on_wake_word")
        private val KEY_PREFER_NPU = booleanPreferencesKey("prefer_npu_backend")
    }
}
