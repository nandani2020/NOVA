package com.example.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class RoutineScope(val label: String) {
    GLOBAL("Global (All Apps)"),
    PER_APP("Per-App")
}

enum class RoutineActionType(val label: String) {
    TAP("Tap"),
    SWIPE("Swipe"),
    TYPE_TEXT("Type Text"),
    GLOBAL_ACTION("OS Action"),
    LAUNCH_APP("Open App"),
    HARDWARE_TOGGLE("Hardware"),
    IF_CONDITION("IF Condition")
}

enum class ConditionType(val label: String, val needsExpectedValue: Boolean = true) {
    SCREEN_CONTAINS_TEXT("Screen contains text", true),
    SCREEN_MISSING_TEXT("Screen does NOT contain text", true),
    APP_IN_FOREGROUND("App is in foreground", true),
    BATTERY_AT_LEAST("Battery at least (%)", true),
    BATTERY_BELOW("Battery below (%)", true),
    WIFI_IS_ON("Wi-Fi is ON", false),
    BLUETOOTH_IS_ON("Bluetooth is ON", false),
    TIME_BETWEEN("Time between HH:mm-HH:mm", true)
}

enum class ElseBehavior(val label: String) {
    SKIP_NEXT("Skip next step"),
    CONTINUE("Skip only guarded step & continue"),
    STOP_ROUTINE("Stop routine safely"),
    WAIT_AND_RETRY("Wait & retry condition"),
    GO_BACK("Press Back then continue"),
    SCROLL_DOWN("Scroll down then continue")
}

data class RoutineCondition(
    val type: ConditionType,
    val expectedValue: String = "",
    val secondValue: String = "",
    val elseBehavior: ElseBehavior = ElseBehavior.SKIP_NEXT
) {
    fun describe(): String {
        val check = when (type) {
            ConditionType.SCREEN_CONTAINS_TEXT -> "screen shows \"$expectedValue\""
            ConditionType.SCREEN_MISSING_TEXT -> "screen does NOT show \"$expectedValue\""
            ConditionType.APP_IN_FOREGROUND -> "\"$expectedValue\" is open"
            ConditionType.BATTERY_AT_LEAST -> "battery >= ${expectedValue.ifBlank { "20" }}%"
            ConditionType.BATTERY_BELOW -> "battery < ${expectedValue.ifBlank { "20" }}%"
            ConditionType.WIFI_IS_ON -> "Wi-Fi is ON"
            ConditionType.BLUETOOTH_IS_ON -> "Bluetooth is ON"
            ConditionType.TIME_BETWEEN -> "time is $expectedValue–${secondValue.ifBlank { "23:59" }}"
        }
        return "IF $check -> else ${elseBehavior.label}"
    }
}

data class RoutineStep(
    val id: String = UUID.randomUUID().toString().take(8),
    val actionType: RoutineActionType,
    val title: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val endX: Float = 0f,
    val endY: Float = 0f,
    val holdDurationMs: Long = 60L,
    val delayBeforeMs: Long = 500L,
    val targetLabel: String = "",
    val textValue: String = "",
    val targetPackage: String = "",
    val useExactCoordinates: Boolean = false,
    val retryCount: Int = 1,
    val condition: RoutineCondition? = null
)

data class RecordedRoutine(
    val id: String = "rt_${UUID.randomUUID().toString().take(8)}",
    val name: String,
    val description: String = "",
    val scope: RoutineScope = RoutineScope.GLOBAL,
    val targetPackage: String? = null,
    val targetAppName: String? = null,
    val steps: List<RoutineStep> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun hasConditionals(): Boolean =
        steps.any { it.actionType == RoutineActionType.IF_CONDITION || it.condition != null }

    fun summaryLine(): String {
        val scopeLabel = if (scope == RoutineScope.PER_APP && !targetAppName.isNullOrBlank()) {
            targetAppName
        } else {
            "Global"
        }
        val condTag = if (hasConditionals()) " • Conditional" else ""
        return "$scopeLabel • ${steps.size} step${if (steps.size == 1) "" else "s"}$condTag"
    }
}

data class RoutineRecorderState(
    val isFloatingVisible: Boolean = false,
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val isCapturingTouch: Boolean = false,
    val scope: RoutineScope = RoutineScope.GLOBAL,
    val targetPackage: String? = null,
    val targetAppName: String? = null,
    val draftName: String = "",
    val capturedSteps: List<RoutineStep> = emptyList(),
    val selectedPlayRoutineId: String? = null,
    val lastStatusMessage: String = ""
)

object RoutineJsonCodec {

    fun stepsToJson(steps: List<RoutineStep>): String {
        val arr = JSONArray()
        for (step in steps) {
            val obj = JSONObject().apply {
                put("id", step.id)
                put("actionType", step.actionType.name)
                put("title", step.title)
                put("x", step.x.toDouble())
                put("y", step.y.toDouble())
                put("endX", step.endX.toDouble())
                put("endY", step.endY.toDouble())
                put("holdDurationMs", step.holdDurationMs)
                put("delayBeforeMs", step.delayBeforeMs)
                put("targetLabel", step.targetLabel)
                put("textValue", step.textValue)
                put("targetPackage", step.targetPackage)
                put("useExactCoordinates", step.useExactCoordinates)
                put("retryCount", step.retryCount)
                if (step.condition != null) {
                    put(
                        "condition",
                        JSONObject().apply {
                            put("type", step.condition.type.name)
                            put("expectedValue", step.condition.expectedValue)
                            put("secondValue", step.condition.secondValue)
                            put("elseBehavior", step.condition.elseBehavior.name)
                        }
                    )
                }
            }
            arr.put(obj)
        }
        return arr.toString()
    }

    fun stepsFromJson(raw: String): List<RoutineStep> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val actionType = runCatching {
                        RoutineActionType.valueOf(o.optString("actionType", "TAP"))
                    }.getOrDefault(RoutineActionType.TAP)
                    val condObj = o.optJSONObject("condition")
                    val condition = if (condObj != null) {
                        val cType = runCatching {
                            ConditionType.valueOf(condObj.optString("type", "SCREEN_CONTAINS_TEXT"))
                        }.getOrDefault(ConditionType.SCREEN_CONTAINS_TEXT)
                        val elseBeh = runCatching {
                            ElseBehavior.valueOf(condObj.optString("elseBehavior", "SKIP_NEXT"))
                        }.getOrDefault(ElseBehavior.SKIP_NEXT)
                        RoutineCondition(
                            type = cType,
                            expectedValue = condObj.optString("expectedValue", ""),
                            secondValue = condObj.optString("secondValue", ""),
                            elseBehavior = elseBeh
                        )
                    } else null

                    add(
                        RoutineStep(
                            id = o.optString("id").ifBlank { UUID.randomUUID().toString().take(8) },
                            actionType = actionType,
                            title = o.optString("title", actionType.label),
                            x = o.optDouble("x", 0.0).toFloat(),
                            y = o.optDouble("y", 0.0).toFloat(),
                            endX = o.optDouble("endX", 0.0).toFloat(),
                            endY = o.optDouble("endY", 0.0).toFloat(),
                            holdDurationMs = o.optLong("holdDurationMs", 60L).coerceAtLeast(20L),
                            delayBeforeMs = o.optLong("delayBeforeMs", 500L).coerceAtLeast(0L),
                            targetLabel = o.optString("targetLabel", ""),
                            textValue = o.optString("textValue", ""),
                            targetPackage = o.optString("targetPackage", ""),
                            useExactCoordinates = o.optBoolean("useExactCoordinates", false),
                            retryCount = o.optInt("retryCount", 1).coerceIn(0, 5),
                            condition = condition
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }
}
