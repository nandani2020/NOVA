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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
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
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.AsrEngineOption
import com.example.data.SystemTtsEngineInfo
import com.example.data.SystemTtsVoiceInfo
import com.example.data.WakeWordStatus
import com.example.engine.InputNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Process A Always-On Foreground Service (`microphone` + `specialUse`).
 * Keeps Process A resident for wake-word reflexes ("Hey Nova"), immediate TTS barge-in ducking,
 * and hands-free spoken command dispatch when wake-word listening is enabled.
 */
class NovaVoiceForegroundService : Service(), TextToSpeech.OnInitListener {

    inner class LocalBinder : Binder() {
        fun getService(): NovaVoiceForegroundService = this@NovaVoiceForegroundService
    }

    private data class PendingSpeechRequest(
        val text: String,
        val onDone: (() -> Unit)?
    )

    enum class BargeInAction {
        STOP_ONLY,
        LISTEN_NOW,
        RUN_COMMAND
    }

    data class BargeInDecision(
        val action: BargeInAction,
        val command: String = ""
    )

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile
    private var isTtsReady = false
    @Volatile
    private var isSpeakingTtsActive = false
    @Volatile
    private var currentSpokenText = ""
    @Volatile
    private var activeUtteranceId: String? = null
    @Volatile
    private var pendingSpeechRequest: PendingSpeechRequest? = null
    private val _isSpeakingTts = MutableStateFlow(false)
    val isSpeakingTts: StateFlow<Boolean> = _isSpeakingTts.asStateFlow()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeRecognizer: SpeechRecognizer? = null
    private var followUpRecognizer: SpeechRecognizer? = null
    private var bargeInRecognizer: SpeechRecognizer? = null
    private var isFollowUpCaptureActive = false
    private var isFollowUpRecognizerBusy = false
    private var isBargeInRecognizerBusy = false
    private var followUpSilenceTimeoutMs = 10_000L
    private var followUpConsecutiveFailures = 0
    private var followUpOnReady: (() -> Unit)? = null
    private var followUpOnPartial: ((String) -> Unit)? = null
    private var followUpOnRms: ((Float) -> Unit)? = null
    private var followUpOnResult: ((String) -> Unit)? = null
    private var followUpOnNoMatch: (() -> Unit)? = null
    private var followUpOnFallback: ((String) -> Unit)? = null
    private var wakeWordEnabled = false
    private var pausedForOverlay = false
    private var isListeningActive = false
    private var pendingPartialWakeMatch: String? = null
    @Volatile
    private var isCalibratingWake = false
    private var calibrationRecognizer: SpeechRecognizer? = null
    @Volatile
    private var isDuckingForBargeIn = false
    private var wakeConsecutiveTimeouts = 0
    private var wakeSessionStartedAtMs = 0L
    private var proactivePromptsEnabled = true
    private var pauseWakeDuringMedia = false
    @Volatile
    private var mediaCooldownUntilMs = 0L
    @Volatile
    private var hasActiveExternalMediaConfig = false
    @Volatile
    internal var testMediaPlayingOverride: Boolean? = null
    @Volatile
    private var asrEngineOption: AsrEngineOption = AsrEngineOption.ANDROID_SYSTEM
    @Volatile
    private var preferredTtsEnginePkg: String = ""
    @Volatile
    private var preferredTtsVoiceName: String = ""
    @Volatile
    private var ttsSpeechRate: Float = 1.0f
    @Volatile
    private var ttsPitch: Float = 1.0f
    private var audioManager: AudioManager? = null
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null
    private var activeTtsFocusRequest: AudioFocusRequest? = null
    private var activeMicDuckFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var wasMediaActiveBeforeMic = false
    private var lastNotificationPromptAtMs = 0L
    private var onWakePhraseDetected: ((String) -> Unit)? = null
    private var onBargeInTriggered: ((BargeInDecision) -> Unit)? = null
    private val wakeRestartRunnable = Runnable { startWakeLoopIfPermitted() }
    private val mediaResumePollRunnable = Runnable { pollExternalMediaPlaybackState() }
    private val wakeWatchdogRunnable = Runnable {
        if (wakeWordEnabled && !pausedForOverlay && !isSpeakingTtsActive && !isFollowUpCaptureActive && !isCalibratingWake && isListeningActive) {
            Log.w(TAG, "Wake recognizer stalled > 25s; resetting recognizer instance")
            destroyWakeRecognizerInstance()
            scheduleWakeRestart(delayMs = 250L)
        }
    }
    private val wakePostEndOfSpeechRecoveryRunnable = Runnable {
        if (wakeWordEnabled && !pausedForOverlay && !isSpeakingTtsActive && !isFollowUpCaptureActive && !isCalibratingWake && !isListeningActive) {
            val savedPartial = pendingPartialWakeMatch
            pendingPartialWakeMatch = null
            if (savedPartial != null) {
                Log.i(TAG, "Post-EOS recovery dispatching partial wake match")
                dispatchWakeDetection(savedPartial)
            } else {
                Log.w(TAG, "Wake recognizer stalled after onEndOfSpeech; restarting listener")
                destroyWakeRecognizerInstance()
                scheduleWakeRestart(delayMs = 250L)
            }
        }
    }
    private val followUpStartRunnable = Runnable { startFollowUpRecognizerInternal() }
    private val bargeInStartRunnable = Runnable { startBargeInRecognizerInternal() }
    private val bargeInDuckTimeoutRunnable = Runnable {
        if (isDuckingForBargeIn) {
            isDuckingForBargeIn = false
            handleBargeInDetected(BargeInDecision(BargeInAction.LISTEN_NOW))
        }
    }

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        proactivePromptsEnabled = prefs.getBoolean(PREF_PROACTIVE_PROMPTS, true)
        pauseWakeDuringMedia = prefs.getBoolean(PREF_PAUSE_WAKE_DURING_MEDIA, false)
        asrEngineOption = AsrEngineOption.fromId(prefs.getString(PREF_ASR_ENGINE, null))
        preferredTtsEnginePkg = prefs.getString(PREF_TTS_ENGINE_PKG, "").orEmpty()
        preferredTtsVoiceName = prefs.getString(PREF_TTS_VOICE_NAME, "").orEmpty()
        ttsSpeechRate = prefs.getFloat(PREF_TTS_RATE, 1.0f).coerceIn(0.6f, 1.8f)
        ttsPitch = prefs.getFloat(PREF_TTS_PITCH, 1.0f).coerceIn(0.6f, 1.5f)
        val savedAliases = prefs.getStringSet(PREF_CUSTOM_WAKE_ALIASES, emptySet()).orEmpty()
        val savedSamples = prefs.getInt(PREF_WAKE_CALIBRATION_SAMPLES, 0).coerceIn(0, 3)
        synchronized(customWakeLock) {
            customWakeAliases.clear()
            customWakeAliases.addAll(savedAliases.map { it.trim().lowercase() }.filter { it.length >= 4 })
            _wakeCalibrationAliases.value = customWakeAliases.sorted()
        }
        _wakeCalibrationSamples.value = savedSamples
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        registerAudioPlaybackMonitor()
        createNotificationChannel()
        initTtsEngine(preferredTtsEnginePkg.ifBlank { null })
        startForegroundCompat()
        observeImportantNotifications()
        startBackgroundNotificationAuditLoop()
    }

    private fun initTtsEngine(enginePackage: String?) {
        isTtsReady = false
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = if (!enginePackage.isNullOrBlank()) {
            TextToSpeech(this, this, enginePackage)
        } else {
            TextToSpeech(this, this)
        }
    }

    private fun registerAudioPlaybackMonitor() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                hasActiveExternalMediaConfig = hasExternalMediaInConfigs(am.activePlaybackConfigurations)
                _externalMediaPlaying.value = isExternalMediaPlaying()
                val cb = object : AudioManager.AudioPlaybackCallback() {
                    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                        hasActiveExternalMediaConfig = hasExternalMediaInConfigs(configs.orEmpty())
                        mainHandler.post { onExternalMediaPlaybackChanged() }
                    }
                }
                audioPlaybackCallback = cb
                am.registerAudioPlaybackCallback(cb, mainHandler)
            }
        }
    }

    private fun hasExternalMediaInConfigs(configs: List<AudioPlaybackConfiguration>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return configs.any { cfg ->
            val usage = cfg.audioAttributes.usage
            usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME
        }
    }

    fun isExternalMediaPlaying(): Boolean {
        testMediaPlayingOverride?.let { return it }
        if (System.currentTimeMillis() < mediaCooldownUntilMs) return true
        if (hasActiveExternalMediaConfig) return true
        val am = audioManager ?: return false
        // Ignore isMusicActive if only Nova's own TTS is actively speaking and no USAGE_MEDIA stream exists
        return runCatching { am.isMusicActive && !isSpeakingTtsActive }.getOrDefault(false)
    }

    fun setPauseWakeDuringMedia(enabled: Boolean) {
        pauseWakeDuringMedia = enabled
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_PAUSE_WAKE_DURING_MEDIA, enabled)
            .apply()
        mainHandler.post {
            onExternalMediaPlaybackChanged()
        }
    }

    fun notifyMediaPlaybackStarted(graceWindowMs: Long = 5_000L) {
        mediaCooldownUntilMs = System.currentTimeMillis() + graceWindowMs.coerceAtLeast(1_500L)
        _externalMediaPlaying.value = true
        mainHandler.post {
            if (pauseWakeDuringMedia) {
                stopBargeInRecognizer()
                if (wakeWordEnabled) {
                    stopWakeRecognizer()
                    if (!pausedForOverlay) {
                        _wakeWordStatus.value = WakeWordStatus.PAUSED_FOR_MEDIA
                    }
                    scheduleMediaResumePoll()
                }
            } else if (wakeWordEnabled &&
                !pausedForOverlay &&
                !isSpeakingTtsActive &&
                !isFollowUpCaptureActive &&
                !isDuckingForBargeIn
            ) {
                ensureWakeLoopActiveIfIdle()
            }
        }
    }

    private fun onExternalMediaPlaybackChanged() {
        val playing = isExternalMediaPlaying()
        _externalMediaPlaying.value = playing
        if (playing) {
            if (pauseWakeDuringMedia) {
                stopBargeInRecognizer()
                if (wakeWordEnabled && !pausedForOverlay && !isFollowUpCaptureActive) {
                    stopWakeRecognizer()
                    _wakeWordStatus.value = WakeWordStatus.PAUSED_FOR_MEDIA
                    scheduleMediaResumePoll()
                }
            } else {
                mainHandler.removeCallbacks(mediaResumePollRunnable)
                if (wakeWordEnabled &&
                    !pausedForOverlay &&
                    !isSpeakingTtsActive &&
                    !isFollowUpCaptureActive &&
                    !isDuckingForBargeIn &&
                    _wakeWordStatus.value == WakeWordStatus.PAUSED_FOR_MEDIA
                ) {
                    startWakeLoopIfPermitted()
                }
            }
        } else {
            mainHandler.removeCallbacks(mediaResumePollRunnable)
            if (wakeWordEnabled &&
                !pausedForOverlay &&
                !isSpeakingTtsActive &&
                !isFollowUpCaptureActive &&
                !isDuckingForBargeIn &&
                _wakeWordStatus.value == WakeWordStatus.PAUSED_FOR_MEDIA
            ) {
                if (pauseWakeDuringMedia) {
                    scheduleWakeRestart(delayMs = 500L)
                } else {
                    startWakeLoopIfPermitted()
                }
            }
        }
    }

    private fun scheduleMediaResumePoll() {
        mainHandler.removeCallbacks(mediaResumePollRunnable)
        mainHandler.postDelayed(mediaResumePollRunnable, MEDIA_RESUME_POLL_MS)
    }

    private fun pollExternalMediaPlaybackState() {
        if (!wakeWordEnabled || !pauseWakeDuringMedia) {
            if (wakeWordEnabled && _wakeWordStatus.value == WakeWordStatus.PAUSED_FOR_MEDIA && !pausedForOverlay) {
                scheduleWakeRestart(delayMs = 350L)
            }
            return
        }
        val playing = isExternalMediaPlaying()
        _externalMediaPlaying.value = playing
        if (playing) {
            if (!pausedForOverlay && _wakeWordStatus.value != WakeWordStatus.PAUSED_FOR_MEDIA) {
                stopWakeRecognizer()
                _wakeWordStatus.value = WakeWordStatus.PAUSED_FOR_MEDIA
            }
            scheduleMediaResumePoll()
        } else if (!pausedForOverlay && !isSpeakingTtsActive && !isFollowUpCaptureActive && !isDuckingForBargeIn) {
            scheduleWakeRestart(delayMs = 450L)
        }
    }

    fun setAsrEngineOption(option: AsrEngineOption) {
        if (asrEngineOption == option) return
        asrEngineOption = option
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_ASR_ENGINE, option.id)
            .apply()
        mainHandler.post {
            val wasWakeActive = isListeningActive
            destroyWakeRecognizerInstance()
            destroyBargeInRecognizerInstance()
            if (wasWakeActive && wakeWordEnabled && !pausedForOverlay && !isSpeakingTtsActive) {
                scheduleWakeRestart(delayMs = 300L)
            }
        }
    }

    fun getAvailableSystemTtsEngines(): List<SystemTtsEngineInfo> {
        return runCatching {
            tts?.engines.orEmpty().map { engine ->
                SystemTtsEngineInfo(
                    packageName = engine.name.orEmpty(),
                    label = engine.label?.takeIf { it.isNotBlank() } ?: engine.name.orEmpty()
                )
            }.filter { it.packageName.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun getAvailableSystemTtsVoices(): List<SystemTtsVoiceInfo> {
        return runCatching {
            val defaultLang = Locale.getDefault().language
            tts?.voices.orEmpty()
                .filter { !it.isNetworkConnectionRequired || it.locale != null }
                .sortedWith(
                    compareByDescending<android.speech.tts.Voice> {
                        it.locale?.language.equals(defaultLang, ignoreCase = true) ||
                            it.locale?.language.equals("en", ignoreCase = true)
                    }.thenBy { it.isNetworkConnectionRequired }
                        .thenBy { it.name }
                )
                .take(24)
                .map { v ->
                    val localeTag = v.locale?.toLanguageTag().orEmpty().ifBlank { "default" }
                    val cleanName = v.name
                        .replace("en-us-x-", "EN-US ")
                        .replace("en-gb-x-", "EN-GB ")
                        .replace("-local", " (Offline)")
                        .replace("-network", " (HD)")
                    SystemTtsVoiceInfo(
                        name = v.name,
                        displayName = "$cleanName [$localeTag]",
                        localeTag = localeTag,
                        isNetworkRequired = v.isNetworkConnectionRequired
                    )
                }
        }.getOrDefault(emptyList())
    }

    fun configureTts(
        enginePackage: String?,
        voiceName: String?,
        speechRate: Float,
        pitch: Float
    ) {
        val normPkg = enginePackage.orEmpty().trim()
        val normVoice = voiceName.orEmpty().trim()
        val clampedRate = speechRate.coerceIn(0.6f, 1.8f)
        val clampedPitch = pitch.coerceIn(0.6f, 1.5f)
        val engineChanged = normPkg != preferredTtsEnginePkg

        preferredTtsEnginePkg = normPkg
        preferredTtsVoiceName = normVoice
        ttsSpeechRate = clampedRate
        ttsPitch = clampedPitch

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_TTS_ENGINE_PKG, normPkg)
            .putString(PREF_TTS_VOICE_NAME, normVoice)
            .putFloat(PREF_TTS_RATE, clampedRate)
            .putFloat(PREF_TTS_PITCH, clampedPitch)
            .apply()

        mainHandler.post {
            if (engineChanged) {
                initTtsEngine(normPkg.ifBlank { null })
            } else if (isTtsReady) {
                applyTtsVoiceParameters()
            }
        }
    }

    private fun applyTtsVoiceParameters() {
        val engine = tts ?: return
        runCatching { engine.setSpeechRate(ttsSpeechRate) }
        runCatching { engine.setPitch(ttsPitch) }
        if (preferredTtsVoiceName.isNotBlank()) {
            runCatching {
                val match = engine.voices?.firstOrNull { it.name == preferredTtsVoiceName }
                if (match != null) {
                    engine.voice = match
                }
            }
        }
    }

    fun speakTestSample(sampleText: String = "Hi, I'm Nova. Here is how my voice sounds right now."): Boolean {
        return speak(sampleText)
    }

    private fun createConfiguredSpeechRecognizer(): SpeechRecognizer? {
        val standardAvail = SpeechRecognizer.isRecognitionAvailable(this)
        val onDeviceAvail = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(this) }.getOrDefault(false)
        if (!standardAvail && !onDeviceAvail) return null
        return runCatching {
            val preferOnDevice = asrEngineOption == AsrEngineOption.ANDROID_ON_DEVICE
            when {
                preferOnDevice && onDeviceAvail && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                standardAvail -> SpeechRecognizer.createSpeechRecognizer(this)
                onDeviceAvail && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
                else -> null
            }
        }.getOrNull()
    }

    private fun requestTtsDuckingAudioFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeTtsFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                activeTtsFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    private fun abandonTtsAudioFocus() {
        val am = audioManager ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeTtsFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                activeTtsFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        }
    }

    /**
     * Requests transient ducking focus so background music dims gently instead of stopping
     * while Nova listens for a spoken command.
     */
    fun beginConcurrentMicSession() {
        val am = audioManager
        val playingNow = isExternalMediaPlaying() ||
            runCatching { am?.isMusicActive == true }.getOrDefault(false)
        if (playingNow) {
            wasMediaActiveBeforeMic = true
        }
        if (!playingNow || am == null) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeMicDuckFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { }
                    .build()
                activeMicDuckFocusRequest = req
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    fun endConcurrentMicSession(resumeInterruptedMediaIfNeeded: Boolean = false) {
        val am = audioManager
        if (am != null) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activeMicDuckFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                    activeMicDuckFocusRequest = null
                } else {
                    @Suppress("DEPRECATION")
                    am.abandonAudioFocus(null)
                }
            }
        }
        val shouldRestore = resumeInterruptedMediaIfNeeded && wasMediaActiveBeforeMic
        wasMediaActiveBeforeMic = false
        if (shouldRestore && am != null) {
            val activeNow = runCatching { am.isMusicActive }.getOrDefault(false)
            if (!activeNow) {
                runCatching {
                    val now = android.os.SystemClock.uptimeMillis()
                    am.dispatchMediaKeyEvent(
                        android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0)
                    )
                    am.dispatchMediaKeyEvent(
                        android.view.KeyEvent(now, now, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY, 0)
                    )
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
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

    private val utteranceCallbacks = ConcurrentHashMap<String, () -> Unit>()
    private val utteranceSeq = AtomicLong(0L)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            runCatching {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    handleUtteranceCompleted(utteranceId)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    handleUtteranceCompleted(utteranceId)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    handleUtteranceCompleted(utteranceId)
                }
            })
            applyTtsVoiceParameters()
            isTtsReady = true
            val pending = pendingSpeechRequest
            pendingSpeechRequest = null
            if (pending != null) {
                speak(pending.text, pending.onDone)
            }
        } else {
            val pending = pendingSpeechRequest
            pendingSpeechRequest = null
            isSpeakingTtsActive = false
            _isSpeakingTts.value = false
            abandonTtsAudioFocus()
            if (pending?.onDone != null) {
                mainHandler.post(pending.onDone)
            }
        }
    }

    private fun handleUtteranceCompleted(utteranceId: String?) {
        val cb = utteranceId?.let { utteranceCallbacks.remove(it) }
        if (utteranceId == null || utteranceId == activeUtteranceId) {
            activeUtteranceId = null
            currentSpokenText = ""
            isSpeakingTtsActive = false
            _isSpeakingTts.value = false
            abandonTtsAudioFocus()
            if (!isDuckingForBargeIn) {
                mainHandler.post { stopBargeInRecognizer() }
            }
        }
        if (cb != null) {
            mainHandler.post(cb)
        }
        if (wakeWordEnabled && !pausedForOverlay && !isSpeakingTtsActive && !isFollowUpCaptureActive && !isDuckingForBargeIn) {
            mainHandler.post { scheduleWakeRestart(delayMs = 450L) }
        }
    }

    fun isCurrentlySpeaking(): Boolean = isSpeakingTtsActive || pendingSpeechRequest != null

    fun setBargeInCallback(callback: ((BargeInDecision) -> Unit)?) {
        onBargeInTriggered = callback
    }

    fun startOneShotCommandCapture(
        onReady: () -> Unit = {},
        onPartial: (String) -> Unit = {},
        onRms: (Float) -> Unit = {},
        onResult: (String) -> Unit,
        onErrorFallback: (String) -> Unit = {}
    ) {
        beginConcurrentMicSession()
        startFollowUpCapture(
            silenceTimeoutMs = 3_500L,
            onReady = onReady,
            onPartial = onPartial,
            onRms = onRms,
            onResult = { spoken ->
                onResult(spoken)
            },
            onNoMatchOrTimeout = {},
            onErrorFallback = { msg ->
                endConcurrentMicSession(resumeInterruptedMediaIfNeeded = true)
                onErrorFallback(msg)
            }
        )
    }

    /**
     * Captures a single follow-up voice reply directly inside the microphone ForegroundService so
     * follow-up prompts work even when an external app (YouTube, Chrome, Settings, etc.) is on screen
     * or when the Activity-scoped SpeechRecognizer is unavailable/busy.
     */
    fun startFollowUpCapture(
        silenceTimeoutMs: Long = 10_000L,
        onReady: () -> Unit = {},
        onPartial: (String) -> Unit = {},
        onRms: (Float) -> Unit = {},
        onResult: (String) -> Unit,
        onNoMatchOrTimeout: () -> Unit = {},
        onErrorFallback: (String) -> Unit = {}
    ) {
        mainHandler.post {
            beginConcurrentMicSession()
            val hadCompeteRecognizer = wakeRecognizer != null ||
                isListeningActive ||
                bargeInRecognizer != null ||
                isBargeInRecognizerBusy
            stopWakeRecognizer()
            stopBargeInRecognizer()
            mainHandler.removeCallbacks(followUpStartRunnable)
            destroyFollowUpRecognizerInstance()
            isFollowUpCaptureActive = true
            isFollowUpRecognizerBusy = false
            followUpSilenceTimeoutMs = silenceTimeoutMs.coerceAtLeast(3_000L)
            followUpConsecutiveFailures = 0
            followUpOnReady = onReady
            followUpOnPartial = onPartial
            followUpOnRms = onRms
            followUpOnResult = onResult
            followUpOnNoMatch = onNoMatchOrTimeout
            followUpOnFallback = onErrorFallback

            val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (!hasMic) {
                isFollowUpCaptureActive = false
                onErrorFallback("Microphone permission required for follow-up listening")
                return@post
            }
            // Wait long enough for previous SpeechRecognizer binder & AudioRecord session to release cleanly
            mainHandler.postDelayed(followUpStartRunnable, if (hadCompeteRecognizer) 360L else 120L)
        }
    }

    fun stopFollowUpCapture() {
        mainHandler.post {
            isFollowUpCaptureActive = false
            isFollowUpRecognizerBusy = false
            mainHandler.removeCallbacks(followUpStartRunnable)
            followUpOnReady = null
            followUpOnPartial = null
            followUpOnRms = null
            followUpOnResult = null
            followUpOnNoMatch = null
            followUpOnFallback = null
            destroyFollowUpRecognizerInstance()
            if (wakeWordEnabled && !pausedForOverlay && !isSpeakingTtsActive) {
                scheduleWakeRestart(delayMs = 400L)
            }
        }
    }

    private fun startFollowUpRecognizerInternal() {
        if (!isFollowUpCaptureActive || isSpeakingTtsActive || isFollowUpRecognizerBusy) return
        val standardAvail = SpeechRecognizer.isRecognitionAvailable(this)
        val onDeviceAvail = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(this) }.getOrDefault(false)
        if (!standardAvail && !onDeviceAvail) {
            isFollowUpCaptureActive = false
            followUpOnFallback?.invoke("Speech recognition service unavailable on device")
            return
        }

        if (followUpRecognizer == null) {
            followUpRecognizer = createConfiguredSpeechRecognizer()?.apply {
                setRecognitionListener(createFollowUpListener())
            }
        }

        val recognizer = followUpRecognizer
        if (recognizer == null) {
            isFollowUpCaptureActive = false
            followUpOnFallback?.invoke("Unable to initialize speech recognizer")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, followUpSilenceTimeoutMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, followUpSilenceTimeoutMs)
        }
        isFollowUpRecognizerBusy = true
        runCatching { recognizer.startListening(intent) }
            .onFailure { ex ->
                Log.w(TAG, "FollowUp startListening failed: ${ex.message}")
                isFollowUpRecognizerBusy = false
                destroyFollowUpRecognizerInstance()
                followUpConsecutiveFailures++
                if (isFollowUpCaptureActive && followUpConsecutiveFailures <= 4) {
                    mainHandler.postDelayed(followUpStartRunnable, 350L)
                } else {
                    isFollowUpCaptureActive = false
                    followUpOnFallback?.invoke("Could not open microphone")
                }
            }
    }

    private fun createFollowUpListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (!isFollowUpCaptureActive) return
            followUpConsecutiveFailures = 0
            followUpOnReady?.invoke()
        }

        override fun onBeginningOfSpeech() {
            if (!isFollowUpCaptureActive) return
            followUpOnRms?.invoke(0.68f)
        }

        override fun onRmsChanged(rmsdB: Float) {
            if (!isFollowUpCaptureActive) return
            val norm = ((rmsdB + 2f) / 12f).coerceIn(0.2f, 1.0f)
            followUpOnRms?.invoke(norm)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isFollowUpRecognizerBusy = false
        }

        override fun onError(error: Int) {
            isFollowUpRecognizerBusy = false
            if (!isFollowUpCaptureActive) return
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                isFollowUpCaptureActive = false
                followUpOnFallback?.invoke("Microphone permission denied")
                return
            }
            val hardwareOrBusyError = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_AUDIO ||
                error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                error == 11 // ERROR_SERVER_DISCONNECTED
            // Always destroy and recreate before retrying so vendor SpeechRecognizer never wedges mid-countdown
            destroyFollowUpRecognizerInstance()
            if (hardwareOrBusyError) {
                followUpConsecutiveFailures++
            } else {
                followUpOnNoMatch?.invoke()
            }
            if (isFollowUpCaptureActive && !isSpeakingTtsActive && followUpConsecutiveFailures <= 4) {
                val delayMs = if (hardwareOrBusyError) 340L else 180L
                mainHandler.removeCallbacks(followUpStartRunnable)
                mainHandler.postDelayed(followUpStartRunnable, delayMs)
            } else if (followUpConsecutiveFailures > 4) {
                isFollowUpCaptureActive = false
                followUpOnFallback?.invoke("Microphone speech service busy (error $error)")
            }
        }

        override fun onResults(results: Bundle?) {
            isFollowUpRecognizerBusy = false
            if (!isFollowUpCaptureActive) return
            val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty().trim()
            if (spoken.isNotBlank()) {
                val cb = followUpOnResult
                stopFollowUpCapture()
                cb?.invoke(spoken)
            } else {
                followUpOnNoMatch?.invoke()
                if (isFollowUpCaptureActive && !isSpeakingTtsActive) {
                    mainHandler.removeCallbacks(followUpStartRunnable)
                    mainHandler.postDelayed(followUpStartRunnable, 180L)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isFollowUpCaptureActive) return
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (partial.isNotBlank()) {
                followUpOnPartial?.invoke(partial)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun destroyFollowUpRecognizerInstance() {
        isFollowUpRecognizerBusy = false
        runCatching {
            followUpRecognizer?.cancel()
            followUpRecognizer?.destroy()
        }
        followUpRecognizer = null
    }

    fun setProactiveNotificationPromptsEnabled(enabled: Boolean) {
        proactivePromptsEnabled = enabled
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_PROACTIVE_PROMPTS, enabled)
            .apply()
    }

    private fun observeImportantNotifications() {
        serviceScope.launch {
            NovaNotificationListenerService.incomingImportantAlerts.collect { alert ->
                if (!proactivePromptsEnabled || pausedForOverlay) return@collect
                // Do not interrupt active music/video playback with proactive notification speech
                if (isExternalMediaPlaying()) return@collect
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val inDnd = nm != null && nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                if (inDnd) return@collect

                val now = System.currentTimeMillis()
                if (now - lastNotificationPromptAtMs < 4000L) return@collect
                lastNotificationPromptAtMs = now

                speak(alert.spokenPrompt)
            }
        }
    }

    private fun startBackgroundNotificationAuditLoop() {
        serviceScope.launch(Dispatchers.IO) {
            val fts = com.example.data.LocalFtsIndexHelper(applicationContext)
            try {
                while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                    val now = System.currentTimeMillis()
                    runCatching {
                        NovaNotificationListenerService.syncActiveNotificationsToArchive(fts, now)
                    }
                    kotlinx.coroutines.delay(15L * 60_000L)
                }
            } finally {
                runCatching { fts.close() }
            }
        }
    }

    fun speak(text: String, onDone: (() -> Unit)? = null): Boolean {
        if (text.isBlank()) return false
        isDuckingForBargeIn = false
        mainHandler.removeCallbacks(bargeInDuckTimeoutRunnable)
        currentSpokenText = text
        isSpeakingTtsActive = true
        _isSpeakingTts.value = true
        // Pause wake recognizer cleanly with full HAL release margin, then arm echo-filtered barge-in mic
        // Skip barge-in only when pauseWakeDuringMedia is enabled AND external media is playing
        val mediaPlayingNow = isExternalMediaPlaying()
        val suppressBargeIn = pauseWakeDuringMedia && mediaPlayingNow
        mainHandler.post {
            val hadWakeActive = wakeRecognizer != null || isListeningActive
            stopWakeRecognizer()
            if (!isFollowUpCaptureActive && !suppressBargeIn) {
                mainHandler.removeCallbacks(bargeInStartRunnable)
                mainHandler.postDelayed(bargeInStartRunnable, if (hadWakeActive) 380L else 240L)
            } else {
                stopBargeInRecognizer()
            }
        }

        if (!isTtsReady) {
            pendingSpeechRequest = PendingSpeechRequest(text, onDone)
            return true
        }

        pendingSpeechRequest = null
        activeUtteranceId?.let { prev -> utteranceCallbacks.remove(prev) }
        val uttId = "nova_turn_${System.currentTimeMillis()}_${utteranceSeq.incrementAndGet()}"
        activeUtteranceId = uttId
        if (onDone != null) {
            utteranceCallbacks[uttId] = onDone
        }
        requestTtsDuckingAudioFocus()
        applyTtsVoiceParameters()
        val queued = runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, uttId) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        if (!queued) {
            utteranceCallbacks.remove(uttId)
            activeUtteranceId = null
            currentSpokenText = ""
            isSpeakingTtsActive = false
            _isSpeakingTts.value = false
            abandonTtsAudioFocus()
            mainHandler.post { stopBargeInRecognizer() }
            if (wakeWordEnabled && !pausedForOverlay) {
                scheduleWakeRestart(delayMs = 400L)
            }
        }
        return queued
    }

    /**
     * Silences TTS audio immediately on partial wake/barge-in detection while keeping the
     * barge-in SpeechRecognizer open so the user's trailing command finishes without clipping.
     */
    private fun duckTtsAudioOnly() {
        isDuckingForBargeIn = true
        pendingSpeechRequest = null
        activeUtteranceId = null
        currentSpokenText = ""
        utteranceCallbacks.clear()
        isSpeakingTtsActive = false
        _isSpeakingTts.value = false
        runCatching { tts?.stop() }
        abandonTtsAudioFocus()
        mainHandler.removeCallbacks(bargeInDuckTimeoutRunnable)
        mainHandler.postDelayed(bargeInDuckTimeoutRunnable, 3_500L)
    }

    fun stopSpeakingImmediate() {
        isDuckingForBargeIn = false
        mainHandler.removeCallbacks(bargeInDuckTimeoutRunnable)
        pendingSpeechRequest = null
        activeUtteranceId = null
        currentSpokenText = ""
        utteranceCallbacks.clear()
        isSpeakingTtsActive = false
        _isSpeakingTts.value = false
        mainHandler.post { stopBargeInRecognizer() }
        runCatching { tts?.stop() }
        abandonTtsAudioFocus()
        if (wakeWordEnabled && !pausedForOverlay && !isFollowUpCaptureActive) {
            scheduleWakeRestart(delayMs = 400L)
        }
    }

    private fun startBargeInRecognizerInternal() {
        if (!isSpeakingTtsActive || isFollowUpCaptureActive || isBargeInRecognizerBusy) return
        if (pauseWakeDuringMedia && isExternalMediaPlaying()) {
            stopBargeInRecognizer()
            return
        }
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic) return

        if (bargeInRecognizer == null) {
            bargeInRecognizer = createConfiguredSpeechRecognizer()?.apply {
                setRecognitionListener(createBargeInListener())
            }
        }

        val recognizer = bargeInRecognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 350L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 550L)
        }
        isBargeInRecognizerBusy = true
        runCatching { recognizer.startListening(intent) }
            .onFailure {
                isBargeInRecognizerBusy = false
                destroyBargeInRecognizerInstance()
                if (isSpeakingTtsActive && !isFollowUpCaptureActive) {
                    mainHandler.removeCallbacks(bargeInStartRunnable)
                    mainHandler.postDelayed(bargeInStartRunnable, 360L)
                }
            }
    }

    private fun createBargeInListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isBargeInRecognizerBusy = false
        }

        override fun onError(error: Int) {
            isBargeInRecognizerBusy = false
            if (isFollowUpCaptureActive) return
            if (isDuckingForBargeIn) {
                handleBargeInDetected(BargeInDecision(BargeInAction.LISTEN_NOW))
                return
            }
            if (!isSpeakingTtsActive) return
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) return
            val needsRecreate = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_AUDIO
            if (needsRecreate) {
                destroyBargeInRecognizerInstance()
            }
            mainHandler.removeCallbacks(bargeInStartRunnable)
            mainHandler.postDelayed(bargeInStartRunnable, if (needsRecreate) 320L else 180L)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!isSpeakingTtsActive && !isDuckingForBargeIn) return
            val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (partial.isBlank()) return
            val decision = evaluateBargeInIntent(partial, currentSpokenText, isPartial = true)
            if (decision != null &&
                (decision.action == BargeInAction.STOP_ONLY || decision.action == BargeInAction.LISTEN_NOW)
            ) {
                handleBargeInDetected(decision)
                return
            }
            // If user already started saying "Hey Nova <cmd>" or "Nova <cmd>", silence TTS immediately
            // while keeping bargeInRecognizer open to receive the full command in onResults.
            if (isSpeakingTtsActive && extractWakeCommand(partial) != null) {
                duckTtsAudioOnly()
            }
        }

        override fun onResults(results: Bundle?) {
            isBargeInRecognizerBusy = false
            val wasDucking = isDuckingForBargeIn
            if (!isSpeakingTtsActive && !wasDucking) return
            val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            for (spoken in candidates) {
                val decision = evaluateBargeInIntent(spoken, currentSpokenText, isPartial = false)
                if (decision != null) {
                    handleBargeInDetected(decision)
                    return
                }
            }
            if (wasDucking) {
                handleBargeInDetected(BargeInDecision(BargeInAction.LISTEN_NOW))
                return
            }
            mainHandler.removeCallbacks(bargeInStartRunnable)
            mainHandler.postDelayed(bargeInStartRunnable, 160L)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun handleBargeInDetected(decision: BargeInDecision) {
        val activeOrDucking = isSpeakingTtsActive || isDuckingForBargeIn
        if (!activeOrDucking && decision.action == BargeInAction.STOP_ONLY) return
        stopSpeakingImmediate()
        val cb = onBargeInTriggered
        if (cb != null) {
            mainHandler.post { cb.invoke(decision) }
        } else if (decision.action != BargeInAction.STOP_ONLY) {
            dispatchWakeDetection(decision.command)
        }
    }

    private fun destroyBargeInRecognizerInstance() {
        isBargeInRecognizerBusy = false
        runCatching {
            bargeInRecognizer?.cancel()
            bargeInRecognizer?.destroy()
        }
        bargeInRecognizer = null
    }

    private fun stopBargeInRecognizer() {
        mainHandler.removeCallbacks(bargeInStartRunnable)
        destroyBargeInRecognizerInstance()
    }

    fun setWakeWordListening(enabled: Boolean, callback: ((String) -> Unit)?) {
        wakeWordEnabled = enabled
        onWakePhraseDetected = callback
        mainHandler.post {
            if (enabled) {
                startForegroundCompat()
                if (!pausedForOverlay) {
                    startWakeLoopIfPermitted()
                } else {
                    _wakeWordStatus.value = WakeWordStatus.PAUSED_FOR_OVERLAY
                }
            } else {
                stopWakeRecognizer()
                _wakeWordStatus.value = WakeWordStatus.OFF
            }
        }
    }

    /**
     * Pauses background "Hey Nova" recognition while the Voice Overlay active mic is capturing
     * so two SpeechRecognizer instances do not compete for the microphone.
     */
    fun setOverlayListeningPaused(paused: Boolean) {
        pausedForOverlay = paused
        mainHandler.post {
            if (paused) {
                stopWakeRecognizer()
                if (wakeWordEnabled) {
                    _wakeWordStatus.value = WakeWordStatus.PAUSED_FOR_OVERLAY
                }
            } else if (wakeWordEnabled && !isSpeakingTtsActive && !isFollowUpCaptureActive) {
                scheduleWakeRestart(delayMs = 350L)
            }
        }
    }

    fun ensureWakeLoopActiveIfIdle() {
        mainHandler.post {
            if (!wakeWordEnabled || pausedForOverlay || isSpeakingTtsActive || isFollowUpCaptureActive || isDuckingForBargeIn) {
                return@post
            }
            val now = System.currentTimeMillis()
            if (isListeningActive && wakeSessionStartedAtMs > 0L && now - wakeSessionStartedAtMs > WAKE_STALL_WATCHDOG_MS) {
                destroyWakeRecognizerInstance()
            }
            if (!isListeningActive) {
                startWakeLoopIfPermitted()
            }
        }
    }

    fun captureWakeCalibrationSample(
        onComplete: (sampleCount: Int, learnedAliases: List<String>, heardPrimary: String) -> Unit,
        onError: (String) -> Unit
    ) {
        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            onError("Microphone permission is needed to calibrate 'Hey Nova'.")
            return
        }
        mainHandler.post {
            isCalibratingWake = true
            stopWakeRecognizer()
            destroyCalibrationRecognizer()
            val recognizer = createConfiguredSpeechRecognizer()
            if (recognizer == null) {
                isCalibratingWake = false
                if (wakeWordEnabled && !pausedForOverlay) scheduleWakeRestart(delayMs = 350L)
                onError("Speech recognizer is unavailable on this device.")
                return@post
            }
            calibrationRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    destroyCalibrationRecognizer()
                    isCalibratingWake = false
                    if (wakeWordEnabled && !pausedForOverlay) scheduleWakeRestart(delayMs = 400L)
                    onError("Couldn't hear 'Hey Nova' clearly — tap Record Sample and try again.")
                }

                override fun onResults(results: Bundle?) {
                    val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    destroyCalibrationRecognizer()
                    isCalibratingWake = false
                    if (candidates.isEmpty()) {
                        if (wakeWordEnabled && !pausedForOverlay) scheduleWakeRestart(delayMs = 400L)
                        onError("No voice captured — please say 'Hey Nova' clearly.")
                        return
                    }
                    val learned = recordCalibrationTranscripts(candidates)
                    val newCount = (_wakeCalibrationSamples.value + 1).coerceAtMost(3)
                    _wakeCalibrationSamples.value = newCount
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(PREF_WAKE_CALIBRATION_SAMPLES, newCount)
                        .putStringSet(PREF_CUSTOM_WAKE_ALIASES, learned.toSet())
                        .apply()
                    if (wakeWordEnabled && !pausedForOverlay) scheduleWakeRestart(delayMs = 400L)
                    onComplete(newCount, learned, candidates.first())
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1300L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
            }
            runCatching { recognizer.startListening(intent) }
                .onFailure {
                    destroyCalibrationRecognizer()
                    isCalibratingWake = false
                    if (wakeWordEnabled && !pausedForOverlay) scheduleWakeRestart(delayMs = 400L)
                    onError("Could not start microphone for voice calibration.")
                }
        }
    }

    fun clearWakeVoiceCalibration() {
        synchronized(customWakeLock) {
            customWakeAliases.clear()
        }
        _wakeCalibrationAliases.value = emptyList()
        _wakeCalibrationSamples.value = 0
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_CUSTOM_WAKE_ALIASES)
            .putInt(PREF_WAKE_CALIBRATION_SAMPLES, 0)
            .apply()
    }

    private fun destroyCalibrationRecognizer() {
        runCatching {
            calibrationRecognizer?.cancel()
            calibrationRecognizer?.destroy()
        }
        calibrationRecognizer = null
    }

    private fun startWakeLoopIfPermitted() {
        if (!wakeWordEnabled || pausedForOverlay || isSpeakingTtsActive || isFollowUpCaptureActive || isDuckingForBargeIn || isCalibratingWake) return

        if (pauseWakeDuringMedia && isExternalMediaPlaying()) {
            _externalMediaPlaying.value = true
            stopWakeRecognizer()
            _wakeWordStatus.value = WakeWordStatus.PAUSED_FOR_MEDIA
            scheduleMediaResumePoll()
            return
        }
        _externalMediaPlaying.value = isExternalMediaPlaying()

        val hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasMic) {
            _wakeWordStatus.value = WakeWordStatus.MIC_PERMISSION_NEEDED
            return
        }

        val standardAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        val onDeviceAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(this) }.getOrDefault(false)
        if (!standardAvailable && !onDeviceAvailable) {
            _wakeWordStatus.value = WakeWordStatus.UNAVAILABLE
            return
        }

        if (isListeningActive) return

        if (wakeRecognizer == null) {
            wakeRecognizer = createConfiguredSpeechRecognizer()?.apply {
                setRecognitionListener(createWakeListener())
            }
        }

        val recognizer = wakeRecognizer
        if (recognizer == null) {
            _wakeWordStatus.value = WakeWordStatus.UNAVAILABLE
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
        }
        pendingPartialWakeMatch = null
        isListeningActive = true
        wakeSessionStartedAtMs = System.currentTimeMillis()
        mainHandler.removeCallbacks(wakeWatchdogRunnable)
        mainHandler.removeCallbacks(wakePostEndOfSpeechRecoveryRunnable)
        mainHandler.postDelayed(wakeWatchdogRunnable, WAKE_STALL_WATCHDOG_MS)
        _wakeWordStatus.value = WakeWordStatus.LISTENING
        runCatching { recognizer.startListening(intent) }
            .onFailure { ex ->
                Log.w(TAG, "Wake startListening failed: ${ex.message}")
                isListeningActive = false
                mainHandler.removeCallbacks(wakeWatchdogRunnable)
                destroyWakeRecognizerInstance()
                scheduleWakeRestart(delayMs = 800L)
            }
    }

    private fun createWakeListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            if (wakeWordEnabled && !pausedForOverlay) {
                _wakeWordStatus.value = WakeWordStatus.LISTENING
            }
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            isListeningActive = false
            mainHandler.removeCallbacks(wakeWatchdogRunnable)
            // Guard against vendor SpeechRecognizer silently stalling after onEndOfSpeech without delivering onResults/onError
            mainHandler.removeCallbacks(wakePostEndOfSpeechRecoveryRunnable)
            mainHandler.postDelayed(wakePostEndOfSpeechRecoveryRunnable, WAKE_POST_EOS_RECOVERY_MS)
        }

        override fun onError(error: Int) {
            isListeningActive = false
            mainHandler.removeCallbacks(wakeWatchdogRunnable)
            mainHandler.removeCallbacks(wakePostEndOfSpeechRecoveryRunnable)
            val savedPartialWake = pendingPartialWakeMatch
            pendingPartialWakeMatch = null
            if (savedPartialWake != null &&
                (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
            ) {
                wakeConsecutiveTimeouts = 0
                dispatchWakeDetection(savedPartialWake)
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    _wakeWordStatus.value = WakeWordStatus.MIC_PERMISSION_NEEDED
                    stopWakeRecognizer()
                }
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_AUDIO -> {
                    wakeConsecutiveTimeouts = 0
                    destroyWakeRecognizerInstance()
                    scheduleWakeRestart(delayMs = 700L)
                }
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    wakeConsecutiveTimeouts++
                    // Periodically recycle SpeechRecognizer so vendor ASR HAL never silently stops listening
                    if (wakeConsecutiveTimeouts >= 4) {
                        wakeConsecutiveTimeouts = 0
                        destroyWakeRecognizerInstance()
                    }
                    val restartDelay = if (isExternalMediaPlaying()) 650L else 200L
                    scheduleWakeRestart(delayMs = restartDelay)
                }
                else -> {
                    wakeConsecutiveTimeouts = 0
                    destroyWakeRecognizerInstance()
                    scheduleWakeRestart(delayMs = 700L)
                }
            }
        }

        override fun onResults(results: Bundle?) {
            isListeningActive = false
            mainHandler.removeCallbacks(wakeWatchdogRunnable)
            mainHandler.removeCallbacks(wakePostEndOfSpeechRecoveryRunnable)
            wakeConsecutiveTimeouts = 0
            val savedPartialWake = pendingPartialWakeMatch
            pendingPartialWakeMatch = null
            val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            var handled = false
            for (spoken in candidates) {
                val extracted = extractWakeCommand(spoken)
                if (extracted != null) {
                    handled = true
                    dispatchWakeDetection(extracted)
                    break
                }
            }
            if (!handled && savedPartialWake != null) {
                handled = true
                dispatchWakeDetection(savedPartialWake)
            }
            if (!handled) {
                val restartDelay = if (isExternalMediaPlaying()) 650L else 200L
                scheduleWakeRestart(delayMs = restartDelay)
            }
            // When handled == true, dispatchWakeDetection pauses wake recognition so follow-up /
            // overlay SpeechRecognizer gets uncontested microphone access; ViewModel unpauses when idle.
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            val primary = partials.firstOrNull().orEmpty().lowercase()
            if (primary.contains("stop talking") || primary == "stop" || primary.contains("cancel")) {
                NovaAccessibilityService.abortAutomation("Voice kill switch detected")
                stopSpeakingImmediate()
                return
            }
            for (p in partials) {
                val extracted = extractWakeCommand(p)
                if (extracted != null) {
                    pendingPartialWakeMatch = extracted
                    stopSpeakingImmediate()
                    break
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    internal fun isPausedForOverlayForTest(): Boolean = pausedForOverlay

    internal fun isWakeRecognizerActiveForTest(): Boolean = isListeningActive

    internal fun refreshExternalMediaStateForTest() {
        onExternalMediaPlaybackChanged()
    }

    private fun dispatchWakeDetection(trailingCommand: String) {
        beginConcurrentMicSession()
        stopSpeakingImmediate()
        val cb = onWakePhraseDetected
        if (cb != null) {
            // Pause wake loop immediately so follow-up listening / TTS / overlay never races for mic
            pausedForOverlay = true
            stopWakeRecognizer()
            if (wakeWordEnabled) {
                _wakeWordStatus.value = WakeWordStatus.PAUSED_FOR_OVERLAY
            }
            cb.invoke(trailingCommand)
        } else {
            stopWakeRecognizer()
            val launch = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("open_voice_overlay", true)
                putExtra("auto_listen", trailingCommand.isEmpty())
                if (trailingCommand.isNotEmpty()) {
                    putExtra("wake_command", trailingCommand)
                }
            }
            runCatching { startActivity(launch) }
        }
    }

    private fun scheduleWakeRestart(delayMs: Long = 350L) {
        if (!wakeWordEnabled || pausedForOverlay || isSpeakingTtsActive || isFollowUpCaptureActive || isDuckingForBargeIn || isCalibratingWake) return
        if (pauseWakeDuringMedia && isExternalMediaPlaying()) {
            _externalMediaPlaying.value = true
            stopWakeRecognizer()
            _wakeWordStatus.value = WakeWordStatus.PAUSED_FOR_MEDIA
            scheduleMediaResumePoll()
            return
        }
        mainHandler.removeCallbacks(wakeRestartRunnable)
        mainHandler.postDelayed(wakeRestartRunnable, delayMs)
    }

    private fun destroyWakeRecognizerInstance() {
        isListeningActive = false
        wakeSessionStartedAtMs = 0L
        pendingPartialWakeMatch = null
        mainHandler.removeCallbacks(wakeWatchdogRunnable)
        mainHandler.removeCallbacks(wakePostEndOfSpeechRecoveryRunnable)
        runCatching {
            wakeRecognizer?.cancel()
            wakeRecognizer?.destroy()
        }
        wakeRecognizer = null
    }

    private fun stopWakeRecognizer() {
        mainHandler.removeCallbacks(wakeRestartRunnable)
        destroyWakeRecognizerInstance()
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
        serviceScope.cancel()
        mainHandler.removeCallbacks(followUpStartRunnable)
        mainHandler.removeCallbacks(bargeInStartRunnable)
        mainHandler.removeCallbacks(bargeInDuckTimeoutRunnable)
        mainHandler.removeCallbacks(wakeWatchdogRunnable)
        mainHandler.removeCallbacks(wakePostEndOfSpeechRecoveryRunnable)
        mainHandler.removeCallbacks(mediaResumePollRunnable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioPlaybackCallback?.let { cb ->
                runCatching { audioManager?.unregisterAudioPlaybackCallback(cb) }
            }
            audioPlaybackCallback = null
        }
        abandonTtsAudioFocus()
        endConcurrentMicSession(resumeInterruptedMediaIfNeeded = false)
        destroyCalibrationRecognizer()
        destroyFollowUpRecognizerInstance()
        destroyBargeInRecognizerInstance()
        stopWakeRecognizer()
        _wakeWordStatus.value = WakeWordStatus.OFF
        _externalMediaPlaying.value = false
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NovaVoiceFGS"
        private const val PREFS_NAME = "nova_voice_fgs_prefs"
        private const val PREF_PROACTIVE_PROMPTS = "proactive_notif_prompts"
        private const val PREF_PAUSE_WAKE_DURING_MEDIA = "pause_wake_during_media"
        private const val PREF_ASR_ENGINE = "asr_engine_option"
        private const val PREF_TTS_ENGINE_PKG = "tts_engine_package"
        private const val PREF_TTS_VOICE_NAME = "tts_voice_name"
        private const val PREF_TTS_RATE = "tts_speech_rate"
        private const val PREF_TTS_PITCH = "tts_pitch"
        private const val PREF_CUSTOM_WAKE_ALIASES = "custom_wake_aliases"
        private const val PREF_WAKE_CALIBRATION_SAMPLES = "wake_calibration_samples"
        private const val CHANNEL_ID = "nova_voice_channel"
        private const val NOTIFICATION_ID = 4041
        private const val WAKE_STALL_WATCHDOG_MS = 25_000L
        private const val WAKE_POST_EOS_RECOVERY_MS = 4_500L
        private const val MEDIA_RESUME_POLL_MS = 2_500L
        const val ACTION_BARGE_IN = "com.example.action.BARGE_IN"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_FGS"

        private val _wakeWordStatus = MutableStateFlow(WakeWordStatus.OFF)
        val wakeWordStatus: StateFlow<WakeWordStatus> = _wakeWordStatus.asStateFlow()

        private val _externalMediaPlaying = MutableStateFlow(false)
        val externalMediaPlaying: StateFlow<Boolean> = _externalMediaPlaying.asStateFlow()

        private val customWakeLock = Any()
        private val customWakeAliases = LinkedHashSet<String>()

        private val _wakeCalibrationAliases = MutableStateFlow<List<String>>(emptyList())
        val wakeCalibrationAliases: StateFlow<List<String>> = _wakeCalibrationAliases.asStateFlow()

        private val _wakeCalibrationSamples = MutableStateFlow(0)
        val wakeCalibrationSamples: StateFlow<Int> = _wakeCalibrationSamples.asStateFlow()

        private val embeddedWakeRegex = Regex(
            "(?:^|\\b)(?:hey|ok|okay|hi|yo|hello|eh|ey|ay)[\\s,]+(?:nova|novah|novaa|noba|nava|noah|nora|nola|novas|nova's)[\\s,.:!?]*(.*)$",
            RegexOption.IGNORE_CASE
        )

        private val stopBargeInPhrases = setOf(
            "stop", "stop talking", "stop speaking", "quiet", "be quiet", "shut up",
            "cancel", "abort", "shush", "hush", "nevermind", "never mind", "enough"
        )

        private val interruptAndListenPhrases = setOf(
            "wait", "hold on", "excuse me", "nova", "listen", "interrupt", "one second", "hang on"
        )

        fun recordCalibrationTranscripts(rawTranscripts: List<String>): List<String> {
            val cleaned = rawTranscripts.mapNotNull { raw ->
                val norm = raw.trim().lowercase()
                    .replace(Regex("[^a-z0-9\\s']"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                val words = norm.split(' ').filter { it.isNotBlank() }
                if (norm.length in 4..28 && words.size in 1..3) norm else null
            }
            return synchronized(customWakeLock) {
                for (alias in cleaned) {
                    if (customWakeAliases.size >= 16) break
                    customWakeAliases.add(alias)
                }
                val list = customWakeAliases.sorted()
                _wakeCalibrationAliases.value = list
                list
            }
        }

        internal fun setCustomWakeAliasesForTest(aliases: Collection<String>) {
            synchronized(customWakeLock) {
                customWakeAliases.clear()
                customWakeAliases.addAll(aliases.map { it.trim().lowercase() }.filter { it.length >= 4 })
                _wakeCalibrationAliases.value = customWakeAliases.sorted()
            }
        }

        /**
         * Returns the trailing command (or empty string "" if user only said "Hey Nova") when a wake
         * phrase is detected, or null if the utterance does not address Nova.
         */
        fun extractWakeCommand(spoken: String): String? {
            val trimmed = spoken.trim()
            if (trimmed.isEmpty()) return null
            val (afterWake, matchedStart) = InputNormalizer.extractAfterWakePhrase(trimmed)
            if (matchedStart) return afterWake
            embeddedWakeRegex.find(trimmed)?.let { m ->
                return m.groupValues[1].trim()
            }
            val aliasesSnapshot = synchronized(customWakeLock) { customWakeAliases.toList() }
            if (aliasesSnapshot.isNotEmpty()) {
                val lower = trimmed.lowercase()
                for (alias in aliasesSnapshot) {
                    val rx = Regex("(?:^|\\b)${Regex.escape(alias)}(?:[\\s,.:!?]+(.*))?$", RegexOption.IGNORE_CASE)
                    rx.find(lower)?.let { m ->
                        val startIdx = m.range.last + 1
                        val trailing = if (m.groupValues.size > 1 && m.groupValues[1].isNotBlank()) {
                            // Preserve original casing for trailing command
                            val tailLen = m.groupValues[1].length
                            trimmed.takeLast(tailLen).trim()
                        } else if (startIdx < trimmed.length) {
                            trimmed.substring(startIdx).trim()
                        } else {
                            ""
                        }
                        return trailing
                    }
                }
            }
            return null
        }

        /**
         * Classifies speech heard while TTS is reading aloud, filtering self-echo from device speakers
         * while enabling instant barge-in ("Stop", "Wait", "Hey Nova", or a new non-echo command).
         */
        fun evaluateBargeInIntent(
            transcript: String,
            currentSpokenText: String,
            isPartial: Boolean = false
        ): BargeInDecision? {
            val normalized = transcript.trim().lowercase()
                .replace(Regex("[^a-z0-9\\s']"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (normalized.isEmpty()) return null

            if (normalized in stopBargeInPhrases) {
                return BargeInDecision(BargeInAction.STOP_ONLY)
            }
            if (normalized in interruptAndListenPhrases) {
                return BargeInDecision(BargeInAction.LISTEN_NOW)
            }

            extractWakeCommand(transcript)?.let { trailing ->
                return if (trailing.isBlank()) {
                    BargeInDecision(BargeInAction.LISTEN_NOW)
                } else if (isPartial) {
                    // Wait for final result before executing partial trailing command, or interrupt immediately
                    null
                } else {
                    BargeInDecision(BargeInAction.RUN_COMMAND, trailing)
                }
            }

            if (normalized.startsWith("nova ")) {
                val cmd = normalized.removePrefix("nova ").trim()
                if (cmd in stopBargeInPhrases) return BargeInDecision(BargeInAction.STOP_ONLY)
                if (!isPartial && cmd.length >= 2) {
                    return BargeInDecision(BargeInAction.RUN_COMMAND, cmd)
                }
                return null
            }

            if (isPartial) return null

            // Ignore self-echo picked up from the phone speaker
            if (isLikelySelfEcho(normalized, currentSpokenText)) return null

            // Non-echo multi-word utterance spoken while assistant was talking -> treat as interrupting command
            val wordCount = normalized.split(' ').count { it.length >= 2 }
            return if (wordCount >= 2) {
                BargeInDecision(BargeInAction.RUN_COMMAND, transcript.trim())
            } else {
                null
            }
        }

        fun isLikelySelfEcho(heardNormalized: String, currentSpokenText: String): Boolean {
            if (currentSpokenText.isBlank()) return false
            val spokenNormalized = currentSpokenText.lowercase()
                .replace(Regex("[^a-z0-9\\s']"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            if (spokenNormalized.contains(heardNormalized) && heardNormalized.length >= 5) {
                return true
            }
            val heardTokens = heardNormalized.split(' ').filter { it.length >= 3 }.toSet()
            if (heardTokens.isEmpty()) return true
            val spokenTokens = spokenNormalized.split(' ').filter { it.length >= 3 }.toSet()
            val overlap = heardTokens.count { it in spokenTokens }
            return overlap.toFloat() / heardTokens.size.toFloat() >= 0.65f
        }

        fun start(context: Context) {
            val intent = Intent(context, NovaVoiceForegroundService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }
    }
}
