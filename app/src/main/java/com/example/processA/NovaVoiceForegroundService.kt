package com.example.processA

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import java.util.Locale

/**
 * Process A Always-On Foreground Service (`microphone` + `specialUse`).
 * Keeps Process A resident for wake-word reflexes ("Hey Nova"), immediate TTS barge-in ducking,
 * and hands-free spoken command dispatch when wake-word listening is enabled.
 */
class NovaVoiceForegroundService : Service(), TextToSpeech.OnInitListener {

    inner class LocalBinder : Binder() {
        fun getService(): NovaVoiceForegroundService = this@NovaVoiceForegroundService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var wakeRecognizer: SpeechRecognizer? = null
    private var wakeWordEnabled = false
    private var onWakePhraseDetected: ((String) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        tts = TextToSpeech(this, this)
        startForegroundCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_BARGE_IN -> stopSpeakingImmediate()
            ACTION_STOP_SERVICE -> {
                setWakeWordListening(false, null)
                stopSpeakingImmediate()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isTtsReady = true
        }
    }

    fun speak(text: String) {
        if (!isTtsReady || text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "nova_turn_${System.currentTimeMillis()}")
    }

    fun stopSpeakingImmediate() {
        runCatching { tts?.stop() }
    }

    fun setWakeWordListening(enabled: Boolean, callback: ((String) -> Unit)?) {
        wakeWordEnabled = enabled
        onWakePhraseDetected = callback
        mainHandler.post {
            if (enabled) {
                startWakeLoopIfPermitted()
            } else {
                stopWakeRecognizer()
            }
        }
    }

    private fun startWakeLoopIfPermitted() {
        if (!wakeWordEnabled) return
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic || !SpeechRecognizer.isRecognitionAvailable(this)) return

        if (wakeRecognizer == null) {
            wakeRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {
                        // Immediate barge-in ducking when user starts speaking
                        stopSpeakingImmediate()
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        scheduleWakeRestart()
                    }
                    override fun onResults(results: Bundle?) {
                        val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty().trim()
                        handleWakeTranscript(spoken)
                        scheduleWakeRestart()
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty().lowercase()
                        if (partial.contains("stop") || partial.contains("cancel")) {
                            NovaAccessibilityService.abortAutomation("Voice kill switch detected")
                            stopSpeakingImmediate()
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        runCatching { wakeRecognizer?.startListening(intent) }
    }

    private fun handleWakeTranscript(spoken: String) {
        if (spoken.isBlank()) return
        val lower = spoken.lowercase()
        val wakeMatch = Regex("^(?:hey|ok|hi)?\\s*nova[,\\s]*(.*)$").find(lower)
        if (wakeMatch != null) {
            stopSpeakingImmediate()
            val trailingCommand = wakeMatch.groupValues[1].trim()
            if (trailingCommand.isNotEmpty()) {
                onWakePhraseDetected?.invoke(trailingCommand)
            } else {
                onWakePhraseDetected?.invoke("")
            }
        }
    }

    private fun scheduleWakeRestart() {
        if (!wakeWordEnabled) return
        mainHandler.postDelayed({ startWakeLoopIfPermitted() }, 600L)
    }

    private fun stopWakeRecognizer() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            wakeRecognizer?.cancel()
            wakeRecognizer?.destroy()
        }
        wakeRecognizer = null
    }

    private fun startForegroundCompat() {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.voice_service_notification_title))
            .setContentText(getString(R.string.voice_service_notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fgsType = if (hasMicPermission) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            runCatching { startForeground(NOTIFICATION_ID, notification, fgsType) }
                .onFailure { Log.w(TAG, "startForeground fallback: ${it.message}") }
        } else {
            runCatching { startForeground(NOTIFICATION_ID, notification) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nova Always-On Local Voice",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Nova Process A ready for 0ms local reflexes"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopWakeRecognizer()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NovaVoiceFGS"
        private const val CHANNEL_ID = "nova_voice_channel"
        private const val NOTIFICATION_ID = 4041
        const val ACTION_BARGE_IN = "com.example.action.BARGE_IN"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_FGS"

        fun start(context: Context) {
            val intent = Intent(context, NovaVoiceForegroundService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
