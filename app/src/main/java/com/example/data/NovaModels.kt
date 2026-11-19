package com.example.data

enum class SafetyTier(val label: String) {
    GREEN("Green • Auto-Execute"),
    YELLOW("Yellow • Announce & Execute"),
    RED("Red • Mandatory Confirmation")
}

enum class RoutingTier(val displayName: String, val badge: String) {
    TIER_0_NORMALIZE("Tier 0 • Input Normalizer", "T0"),
    TIER_1_REGEX("Tier 1 • Regex Fast-Path (<5ms)", "T1 <5ms"),
    TIER_1_5_SEMANTIC("Tier 1.5 • Semantic Lookup", "T1.5"),
    TIER_2_SKILL_DISCLOSURE("Tier 2 • LiteRT-LM (:llm)", "T2 :llm"),
    TIER_3_TOOL_LOOP("Tier 3 • Tool & UI Loop (:llm)", "T3 UI-LOOP")
}

enum class SkillCategory(val title: String) {
    A("A: Device & Hardware Reflexes"),
    B("B: Local Search & Personal Memory"),
    C("C: Communications & Scheduling"),
    D("D: Autonomous Cross-App UI"),
    E("E: Workflows (Smart Home Disabled)")
}

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>,
    val safetyTier: SafetyTier = SafetyTier.GREEN
) {
    init {
        require(parameters.size <= 3) {
            "Tool '$name' violates Tool Arity Cap: ${parameters.size} > 3 parameters"
        }
    }
}

data class SkillSpec(
    val id: String,
    val title: String,
    val category: SkillCategory,
    val description: String,
    val requiresThinking: Boolean,
    val fastTriggers: List<String>,
    val tools: List<ToolDefinition>,
    val safetyTier: SafetyTier,
    val fewShotExamples: List<String>,
    val enabled: Boolean = true,
    val guidance: String = "",
    val companionTools: List<String> = emptyList()
)

data class ToolCallRequest(
    val toolName: String,
    val args: Map<String, String>,
    val shortRefId: String = "t01",
    val safetyTier: SafetyTier = SafetyTier.GREEN
) {
    init {
        require(args.size <= 3) {
            "ToolCall '$toolName' exceeds 3-argument cap (${args.size} args)"
        }
    }
}

data class UiNodeSnapshot(
    val id: String, // Short alphanumeric ID (e.g., n01, n14)
    val label: String,
    val nodeType: String,
    val boundsLabel: String,
    val clickable: Boolean,
    val editable: Boolean = false,
    val isHighlighted: Boolean = false
)

data class TierRedConfirmation(
    val id: String,
    val title: String,
    val summary: String,
    val targetApp: String,
    val targetNodeId: String,
    val riskTag: String,
    val pipScreenNodes: List<UiNodeSnapshot>,
    val pendingToolCall: ToolCallRequest,
    val pipScreenshotBytes: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TierRedConfirmation) return false
        return id == other.id &&
            targetNodeId == other.targetNodeId &&
            pendingToolCall == other.pendingToolCall &&
            pipScreenNodes == other.pipScreenNodes &&
            pipScreenshotBytes.contentEquals(other.pipScreenshotBytes)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + targetNodeId.hashCode()
        result = 31 * result + pendingToolCall.hashCode()
        result = 31 * result + pipScreenNodes.hashCode()
        result = 31 * result + (pipScreenshotBytes?.contentHashCode() ?: 0)
        return result
    }
}

// Smart Home controls intentionally disabled while focusing on core phone skills
enum class HubCategory(val title: String) {
    FAVOURITES("FAVOURITES"),
    PHONE_APPS("PHONE APPS"),
    ROUTINES("ROUTINES")
}

enum class ControlIconType {
    TIMER, MEDIA, FLASHLIGHT, WIFI, BLUETOOTH, DND, APP, ROUTINE
}

data class SmartControlItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: HubCategory,
    val isEnabled: Boolean,
    val hasRadialDetail: Boolean,
    val primaryValue: Float,
    val minValue: Float,
    val maxValue: Float,
    val stepSize: Float = 1f,
    val unit: String,
    val modes: List<String> = emptyList(),
    val selectedMode: String = "",
    val intensities: List<String> = listOf("Low", "Medium", "High"),
    val selectedIntensity: String = "Medium",
    val iconType: ControlIconType,
    val packageHint: String? = null
)

enum class StepStatus { DONE, ACTIVE, PENDING, BLOCKED }

data class PipelineStepPill(
    val index: Int,
    val label: String,
    val status: StepStatus,
    val latencyMs: Long? = null
)

enum class OrbVisualMode {
    IDLE,
    LISTENING,
    FAST_PATH_FLASH,
    THINKING,
    UI_NAVIGATING,
    CONFIRMATION_PAUSED,
    ERROR_ABORTED
}

data class LocalIndexDocument(
    val docId: String,
    val category: String, // "apps", "contacts", "notes", "messages", "files", "calendar"
    val title: String,
    val body: String,
    val uriOrRef: String,
    val score: Double = 0.0
)

enum class WakeWordStatus {
    OFF,
    LISTENING,
    PAUSED_FOR_OVERLAY,
    PAUSED_FOR_MEDIA,
    MIC_PERMISSION_NEEDED,
    UNAVAILABLE
}

enum class AsrEngineOption(
    val id: String,
    val displayName: String,
    val subtitle: String
) {
    ANDROID_SYSTEM(
        id = "android_system",
        displayName = "Android System ASR",
        subtitle = "Default OS SpeechRecognizer (online + offline fallback)"
    ),
    ANDROID_ON_DEVICE(
        id = "android_on_device",
        displayName = "Android On-Device",
        subtitle = "Android 12+ local SpeechRecognizer — faster, private offline recognition"
    ),
    LOCAL_ASR_MODEL(
        id = "local_asr_model",
        displayName = "Local ASR Model",
        subtitle = "Staged Whisper / Moonshine / Zipformer bundle (falls back to OS ASR when native ONNX bridge is unavailable)"
    );

    companion object {
        fun fromId(raw: String?): AsrEngineOption =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true) }
                ?: ANDROID_SYSTEM
    }
}

enum class TtsEngineOption(
    val id: String,
    val displayName: String,
    val subtitle: String
) {
    ANDROID_SYSTEM(
        id = "android_system",
        displayName = "Android System TTS",
        subtitle = "Uses selected Android TTS engine (Google Neural, Sherpa-ONNX TTS, Samsung, etc.)"
    ),
    KOKORO_ONNX(
        id = "kokoro_onnx",
        displayName = "Kokoro-82M Neural",
        subtitle = "Top natural 82M voice — stage Kokoro ONNX bundle or pair with Sherpa-ONNX Android TTS engine"
    ),
    PIPER_VITS(
        id = "piper_vits",
        displayName = "Piper VITS Neural",
        subtitle = "Ultra-fast lightweight offline neural voice — stage Piper ONNX model or pair with Sherpa-ONNX TTS"
    ),
    LOCAL_TTS_BUNDLE(
        id = "local_tts_bundle",
        displayName = "Custom TTS Bundle",
        subtitle = "Imported .onnx / .zip neural voice model (falls back to System TTS for audio output)"
    );

    companion object {
        fun fromId(raw: String?): TtsEngineOption =
            entries.firstOrNull { it.id.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true) }
                ?: ANDROID_SYSTEM
    }
}

data class SystemTtsEngineInfo(
    val packageName: String,
    val label: String
)

data class SystemTtsVoiceInfo(
    val name: String,
    val displayName: String,
    val localeTag: String,
    val isNetworkRequired: Boolean
)

data class InstalledModelInfo(
    val slot: ModelSlot,
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val isBundleDir: Boolean,
    val modifiedMs: Long,
    val isActive: Boolean
) {
    val sizeMb: Long get() = sizeBytes / (1024L * 1024L)
}

data class ProcessHealthState(
    val processAStatus: String = "ACTIVE",
    val processAPid: Int = 0,
    val processAHeapMb: Int = 0,
    val processBStatus: String = "MODEL_MISSING",
    val processBPid: Int = 0,
    val isLlmReady: Boolean = false,
    val modelPresent: Boolean = false,
    val activeModelName: String? = null,
    val activeModelSizeMb: Long = 0L,
    val activeLlmBackend: String = "UNLOADED",
    val preferNpuBackend: Boolean = false,
    val thinkingEnabled: Boolean = true,
    val embeddingPresent: Boolean = false,
    val embeddingReady: Boolean = false,
    val activeEmbeddingName: String? = null,
    val activeEmbeddingSizeMb: Long = 0L,
    val asrModelPresent: Boolean = false,
    val activeAsrModelName: String? = null,
    val activeAsrModelSizeMb: Long = 0L,
    val ttsModelPresent: Boolean = false,
    val activeTtsModelName: String? = null,
    val activeTtsModelSizeMb: Long = 0L,
    val asrEngine: AsrEngineOption = AsrEngineOption.ANDROID_SYSTEM,
    val ttsEngine: TtsEngineOption = TtsEngineOption.ANDROID_SYSTEM,
    val selectedSystemTtsEnginePkg: String = "",
    val selectedSystemTtsVoiceName: String = "",
    val ttsSpeechRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    val pauseWakeDuringMedia: Boolean = false,
    val externalMediaPlaying: Boolean = false,
    val warmupQueueSize: Int = 0,
    val respawnCount: Int = 0,
    val ftsMode: String = "FTS5",
    val indexedDocCount: Int = 0,
    val unreadMessagesCount: Int = 0,
    val accessibilityConnected: Boolean = false,
    val notificationListenerConnected: Boolean = false,
    val proactiveNotificationPrompts: Boolean = true,
    val wakeWordEnabled: Boolean = false,
    val wakeWordStatus: WakeWordStatus = WakeWordStatus.OFF,
    val wakeCalibrationSamples: Int = 0,
    val wakeCalibrationAliases: List<String> = emptyList(),
    val isCalibratingWakeVoice: Boolean = false,
    val ambientStandMode: Boolean = false,
    val assistantPersonality: AssistantPersonality = AssistantPersonality.WARM_COMPANION,
    val agenticMemoryCount: Int = 0,
    val learnedProcessCount: Int = 0
)

enum class AssistantPersonality(
    val id: String,
    val displayName: String,
    val tagline: String,
    val systemToneInstruction: String
) {
    WARM_COMPANION(
        id = "warm",
        displayName = "Warm Companion",
        tagline = "Friendly, attentive, conversational and encouraging",
        systemToneInstruction = "Adopt a warm, attentive, conversational tone like a trusted personal chief-of-staff — natural phrasing, brief genuine encouragement, proactive follow-through."
    ),
    SHARP_EXECUTIVE(
        id = "executive",
        displayName = "Sharp Executive",
        tagline = "Crisp, structured, decisive and action-first",
        systemToneInstruction = "Adopt a crisp, sharp executive tone — lead with the bottom line, surface priorities first, keep wording tight and decisive."
    ),
    WITTY_COPILOT(
        id = "witty",
        displayName = "Witty Co-Pilot",
        tagline = "Clever, upbeat, light dry humor without slowing down",
        systemToneInstruction = "Adopt an upbeat, lightly witty co-pilot tone — sprinkle subtle clever charm in brief framing while keeping facts and actions crystal clear."
    ),
    CALM_MINIMALIST(
        id = "minimalist",
        displayName = "Calm Minimalist",
        tagline = "Quiet, concise, ultra-clean and distraction-free",
        systemToneInstruction = "Adopt a calm minimalist tone — zero filler, short serene sentences, essential details only."
    );

    companion object {
        fun fromIdOrName(raw: String?): AssistantPersonality? {
            val norm = raw.orEmpty().trim().lowercase()
            if (norm.isEmpty()) return null
            return when {
                norm == "warm" || norm == "default" || norm == "balanced" ||
                    norm.contains("warm") || norm.contains("companion") || norm.contains("friendly") -> WARM_COMPANION
                norm == "executive" || norm.contains("exec") || norm.contains("prof") ||
                    norm.contains("formal") || norm.contains("business") || norm.contains("sharp") -> SHARP_EXECUTIVE
                norm == "witty" || norm.contains("wit") || norm.contains("playful") ||
                    norm.contains("fun") || norm.contains("humor") || norm.contains("casual") -> WITTY_COPILOT
                norm == "minimalist" || norm.contains("min") || norm.contains("concise") ||
                    norm.contains("short") || norm.contains("brief") || norm.contains("calm") ||
                    norm.contains("quiet") -> CALM_MINIMALIST
                else -> null
            }
        }
    }
}

enum class AgenticMemoryType(val label: String) {
    PREFERENCE("Preference"),
    CORRECTION("Correction"),
    EPISODE("Episode"),
    FACT("Fact")
}

enum class TimeOfDayBucket(val label: String) {
    MORNING("Morning"),
    AFTERNOON("Afternoon"),
    EVENING("Evening"),
    NIGHT("Night"),
    ANY("Anytime");

    companion object {
        fun fromTimestamp(timestampMs: Long = System.currentTimeMillis()): TimeOfDayBucket {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestampMs }
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            return when (hour) {
                in 5..11 -> MORNING
                in 12..16 -> AFTERNOON
                in 17..21 -> EVENING
                else -> NIGHT
            }
        }
    }
}

data class AgenticMemoryEntry(
    val memoryId: String,
    val memoryType: AgenticMemoryType,
    val topic: String,
    val content: String,
    val confidence: Float,
    val reinforcementCount: Int,
    val timeBucket: TimeOfDayBucket,
    val createdAtMs: Long,
    val lastReinforcedAtMs: Long
)

data class BehaviorEventRecord(
    val eventId: String,
    val normalizedCommand: String,
    val toolSequence: String,
    val appName: String,
    val topic: String,
    val timeBucket: TimeOfDayBucket,
    val dayOfWeek: Int,
    val success: Boolean,
    val timestampMs: Long
)

data class LearnedProcessRecord(
    val processId: String,
    val name: String,
    val triggerPhrase: String,
    val steps: List<String>,
    val preferredApp: String,
    val timeBucket: TimeOfDayBucket,
    val executionCount: Int,
    val confidence: Float,
    val autoSuggest: Boolean,
    val lastExecutedAtMs: Long,
    val createdAtMs: Long
)

data class TurnExecutionOutcome(
    val normalizedQuery: String,
    val routingTier: RoutingTier,
    val selectedSkills: List<String>,
    val latencyMs: Long,
    val thinkingEnabled: Boolean,
    val steps: List<PipelineStepPill>,
    val responseText: String,
    val toolCalls: List<ToolCallRequest> = emptyList(),
    val pendingConfirmation: TierRedConfirmation? = null,
    val orbMode: OrbVisualMode = OrbVisualMode.FAST_PATH_FLASH,
    val requiresModelSetup: Boolean = false,
    val requiresAccessibilitySetup: Boolean = false
)
