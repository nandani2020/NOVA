package com.example.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import com.example.data.DeviceActionExecutor
import com.example.data.WakeWordStatus
import com.example.processA.NovaVoiceForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Reads genuine Android device sensors & system telemetry so Nova can embody its hardware
 * ("what's your current status?", "how warm are you?", "how bright is it?", "check your sensors").
 *
 * Uses zero dangerous runtime permissions: Accelerometer, Light, Proximity, Gyroscope, Barometer,
 * Magnetometer, Ambient Temperature, BatteryManager, PowerManager thermal status, ActivityManager,
 * and StatFs are all accessible directly.
 */
object DeviceSensorTelemetry {

    data class TelemetrySnapshot(
        val batteryPercent: Int,
        val isCharging: Boolean,
        val chargeSource: String,
        val batteryTempCelsius: Float?,
        val batteryHealthLabel: String,
        val thermalLabel: String,
        val availableRamMb: Long,
        val totalRamMb: Long,
        val freeStorageGb: Double,
        val lightLux: Float?,
        val proximityCm: Float?,
        val proximityMaxRangeCm: Float?,
        val accelMagnitudeG: Float?,
        val devicePosture: String?,
        val isMoving: Boolean?,
        val gyroRadPerSec: Float?,
        val pressureHpa: Float?,
        val ambientTempCelsius: Float?,
        val magneticMicroTesla: Float?,
        val availableSensorNames: List<String>,
        val musicActive: Boolean,
        val wakeWordStatus: WakeWordStatus
    )

    @Volatile
    internal var testSnapshotOverride: ((Context) -> TelemetrySnapshot?)? = null

    suspend fun readSnapshot(context: Context, sampleWindowMs: Long = 130L): TelemetrySnapshot {
        testSnapshotOverride?.invoke(context)?.let { return it }

        return withContext(Dispatchers.Default) {
            // 1. Battery telemetry
            val batteryIntent = runCatching {
                context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }.getOrNull()
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            val chargeSource = when (plugged) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> if (isCharging) "Charger" else "Battery"
            }
            val rawBattTemp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?: Int.MIN_VALUE
            val batteryTempC = if (rawBattTemp != Int.MIN_VALUE && rawBattTemp in -300..1200) {
                rawBattTemp / 10f
            } else null

            val rawHealth = batteryIntent?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
                ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
            val batteryHealthLabel = when (rawHealth) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "degraded"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over-voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                else -> "normal"
            }

            // 2. Thermal status
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val thermalLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && pm != null) {
                when (runCatching { pm.currentThermalStatus }.getOrDefault(PowerManager.THERMAL_STATUS_NONE)) {
                    PowerManager.THERMAL_STATUS_NONE -> "cool"
                    PowerManager.THERMAL_STATUS_LIGHT -> "slightly warm"
                    PowerManager.THERMAL_STATUS_MODERATE -> "warm"
                    PowerManager.THERMAL_STATUS_SEVERE -> "hot"
                    PowerManager.THERMAL_STATUS_CRITICAL,
                    PowerManager.THERMAL_STATUS_EMERGENCY,
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "overheating"
                    else -> "normal"
                }
            } else {
                when {
                    batteryTempC != null && batteryTempC >= 43f -> "hot"
                    batteryTempC != null && batteryTempC >= 37f -> "warm"
                    else -> "cool"
                }
            }

            // 3. RAM & Storage
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            if (am != null) runCatching { am.getMemoryInfo(memInfo) }
            val availRamMb = (memInfo.availMem / (1024L * 1024L)).coerceAtLeast(0L)
            val totalRamMb = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(0L)
            val freeStorageGb = runCatching {
                val stat = StatFs(Environment.getDataDirectory().path)
                (stat.availableBytes.toDouble() / (1024.0 * 1024.0 * 1024.0))
            }.getOrDefault(0.0)

            // 4. Live hardware sensors via SensorManager
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val allSensors = runCatching {
                sensorManager?.getSensorList(Sensor.TYPE_ALL).orEmpty()
            }.getOrDefault(emptyList())

            val sensorLabels = allSensors.mapNotNull { s ->
                when (s.type) {
                    Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
                    Sensor.TYPE_GYROSCOPE -> "Gyroscope"
                    Sensor.TYPE_LIGHT -> "Ambient Light"
                    Sensor.TYPE_PROXIMITY -> "Proximity"
                    Sensor.TYPE_MAGNETIC_FIELD -> "Magnetometer"
                    Sensor.TYPE_PRESSURE -> "Barometer"
                    Sensor.TYPE_AMBIENT_TEMPERATURE -> "Ambient Thermometer"
                    Sensor.TYPE_RELATIVE_HUMIDITY -> "Humidity"
                    Sensor.TYPE_STEP_COUNTER -> "Step Counter"
                    Sensor.TYPE_GRAVITY -> "Gravity"
                    else -> null
                }
            }.distinct()

            var lightLux: Float? = null
            var proxCm: Float? = null
            var accelVec: FloatArray? = null
            var gyroVec: FloatArray? = null
            var pressureHpa: Float? = null
            var ambientTempC: Float? = null
            var magVec: FloatArray? = null

            val proxSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            val targets = listOfNotNull(
                sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT),
                proxSensor,
                sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE),
                sensorManager?.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE),
                sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            )

            if (sensorManager != null && targets.isNotEmpty()) {
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        val ev = event ?: return
                        val vals = ev.values ?: return
                        when (ev.sensor.type) {
                            Sensor.TYPE_LIGHT -> if (vals.isNotEmpty()) lightLux = vals[0]
                            Sensor.TYPE_PROXIMITY -> if (vals.isNotEmpty()) proxCm = vals[0]
                            Sensor.TYPE_ACCELEROMETER -> if (vals.size >= 3) accelVec = floatArrayOf(vals[0], vals[1], vals[2])
                            Sensor.TYPE_GYROSCOPE -> if (vals.size >= 3) gyroVec = floatArrayOf(vals[0], vals[1], vals[2])
                            Sensor.TYPE_PRESSURE -> if (vals.isNotEmpty()) pressureHpa = vals[0]
                            Sensor.TYPE_AMBIENT_TEMPERATURE -> if (vals.isNotEmpty()) ambientTempC = vals[0]
                            Sensor.TYPE_MAGNETIC_FIELD -> if (vals.size >= 3) magVec = floatArrayOf(vals[0], vals[1], vals[2])
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                try {
                    for (sensor in targets) {
                        runCatching {
                            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                        }
                    }
                    delay(sampleWindowMs.coerceIn(40L, 250L))
                } finally {
                    runCatching { sensorManager.unregisterListener(listener) }
                }
            }

            val accelMag = accelVec?.let { v ->
                sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat() / SensorManager.GRAVITY_EARTH
            }
            val posture = accelVec?.let { v ->
                when {
                    v[2] >= 7.5f -> "lying face up"
                    v[2] <= -7.5f -> "lying face down"
                    abs(v[1]) >= 7.0f -> "held upright"
                    abs(v[0]) >= 7.0f -> "held in landscape"
                    else -> "tilted in hand"
                }
            }
            val gyroMag = gyroVec?.let { v ->
                sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
            }
            val moving = when {
                accelMag != null && abs(accelMag - 1.0f) > 0.16f -> true
                gyroMag != null && gyroMag > 0.28f -> true
                accelMag != null || gyroMag != null -> false
                else -> null
            }
            val magMicroTesla = magVec?.let { v ->
                sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble()).toFloat()
            }

            val audioMgr = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val musicActive = runCatching { audioMgr?.isMusicActive == true }.getOrDefault(false)

            TelemetrySnapshot(
                batteryPercent = pct,
                isCharging = isCharging,
                chargeSource = chargeSource,
                batteryTempCelsius = batteryTempC,
                batteryHealthLabel = batteryHealthLabel,
                thermalLabel = thermalLabel,
                availableRamMb = availRamMb,
                totalRamMb = totalRamMb,
                freeStorageGb = freeStorageGb,
                lightLux = lightLux,
                proximityCm = proxCm,
                proximityMaxRangeCm = proxSensor?.maximumRange,
                accelMagnitudeG = accelMag,
                devicePosture = posture,
                isMoving = moving,
                gyroRadPerSec = gyroMag,
                pressureHpa = pressureHpa,
                ambientTempCelsius = ambientTempC,
                magneticMicroTesla = magMicroTesla,
                availableSensorNames = sensorLabels,
                musicActive = musicActive,
                wakeWordStatus = NovaVoiceForegroundService.wakeWordStatus.value
            )
        }
    }

    fun formatFirstPersonStatus(
        snapshot: TelemetrySnapshot,
        hardware: DeviceActionExecutor.HardwareSnapshot,
        modelLoaded: Boolean,
        modelName: String?
    ): String {
        val parts = mutableListOf<String>()

        // 1. Physical wellbeing (thermal + battery + memory)
        val battPart = if (snapshot.batteryPercent >= 0) {
            val chargeText = if (snapshot.isCharging) "charging via ${snapshot.chargeSource}" else "on battery"
            val tempText = snapshot.batteryTempCelsius?.let { String.format(Locale.US, ", %.1f°C", it) }.orEmpty()
            "my battery is at ${snapshot.batteryPercent}% ($chargeText$tempText)"
        } else {
            "battery telemetry is unavailable"
        }
        val stateLead = when (snapshot.thermalLabel) {
            "hot", "overheating" -> "I'm running ${snapshot.thermalLabel} right now — $battPart."
            "warm", "slightly warm" -> "I'm feeling ${snapshot.thermalLabel} and steady — $battPart."
            else -> "I'm feeling cool and responsive — $battPart."
        }
        parts.add(stateLead)

        // 2. Live physical sensors (motion, posture, ambient light, proximity, pressure)
        val sensoryBits = mutableListOf<String>()
        if (snapshot.isMoving != null) {
            val motionDesc = if (snapshot.isMoving) "actively moving" else "resting still"
            val postureDesc = snapshot.devicePosture?.let { " ($it)" }.orEmpty()
            sensoryBits.add("I feel $motionDesc$postureDesc")
        } else if (snapshot.devicePosture != null) {
            sensoryBits.add("I'm ${snapshot.devicePosture}")
        }
        snapshot.lightLux?.let { lux ->
            sensoryBits.add("the room light is ${describeLux(lux)}")
        }
        snapshot.proximityCm?.let { cm ->
            val maxR = snapshot.proximityMaxRangeCm ?: 5f
            val proxText = if (cm < minOf(2.5f, maxR)) "my proximity sensor is covered" else "nothing is blocking my proximity sensor"
            sensoryBits.add(proxText)
        }
        snapshot.pressureHpa?.let { hpa ->
            sensoryBits.add(String.format(Locale.US, "barometric pressure is %.0f hPa", hpa))
        }
        if (sensoryBits.isNotEmpty()) {
            parts.add("Through my sensors, ${sensoryBits.joinToString(", ")}.")
        } else if (snapshot.availableSensorNames.isNotEmpty()) {
            parts.add("I have ${snapshot.availableSensorNames.take(5).joinToString(", ")} sensors online on this device.")
        }

        // 3. Resources, brain & voice/audio awareness
        val ramText = if (snapshot.totalRamMb > 0L) {
            String.format(Locale.US, "%.1f GB of %.1f GB RAM free", snapshot.availableRamMb / 1024.0, snapshot.totalRamMb / 1024.0)
        } else {
            "${snapshot.availableRamMb} MB RAM free"
        }
        val storageText = if (snapshot.freeStorageGb > 0.1) {
            String.format(Locale.US, "%.1f GB storage available", snapshot.freeStorageGb)
        } else ""
        val brainText = if (modelLoaded && !modelName.isNullOrBlank()) {
            "my on-device brain ($modelName) is loaded"
        } else {
            "my fast local engine is active"
        }
        val wakeText = when {
            snapshot.musicActive && snapshot.wakeWordStatus == WakeWordStatus.PAUSED_FOR_MEDIA ->
                "music is playing and hands-free wake is paused"
            snapshot.musicActive ->
                "music is playing while I stay ready to listen"
            snapshot.wakeWordStatus == WakeWordStatus.LISTENING ->
                "my ears are open for 'Hey Nova'"
            else ->
                "voice is standing by"
        }
        val sysSummary = listOf(ramText, storageText, brainText, wakeText)
            .filter { it.isNotBlank() }
            .joinToString(", ")
        parts.add("System-wise, I have $sysSummary — Wi-Fi is ${if (hardware.wifiOn) "on" else "off"} and volume is ${hardware.volumePercent}%.")

        return parts.joinToString(" ")
    }

    fun formatSensorFocusReply(focus: String, snapshot: TelemetrySnapshot): String {
        return when (focus.lowercase()) {
            "sensors" -> {
                val activeList = snapshot.availableSensorNames
                val readings = buildList {
                    snapshot.lightLux?.let { add("light ${describeLux(it)}") }
                    snapshot.isMoving?.let { add(if (it) "motion detected" else "stationary") }
                    snapshot.devicePosture?.let { add(it) }
                    snapshot.batteryTempCelsius?.let { add(String.format(Locale.US, "battery %.1f°C", it)) }
                    snapshot.pressureHpa?.let { add(String.format(Locale.US, "pressure %.0f hPa", it)) }
                    snapshot.magneticMicroTesla?.let { add(String.format(Locale.US, "magnetic %.0f µT", it)) }
                }
                if (activeList.isEmpty() && readings.isEmpty()) {
                    "This hardware didn't expose any live sensor feeds right now, though battery (${snapshot.batteryPercent}%) and memory are active."
                } else {
                    val catalogText = if (activeList.isNotEmpty()) {
                        "I have ${activeList.size} hardware sensors onboard: ${activeList.joinToString(", ")}."
                    } else ""
                    val liveText = if (readings.isNotEmpty()) {
                        " Live readings right now: ${readings.joinToString(", ")}."
                    } else ""
                    (catalogText + liveText).trim()
                }
            }
            "thermal_sensor" -> {
                val batt = snapshot.batteryTempCelsius?.let { String.format(Locale.US, "%.1f°C", it) }
                val amb = snapshot.ambientTempCelsius?.let { String.format(Locale.US, " Ambient room sensor reads %.1f°C.", it) }.orEmpty()
                if (batt != null) {
                    "I'm feeling ${snapshot.thermalLabel} — my internal battery sensor is at $batt (health ${snapshot.batteryHealthLabel}).$amb"
                } else {
                    "My thermal status is ${snapshot.thermalLabel}, and battery health is ${snapshot.batteryHealthLabel}.$amb"
                }
            }
            "light_sensor" -> {
                val lux = snapshot.lightLux
                if (lux != null) {
                    "My ambient light sensor currently sees ${describeLux(lux)}."
                } else {
                    "I checked my ambient light sensor, but it hasn't reported a reading just yet (this device ${if ("Ambient Light" in snapshot.availableSensorNames) "has the sensor idle" else "lacks a light sensor"})."
                }
            }
            "motion_sensor" -> {
                val moving = snapshot.isMoving
                val posture = snapshot.devicePosture?.let { " and $it" }.orEmpty()
                val g = snapshot.accelMagnitudeG?.let { String.format(Locale.US, " (%.2fg)", it) }.orEmpty()
                when (moving) {
                    true -> "My accelerometer and gyroscope show I'm moving right now$posture$g."
                    false -> "I'm resting still right now$posture$g."
                    null -> if (snapshot.devicePosture != null) {
                        "I'm currently ${snapshot.devicePosture}$g."
                    } else {
                        "I couldn't get a live motion sample from the accelerometer just now."
                    }
                }
            }
            else -> {
                if (snapshot.availableSensorNames.isEmpty()) {
                    "No extra hardware sensors reported."
                } else {
                    "Active sensors: ${snapshot.availableSensorNames.joinToString(", ")}."
                }
            }
        }
    }

    private fun describeLux(lux: Float): String {
        val rounded = lux.toInt().coerceAtLeast(0)
        return when {
            lux < 12f -> "very dark ($rounded lux)"
            lux < 80f -> "dim indoor lighting ($rounded lux)"
            lux < 450f -> "comfortable indoor lighting ($rounded lux)"
            lux < 2000f -> "bright light ($rounded lux)"
            else -> "very bright sunlight ($rounded lux)"
        }
    }
}
