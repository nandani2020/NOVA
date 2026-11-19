package com.example.processA

import android.content.Context
import android.os.SystemClock
import com.example.data.ConditionType
import com.example.data.ElseBehavior
import com.example.data.LocalFtsIndexHelper
import com.example.data.RecordedRoutine
import com.example.data.RecordedRoutineStore
import com.example.data.RoutineActionType
import com.example.data.RoutineCondition
import com.example.data.RoutineRecorderState
import com.example.data.RoutineScope
import com.example.data.RoutineStep
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Coordinates interactive routine recording (both automatic Accessibility event capture
 * and precision Floating Overlay tap/swipe/conditional insertion) and manages the
 * floating routine control state across Process A.
 */
object RoutineRecorderManager {

    private val _state = MutableStateFlow(RoutineRecorderState())
    val state: StateFlow<RoutineRecorderState> = _state.asStateFlow()

    @Volatile
    private var lastEventUptimeMs: Long = 0L

    @Volatile
    private var lastScrollUptimeMs: Long = 0L

    @Volatile
    private var lastWindowSwitchPkg: String = ""

    @Volatile
    private var lastManualCaptureUptimeMs: Long = 0L

    @Volatile
    private var manualSuppressionUntilUptimeMs: Long = 0L

    @Volatile
    private var isManualGestureDispatching: Boolean = false

    @Volatile
    private var isFloatingOverlayFocused: Boolean = false

    @Volatile
    private var overlaySuppressionUntilUptimeMs: Long = 0L

    @Volatile
    private var lastTapUptimeMs: Long = 0L

    @Volatile
    private var lastTapX: Float = -1f

    @Volatile
    private var lastTapY: Float = -1f

    @Volatile
    private var lastTapLabel: String = ""

    fun isRecording(): Boolean = _state.value.isRecording

    fun setFloatingOverlayFocused(focused: Boolean, releaseCooldownMs: Long = 550L) {
        val now = SystemClock.uptimeMillis()
        isFloatingOverlayFocused = focused
        val safeCooldown = releaseCooldownMs.coerceAtLeast(250L)
        overlaySuppressionUntilUptimeMs = if (focused) {
            maxOf(overlaySuppressionUntilUptimeMs, now + safeCooldown)
        } else {
            now + safeCooldown
        }
    }

    fun markFloatingOverlayInteraction(cooldownMs: Long = 600L) {
        val now = SystemClock.uptimeMillis()
        overlaySuppressionUntilUptimeMs = maxOf(overlaySuppressionUntilUptimeMs, now + cooldownMs.coerceAtLeast(200L))
    }

    fun setManualDispatchInProgress(active: Boolean, settleCooldownMs: Long = 650L) {
        isManualGestureDispatching = active
        val now = SystemClock.uptimeMillis()
        if (active) {
            lastManualCaptureUptimeMs = now
            manualSuppressionUntilUptimeMs = maxOf(manualSuppressionUntilUptimeMs, now + 2500L)
        } else {
            lastManualCaptureUptimeMs = now
            manualSuppressionUntilUptimeMs = now + settleCooldownMs
        }
    }

    fun isOverlaySuppressingCapture(nowUptimeMs: Long = SystemClock.uptimeMillis()): Boolean {
        return isFloatingOverlayFocused ||
            nowUptimeMs < overlaySuppressionUntilUptimeMs ||
            isManualGestureDispatching ||
            nowUptimeMs < manualSuppressionUntilUptimeMs ||
            _state.value.isPlaying
    }

    fun showFloatingControl(
        scope: RoutineScope = _state.value.scope,
        targetAppName: String? = _state.value.targetAppName,
        targetPackage: String? = _state.value.targetPackage,
        selectedPlayRoutineId: String? = _state.value.selectedPlayRoutineId,
        autoStartRecording: Boolean = false
    ) {
        val now = SystemClock.uptimeMillis()
        if (autoStartRecording) {
            lastEventUptimeMs = now
            lastWindowSwitchPkg = targetPackage.orEmpty()
        }
        _state.update { cur ->
            val resolvedName = if (autoStartRecording && cur.draftName.isBlank()) {
                if (scope == RoutineScope.PER_APP && !targetAppName.isNullOrBlank()) {
                    "$targetAppName Routine"
                } else {
                    "Recorded Routine"
                }
            } else cur.draftName

            cur.copy(
                isFloatingVisible = true,
                isRecording = if (autoStartRecording) true else cur.isRecording,
                scope = scope,
                targetAppName = if (scope == RoutineScope.PER_APP) targetAppName else null,
                targetPackage = if (scope == RoutineScope.PER_APP) targetPackage else null,
                selectedPlayRoutineId = selectedPlayRoutineId ?: cur.selectedPlayRoutineId,
                draftName = resolvedName,
                capturedSteps = if (autoStartRecording && !cur.isRecording) emptyList() else cur.capturedSteps,
                lastStatusMessage = if (autoStartRecording) "Recording started..." else "Floating control ready"
            )
        }
        NovaAccessibilityService.instance?.syncFloatingRoutineOverlay(true)
    }

    fun hideFloatingControl() {
        isFloatingOverlayFocused = false
        overlaySuppressionUntilUptimeMs = 0L
        isManualGestureDispatching = false
        manualSuppressionUntilUptimeMs = 0L
        _state.update {
            it.copy(
                isFloatingVisible = false,
                isRecording = false,
                isCapturingTouch = false,
                lastStatusMessage = ""
            )
        }
        NovaAccessibilityService.instance?.syncFloatingRoutineOverlay(false)
    }

    fun startRecording(
        scope: RoutineScope = _state.value.scope,
        targetAppName: String? = _state.value.targetAppName,
        targetPackage: String? = _state.value.targetPackage,
        draftName: String = ""
    ) {
        val now = SystemClock.uptimeMillis()
        lastEventUptimeMs = now
        lastScrollUptimeMs = 0L
        lastTapUptimeMs = 0L
        lastTapX = -1f
        lastTapY = -1f
        lastTapLabel = ""
        lastManualCaptureUptimeMs = 0L
        manualSuppressionUntilUptimeMs = 0L
        isManualGestureDispatching = false
        isFloatingOverlayFocused = false
        overlaySuppressionUntilUptimeMs = 0L
        lastWindowSwitchPkg = targetPackage.orEmpty()
        val chosenName = draftName.trim().ifEmpty {
            if (scope == RoutineScope.PER_APP && !targetAppName.isNullOrBlank()) {
                "$targetAppName Routine"
            } else {
                "Global Routine"
            }
        }
        _state.update {
            it.copy(
                isFloatingVisible = true,
                isRecording = true,
                scope = scope,
                targetAppName = if (scope == RoutineScope.PER_APP) targetAppName else null,
                targetPackage = if (scope == RoutineScope.PER_APP) targetPackage else null,
                draftName = chosenName,
                capturedSteps = emptyList(),
                lastStatusMessage = "Recording ${if (scope == RoutineScope.PER_APP) targetAppName ?: "app" else "globally"}"
            )
        }
        NovaAccessibilityService.instance?.syncFloatingRoutineOverlay(true)
    }

    fun stopRecordingWithoutSaving(): List<RoutineStep> {
        val captured = _state.value.capturedSteps
        _state.update {
            it.copy(
                isRecording = false,
                isCapturingTouch = false,
                lastStatusMessage = "Recorded ${captured.size} step(s)"
            )
        }
        return captured
    }

    suspend fun stopAndSaveRecording(
        context: Context,
        routineName: String? = null,
        ftsHelper: LocalFtsIndexHelper? = null,
        embeddingRuntime: EmbeddingGemmaRuntime? = null
    ): RecordedRoutine? {
        val snap = _state.value
        val steps = snap.capturedSteps
        _state.update {
            it.copy(
                isRecording = false,
                isCapturingTouch = false
            )
        }
        if (steps.isEmpty()) {
            _state.update { it.copy(lastStatusMessage = "No steps recorded") }
            return null
        }

        val store = RecordedRoutineStore(context)
        val finalName = routineName?.trim()?.takeIf { it.isNotEmpty() }
            ?: snap.draftName.trim().takeIf { it.isNotEmpty() }
            ?: "Recorded Routine ${store.getAll().size + 1}"

        val saved = store.saveRoutine(
            RecordedRoutine(
                name = finalName,
                description = "Recorded ${if (snap.scope == RoutineScope.PER_APP) "for ${snap.targetAppName ?: "app"}" else "globally"} (${steps.size} steps)",
                scope = snap.scope,
                targetPackage = snap.targetPackage,
                targetAppName = snap.targetAppName,
                steps = steps
            )
        )
        if (ftsHelper != null) {
            runCatching { store.syncRoutineIntoFts(saved, ftsHelper, embeddingRuntime) }
        }
        store.close()

        _state.update {
            it.copy(
                selectedPlayRoutineId = saved.id,
                draftName = saved.name,
                lastStatusMessage = "Saved '${saved.name}' (${saved.steps.size} steps)"
            )
        }
        return saved
    }

    fun setTouchCaptureMode(capturing: Boolean) {
        _state.update { it.copy(isCapturingTouch = capturing) }
    }

    fun setPlayingState(playing: Boolean, status: String? = null) {
        _state.update { cur ->
            cur.copy(
                isPlaying = playing,
                lastStatusMessage = status ?: cur.lastStatusMessage
            )
        }
    }

    fun selectPlayRoutine(routineId: String?) {
        _state.update { it.copy(selectedPlayRoutineId = routineId) }
    }

    fun recordManualTap(
        x: Float,
        y: Float,
        holdDurationMs: Long = 60L,
        labelHint: String? = null,
        packageHint: String? = null
    ) {
        val now = SystemClock.uptimeMillis()
        lastManualCaptureUptimeMs = now
        val delayMs = computeElapsedDelayMs(now)
        val cleanLabel = labelHint?.trim().orEmpty()
        val hasCoords = x > 0f || y > 0f
        val title = when {
            cleanLabel.isNotEmpty() && hasCoords -> "Tap '$cleanLabel' (${x.toInt()}, ${y.toInt()})"
            cleanLabel.isNotEmpty() -> "Tap '$cleanLabel'"
            else -> "Tap (${x.toInt()}, ${y.toInt()})"
        }
        appendStep(
            RoutineStep(
                id = "st_${UUID.randomUUID().toString().take(6)}",
                actionType = RoutineActionType.TAP,
                title = title,
                x = x,
                y = y,
                holdDurationMs = holdDurationMs.coerceIn(45L, 2000L),
                delayBeforeMs = delayMs,
                targetLabel = cleanLabel,
                targetPackage = packageHint.orEmpty(),
                useExactCoordinates = hasCoords || cleanLabel.isEmpty()
            )
        )
    }

    fun recordManualSwipe(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 320L,
        packageHint: String? = null
    ) {
        val now = SystemClock.uptimeMillis()
        lastManualCaptureUptimeMs = now
        val delayMs = computeElapsedDelayMs(now)
        val dx = endX - startX
        val dy = endY - startY
        val dirLabel = when {
            kotlin.math.abs(dy) >= kotlin.math.abs(dx) -> if (dy < 0) "Swipe Up" else "Swipe Down"
            dx < 0 -> "Swipe Left"
            else -> "Swipe Right"
        }
        appendStep(
            RoutineStep(
                id = "st_${UUID.randomUUID().toString().take(6)}",
                actionType = RoutineActionType.SWIPE,
                title = "$dirLabel (${durationMs}ms)",
                x = startX,
                y = startY,
                endX = endX,
                endY = endY,
                holdDurationMs = durationMs.coerceIn(100L, 2500L),
                delayBeforeMs = delayMs,
                targetPackage = packageHint.orEmpty(),
                useExactCoordinates = true
            )
        )
    }

    fun recordConditionStep(
        condition: RoutineCondition,
        titleOverride: String? = null
    ) {
        val now = SystemClock.uptimeMillis()
        val delayMs = if (_state.value.capturedSteps.isEmpty()) 250L else computeElapsedDelayMs(now)
        val title = titleOverride?.trim()?.takeIf { it.isNotEmpty() } ?: condition.describe()
        appendStep(
            RoutineStep(
                id = "st_${UUID.randomUUID().toString().take(6)}",
                actionType = RoutineActionType.IF_CONDITION,
                title = title,
                delayBeforeMs = delayMs,
                condition = condition
            )
        )
    }

    fun onAccessibilityTapCaptured(
        x: Float,
        y: Float,
        label: String,
        pkg: String
    ) {
        val cur = _state.value
        if (!cur.isRecording || cur.isCapturingTouch) return
        val now = SystemClock.uptimeMillis()
        if (isOverlaySuppressingCapture(now)) return
        if (lastManualCaptureUptimeMs > 0L && now - lastManualCaptureUptimeMs < 450L) return
        if (cur.scope == RoutineScope.PER_APP && !cur.targetPackage.isNullOrBlank() &&
            pkg.isNotBlank() && pkg != cur.targetPackage
        ) {
            return
        }
        val cleanLabel = label.trim()
        // Deduplicate rapid duplicate Accessibility click events on the same target/point
        val nearSamePoint = lastTapX >= 0f && lastTapY >= 0f &&
            kotlin.math.hypot(x - lastTapX, y - lastTapY) < 16f
        val isRapidDup = lastTapUptimeMs > 0L && (now - lastTapUptimeMs) < 180L &&
            (nearSamePoint || (cleanLabel.isNotEmpty() && cleanLabel == lastTapLabel))
        if (isRapidDup) return

        lastTapUptimeMs = now
        lastTapX = x
        lastTapY = y
        lastTapLabel = cleanLabel

        val delayMs = computeElapsedDelayMs(now)
        val hasCoords = x > 0f || y > 0f
        val title = when {
            cleanLabel.isNotEmpty() && hasCoords -> "Tap '$cleanLabel' (${x.toInt()}, ${y.toInt()})"
            cleanLabel.isNotEmpty() -> "Tap '$cleanLabel'"
            else -> "Tap (${x.toInt()}, ${y.toInt()})"
        }
        appendStep(
            RoutineStep(
                id = "st_${UUID.randomUUID().toString().take(6)}",
                actionType = RoutineActionType.TAP,
                title = title,
                x = x,
                y = y,
                holdDurationMs = 60L,
                delayBeforeMs = delayMs,
                targetLabel = cleanLabel,
                targetPackage = pkg,
                useExactCoordinates = hasCoords
            )
        )
    }

    fun onAccessibilityScrollCaptured(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        pkg: String
    ) {
        val cur = _state.value
        if (!cur.isRecording || cur.isCapturingTouch) return
        val now = SystemClock.uptimeMillis()
        if (isOverlaySuppressingCapture(now)) return
        if (lastManualCaptureUptimeMs > 0L && now - lastManualCaptureUptimeMs < 500L) return
        if (lastScrollUptimeMs > 0L && now - lastScrollUptimeMs < 700L) return
        if (cur.scope == RoutineScope.PER_APP && !cur.targetPackage.isNullOrBlank() &&
            pkg.isNotBlank() && pkg != cur.targetPackage
        ) {
            return
        }
        lastScrollUptimeMs = now
        val delayMs = computeElapsedDelayMs(now)
        val dy = endY - startY
        val dx = endX - startX
        val label = when {
            kotlin.math.abs(dy) >= kotlin.math.abs(dx) -> if (dy < 0) "Swipe Up" else "Swipe Down"
            dx < 0 -> "Swipe Left"
            else -> "Swipe Right"
        }
        appendStep(
            RoutineStep(
                id = "st_${UUID.randomUUID().toString().take(6)}",
                actionType = RoutineActionType.SWIPE,
                title = label,
                x = startX,
                y = startY,
                endX = endX,
                endY = endY,
                holdDurationMs = 320L,
                delayBeforeMs = delayMs,
                targetPackage = pkg,
                useExactCoordinates = true
            )
        )
    }

    fun onAccessibilityTextCaptured(
        targetLabel: String,
        typedText: String,
        pkg: String
    ) {
        val cur = _state.value
        if (!cur.isRecording || cur.isCapturingTouch || typedText.isBlank()) return
        val now = SystemClock.uptimeMillis()
        if (isOverlaySuppressingCapture(now)) return
        if (cur.scope == RoutineScope.PER_APP && !cur.targetPackage.isNullOrBlank() &&
            pkg.isNotBlank() && pkg != cur.targetPackage
        ) {
            return
        }
        val steps = cur.capturedSteps.toMutableList()
        val last = steps.lastOrNull()
        // Coalesce consecutive keystrokes into the same input box cleanly
        if (last != null && last.actionType == RoutineActionType.TYPE_TEXT &&
            last.targetPackage == pkg && last.targetLabel == targetLabel
        ) {
            steps[steps.lastIndex] = last.copy(
                textValue = typedText.take(120),
                title = "Type '${typedText.take(24)}'"
            )
            lastEventUptimeMs = now
            _state.update {
                it.copy(
                    capturedSteps = steps,
                    lastStatusMessage = "Updated text step (${steps.size} steps)"
                )
            }
            return
        }

        val delayMs = computeElapsedDelayMs(now)
        appendStep(
            RoutineStep(
                id = "st_${UUID.randomUUID().toString().take(6)}",
                actionType = RoutineActionType.TYPE_TEXT,
                title = "Type '${typedText.take(24)}'",
                delayBeforeMs = delayMs,
                targetLabel = targetLabel.ifBlank { "Input" },
                textValue = typedText.take(120),
                targetPackage = pkg
            )
        )
    }

    fun onAccessibilityAppSwitched(appLabel: String, pkg: String) {
        val cur = _state.value
        if (!cur.isRecording || cur.scope != RoutineScope.GLOBAL) return
        val now = SystemClock.uptimeMillis()
        if (isOverlaySuppressingCapture(now)) return
        if (pkg.isBlank() || pkg == lastWindowSwitchPkg) return
        lastWindowSwitchPkg = pkg
        val delayMs = computeElapsedDelayMs(now)
        val cleanName = appLabel.trim().ifBlank { pkg.substringAfterLast('.') }
        appendStep(
            RoutineStep(
                id = "st_${UUID.randomUUID().toString().take(6)}",
                actionType = RoutineActionType.LAUNCH_APP,
                title = "Open $cleanName",
                delayBeforeMs = delayMs,
                targetLabel = cleanName,
                targetPackage = pkg
            )
        )
    }

    private fun computeElapsedDelayMs(nowUptimeMs: Long): Long {
        val prev = lastEventUptimeMs
        lastEventUptimeMs = nowUptimeMs
        if (prev <= 0L) return 350L
        return (nowUptimeMs - prev).coerceIn(150L, 10_000L)
    }

    private fun appendStep(step: RoutineStep) {
        _state.update { cur ->
            val updated = cur.capturedSteps + step
            cur.copy(
                capturedSteps = updated,
                lastStatusMessage = "${step.title} (${updated.size} steps)"
            )
        }
    }

    fun defaultConditionForPreset(presetIndex: Int): RoutineCondition = when (presetIndex) {
        0 -> RoutineCondition(ConditionType.SCREEN_CONTAINS_TEXT, "Skip", "", ElseBehavior.SKIP_NEXT)
        1 -> RoutineCondition(ConditionType.SCREEN_MISSING_TEXT, "Done", "", ElseBehavior.STOP_ROUTINE)
        2 -> RoutineCondition(ConditionType.BATTERY_AT_LEAST, "20", "", ElseBehavior.STOP_ROUTINE)
        else -> RoutineCondition(ConditionType.WIFI_IS_ON, "true", "", ElseBehavior.CONTINUE)
    }
}
