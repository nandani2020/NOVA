package com.example.engine

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.data.ConditionType
import com.example.data.ElseBehavior
import com.example.data.RecordedRoutine
import com.example.data.RoutineActionType
import com.example.data.RoutineCondition
import com.example.data.RoutineScope
import com.example.data.RoutineStep
import com.example.processA.NovaAccessibilityService
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class RoutineRunResult(
    val success: Boolean,
    val summary: String,
    val executedSteps: Int,
    val skippedSteps: Int
)

data class ConditionEvalSnapshot(
    val screenTexts: List<String> = emptyList(),
    val foregroundPackage: String = "",
    val batteryPercent: Int = 100,
    val wifiOn: Boolean = false,
    val bluetoothOn: Boolean = false,
    val currentTimeHHmm: String = "12:00"
)

/**
 * Executes recorded conditional routines step-by-step using custom per-step delays,
 * Accessibility gestures (tap, swipe, text entry, global navigation), app launches,
 * and live IF/THEN/ELSE condition evaluation.
 */
class RoutineExecutionEngine(
    private val context: Context,
    private val externalActionBridge: (suspend (RoutineStep) -> Pair<Boolean, String>)? = null
) {

    suspend fun executeRoutine(
        routine: RecordedRoutine,
        overrideDelayMs: Long? = null,
        onStepProgress: (Int, RoutineStep, String) -> Unit = { _, _, _ -> }
    ): RoutineRunResult {
        if (routine.steps.isEmpty()) {
            return RoutineRunResult(
                success = false,
                summary = "Routine '${routine.name}' has no steps to run.",
                executedSteps = 0,
                skippedSteps = 0
            )
        }

        NovaAccessibilityService.resetKillSwitch()

        val needsAccessibility = routine.steps.any {
            it.actionType in setOf(
                RoutineActionType.TAP,
                RoutineActionType.SWIPE,
                RoutineActionType.TYPE_TEXT,
                RoutineActionType.GLOBAL_ACTION
            ) || it.condition?.type in setOf(
                ConditionType.SCREEN_CONTAINS_TEXT,
                ConditionType.SCREEN_MISSING_TEXT,
                ConditionType.APP_IN_FOREGROUND
            )
        }

        val service = NovaAccessibilityService.instance
        if (needsAccessibility && service == null) {
            return RoutineRunResult(
                success = false,
                summary = "Enable Nova Accessibility Service in Android Settings to replay screen taps, swipes, or screen conditions for '${routine.name}'.",
                executedSteps = 0,
                skippedSteps = 0
            )
        }

        // If Per-App scope is set and that app isn't foreground, open it cleanly before replaying
        if (routine.scope == RoutineScope.PER_APP && !routine.targetPackage.isNullOrBlank()) {
            val currentFg = service?.resolveCurrentForegroundPackage()
            if (currentFg != routine.targetPackage) {
                launchPackage(routine.targetPackage)
                service?.waitForTargetAppWindow(routine.targetPackage, timeoutMs = 2400L)
                    ?: delay(700L)
            }
        }

        var executed = 0
        var skipped = 0
        var skipNextStep = false

        for ((index, step) in routine.steps.withIndex()) {
            if (NovaAccessibilityService.isKillSwitchTripped()) {
                return RoutineRunResult(
                    success = false,
                    summary = "Routine '${routine.name}' stopped by hardware kill switch.",
                    executedSteps = executed,
                    skippedSteps = skipped
                )
            }

            if (skipNextStep) {
                skipNextStep = false
                skipped++
                onStepProgress(index, step, "Skipped by previous IF condition")
                continue
            }

            val waitMs = (overrideDelayMs ?: step.delayBeforeMs).coerceIn(0L, 30_000L)
            if (waitMs > 0L) {
                delayInInterruptibleChunks(waitMs)
                if (NovaAccessibilityService.isKillSwitchTripped()) {
                    return RoutineRunResult(
                        success = false,
                        summary = "Routine '${routine.name}' aborted.",
                        executedSteps = executed,
                        skippedSteps = skipped
                    )
                }
            }

            // Evaluate standalone IF_CONDITION step
            if (step.actionType == RoutineActionType.IF_CONDITION) {
                val cond = step.condition
                if (cond == null) {
                    executed++
                    continue
                }
                val passed = evaluateConditionWithRetry(cond, service)
                if (passed) {
                    executed++
                    onStepProgress(index, step, "Condition TRUE: ${cond.describe()}")
                    continue
                }
                when (cond.elseBehavior) {
                    ElseBehavior.STOP_ROUTINE -> {
                        return RoutineRunResult(
                            success = true,
                            summary = "Routine '${routine.name}' stopped safely (${cond.describe()} was false).",
                            executedSteps = executed,
                            skippedSteps = skipped
                        )
                    }
                    ElseBehavior.GO_BACK -> {
                        service?.performUiAct("BACK", "")
                        skipNextStep = true
                        onStepProgress(index, step, "Condition FALSE -> went Back & skipping next step")
                    }
                    ElseBehavior.SCROLL_DOWN -> {
                        service?.performUiAct("SCROLL_DOWN", "")
                        skipNextStep = true
                        onStepProgress(index, step, "Condition FALSE -> scrolled down & skipping next step")
                    }
                    ElseBehavior.SKIP_NEXT,
                    ElseBehavior.CONTINUE,
                    ElseBehavior.WAIT_AND_RETRY -> {
                        skipNextStep = true
                        onStepProgress(index, step, "Condition FALSE -> skipping next step")
                    }
                }
                continue
            }

            // Evaluate inline guard condition attached directly to a gesture/action step
            val inlineCond = step.condition
            if (inlineCond != null) {
                val passed = evaluateConditionWithRetry(inlineCond, service)
                if (!passed) {
                    when (inlineCond.elseBehavior) {
                        ElseBehavior.STOP_ROUTINE -> {
                            return RoutineRunResult(
                                success = true,
                                summary = "Routine '${routine.name}' stopped at '${step.title}' (${inlineCond.describe()} was false).",
                                executedSteps = executed,
                                skippedSteps = skipped
                            )
                        }
                        ElseBehavior.GO_BACK -> {
                            service?.performUiAct("BACK", "")
                            skipped++
                            onStepProgress(index, step, "Guard FALSE -> went Back & skipped ${step.title}")
                        }
                        ElseBehavior.SCROLL_DOWN -> {
                            service?.performUiAct("SCROLL_DOWN", "")
                            skipped++
                            onStepProgress(index, step, "Guard FALSE -> scrolled & skipped ${step.title}")
                        }
                        ElseBehavior.SKIP_NEXT -> {
                            skipped++
                            skipNextStep = true
                            onStepProgress(index, step, "Guard FALSE -> skipped ${step.title} & next")
                        }
                        ElseBehavior.CONTINUE,
                        ElseBehavior.WAIT_AND_RETRY -> {
                            skipped++
                            onStepProgress(index, step, "Guard FALSE -> skipped ${step.title}")
                        }
                    }
                    continue
                }
            }

            val stepOutcome = executeSingleStep(step, service)
            if (!stepOutcome.first && stepOutcome.second.startsWith("TIER_RED_BLOCKED")) {
                return RoutineRunResult(
                    success = false,
                    summary = "Routine '${routine.name}' paused before sensitive control '${step.targetLabel.ifBlank { step.title }}'.",
                    executedSteps = executed,
                    skippedSteps = skipped
                )
            }
            if (stepOutcome.first) {
                executed++
            } else {
                skipped++
            }
            onStepProgress(index, step, stepOutcome.second)
        }

        val skippedPart = if (skipped > 0) " ($skipped skipped)" else ""
        return RoutineRunResult(
            success = executed > 0 || skipped > 0,
            summary = "Completed '${routine.name}': ran $executed step${if (executed == 1) "" else "s"}$skippedPart.",
            executedSteps = executed,
            skippedSteps = skipped
        )
    }

    private suspend fun executeSingleStep(
        step: RoutineStep,
        service: NovaAccessibilityService?
    ): Pair<Boolean, String> {
        return when (step.actionType) {
            RoutineActionType.TAP -> {
                val svc = service ?: return false to "Accessibility unavailable"
                val label = step.targetLabel.trim()
                if (label.isNotEmpty() && DeferredToolRegistry.isRedButtonLabel(label)) {
                    return false to "TIER_RED_BLOCKED:$label"
                }
                val hasValidCoords = step.x > 0f || step.y > 0f
                if (step.useExactCoordinates && hasValidCoords) {
                    val tapped = svc.withFloatingOverlayPassthrough {
                        svc.dispatchTap(step.x, step.y, step.holdDurationMs)
                    }
                    if (tapped) {
                        return true to "Tapped (${step.x.toInt()}, ${step.y.toInt()})"
                    }
                    if (label.isNotEmpty()) {
                        val fallback = svc.performFindAndAct(targetLabel = label, actionType = "CLICK")
                        if (fallback.success) {
                            return true to "Tapped '$label'"
                        }
                        if (fallback.message.startsWith("TIER_RED_BLOCKED")) {
                            return false to fallback.message
                        }
                    }
                    return false to "Tap missed (${step.x.toInt()}, ${step.y.toInt()})"
                }
                if (label.isNotEmpty()) {
                    val outcome = svc.performFindAndAct(targetLabel = label, actionType = "CLICK")
                    if (outcome.success) {
                        return true to "Tapped '$label'"
                    }
                    if (outcome.message.startsWith("TIER_RED_BLOCKED")) {
                        return false to outcome.message
                    }
                }
                val tapped = if (hasValidCoords) {
                    svc.withFloatingOverlayPassthrough {
                        svc.dispatchTap(step.x, step.y, step.holdDurationMs)
                    }
                } else false
                tapped to if (tapped) "Tapped (${step.x.toInt()}, ${step.y.toInt()})" else "Tap missed"
            }

            RoutineActionType.SWIPE -> {
                val svc = service ?: return false to "Accessibility unavailable"
                val ok = svc.withFloatingOverlayPassthrough {
                    svc.dispatchSwipe(
                        x1 = step.x,
                        y1 = step.y,
                        x2 = step.endX,
                        y2 = step.endY,
                        durationMs = step.holdDurationMs.coerceIn(100L, 2500L)
                    )
                }
                ok to if (ok) "Swiped (${step.x.toInt()},${step.y.toInt()})->(${step.endX.toInt()},${step.endY.toInt()})" else "Swipe failed"
            }

            RoutineActionType.TYPE_TEXT -> {
                val svc = service ?: return false to "Accessibility unavailable"
                val outcome = svc.performFindAndAct(
                    targetLabel = step.targetLabel.ifBlank { "Search" },
                    actionType = "TYPE",
                    inputText = step.textValue
                )
                outcome.success to outcome.message
            }

            RoutineActionType.GLOBAL_ACTION -> {
                val svc = service ?: return false to "Accessibility unavailable"
                val actionCmd = step.textValue.ifBlank { step.targetLabel }.ifBlank { "HOME" }.uppercase()
                val outcome = svc.performUiAct(actionCmd, "")
                outcome.success to outcome.message
            }

            RoutineActionType.LAUNCH_APP -> {
                val pkg = step.targetPackage.trim().ifEmpty {
                    AppCapabilityRegistry.findBestMatch(context, step.targetLabel)?.packageName.orEmpty()
                }
                if (pkg.isEmpty()) {
                    false to "App '${step.targetLabel}' not found"
                } else {
                    val launched = launchPackage(pkg)
                    if (launched) {
                        service?.waitForTargetAppWindow(pkg, timeoutMs = 2000L)
                    }
                    launched to if (launched) "Opened ${step.targetLabel.ifBlank { pkg }}" else "Could not open $pkg"
                }
            }

            RoutineActionType.HARDWARE_TOGGLE -> {
                externalActionBridge?.invoke(step) ?: executeLocalHardwareStep(step)
            }

            RoutineActionType.IF_CONDITION -> true to step.title
        }
    }

    private fun executeLocalHardwareStep(step: RoutineStep): Pair<Boolean, String> {
        val feature = step.targetLabel.lowercase().trim()
        val state = step.textValue.lowercase().trim().ifEmpty { "on" }
        val turnOn = state != "off"
        return when {
            feature.contains("flash") || feature.contains("torch") -> {
                val ok = runCatching {
                    val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                    val id = cm.cameraIdList.firstOrNull {
                        cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }
                    if (id != null) {
                        cm.setTorchMode(id, turnOn)
                        true
                    } else false
                }.getOrDefault(false)
                ok to "Flashlight ${if (turnOn) "ON" else "OFF"}"
            }
            feature.contains("mute") -> {
                runCatching {
                    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (turnOn) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                        0
                    )
                }
                true to "Audio ${if (turnOn) "muted" else "unmuted"}"
            }
            feature.contains("wifi") -> {
                val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Settings.Panel.ACTION_WIFI
                } else {
                    Settings.ACTION_WIFI_SETTINGS
                }
                val ok = runCatching {
                    context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }.getOrDefault(false)
                ok to "Opened Wi-Fi panel"
            }
            else -> true to "Updated $feature -> $state"
        }
    }

    private suspend fun evaluateConditionWithRetry(
        condition: RoutineCondition,
        service: NovaAccessibilityService?
    ): Boolean {
        val attempts = if (condition.elseBehavior == ElseBehavior.WAIT_AND_RETRY) 3 else 1
        repeat(attempts) { idx ->
            val snap = captureLiveSnapshot(service)
            if (evaluateCondition(condition, snap)) return true
            if (idx < attempts - 1) {
                delay(650L)
            }
        }
        return false
    }

    @SuppressLint("MissingPermission")
    fun captureLiveSnapshot(service: NovaAccessibilityService?): ConditionEvalSnapshot {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = if (level >= 0 && scale > 0) ((level * 100) / scale).coerceIn(0, 100) else 100

        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val wifiOn = runCatching { wm?.isWifiEnabled == true }.getOrDefault(false)

        val btAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val canReadBt = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
        val btOn = if (canReadBt) runCatching { btAdapter?.isEnabled == true }.getOrDefault(false) else false

        val fgPkg = service?.resolveCurrentForegroundPackage().orEmpty()
        val nodes = service?.scanActiveScreenNodes(fgPkg.ifBlank { null }).orEmpty()
        val texts = nodes.map { it.label }.filter { it.isNotBlank() }
        val timeNow = SimpleDateFormat("HH:mm", Locale.US).format(Date())

        return ConditionEvalSnapshot(
            screenTexts = texts,
            foregroundPackage = fgPkg,
            batteryPercent = batteryPct,
            wifiOn = wifiOn,
            bluetoothOn = btOn,
            currentTimeHHmm = timeNow
        )
    }

    private fun launchPackage(pkg: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return false
        return runCatching { context.startActivity(launch) }.isSuccess
    }

    private suspend fun delayInInterruptibleChunks(totalMs: Long) {
        var remaining = totalMs
        while (remaining > 0L && !NovaAccessibilityService.isKillSwitchTripped()) {
            val chunk = remaining.coerceAtMost(200L)
            delay(chunk)
            remaining -= chunk
        }
    }

    companion object {
        fun evaluateCondition(
            condition: RoutineCondition,
            snapshot: ConditionEvalSnapshot
        ): Boolean {
            val expected = condition.expectedValue.trim()
            return when (condition.type) {
                ConditionType.SCREEN_CONTAINS_TEXT -> {
                    if (expected.isEmpty()) true
                    else snapshot.screenTexts.any { it.contains(expected, ignoreCase = true) }
                }
                ConditionType.SCREEN_MISSING_TEXT -> {
                    if (expected.isEmpty()) true
                    else snapshot.screenTexts.none { it.contains(expected, ignoreCase = true) }
                }
                ConditionType.APP_IN_FOREGROUND -> {
                    if (expected.isEmpty()) snapshot.foregroundPackage.isNotBlank()
                    else snapshot.foregroundPackage.contains(expected, ignoreCase = true) ||
                        snapshot.screenTexts.any { it.contains(expected, ignoreCase = true) }
                }
                ConditionType.BATTERY_AT_LEAST -> {
                    val threshold = expected.toIntOrNull()?.coerceIn(0, 100) ?: 20
                    snapshot.batteryPercent >= threshold
                }
                ConditionType.BATTERY_BELOW -> {
                    val threshold = expected.toIntOrNull()?.coerceIn(0, 100) ?: 20
                    snapshot.batteryPercent < threshold
                }
                ConditionType.WIFI_IS_ON -> {
                    val wantOn = !expected.equals("false", ignoreCase = true)
                    snapshot.wifiOn == wantOn
                }
                ConditionType.BLUETOOTH_IS_ON -> {
                    val wantOn = !expected.equals("false", ignoreCase = true)
                    snapshot.bluetoothOn == wantOn
                }
                ConditionType.TIME_BETWEEN -> {
                    val startMins = parseMinutesOfDay(expected) ?: 0
                    val endMins = parseMinutesOfDay(condition.secondValue.trim().ifEmpty { "23:59" }) ?: 1439
                    val curMins = parseMinutesOfDay(snapshot.currentTimeHHmm) ?: 720
                    if (startMins <= endMins) {
                        curMins in startMins..endMins
                    } else {
                        // Overnight wrap-around window e.g. 22:00 to 06:00
                        curMins >= startMins || curMins <= endMins
                    }
                }
            }
        }

        private fun parseMinutesOfDay(hhMm: String): Int? {
            val match = Regex("(\\d{1,2}):(\\d{2})").find(hhMm.trim()) ?: return null
            val h = match.groupValues[1].toIntOrNull()?.coerceIn(0, 23) ?: return null
            val m = match.groupValues[2].toIntOrNull()?.coerceIn(0, 59) ?: return null
            return h * 60 + m
        }
    }
}
