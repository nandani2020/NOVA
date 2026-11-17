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
    val enabled: Boolean = true
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
    val embeddingPresent: Boolean = false,
    val embeddingReady: Boolean = false,
    val activeEmbeddingName: String? = null,
    val activeEmbeddingSizeMb: Long = 0L,
    val warmupQueueSize: Int = 0,
    val respawnCount: Int = 0,
    val ftsMode: String = "FTS5",
    val indexedDocCount: Int = 0,
    val unreadMessagesCount: Int = 0,
    val accessibilityConnected: Boolean = false,
    val notificationListenerConnected: Boolean = false,
    val wakeWordEnabled: Boolean = false,
    val ambientStandMode: Boolean = false
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
    val requiresModelSetup: Boolean = false
)
