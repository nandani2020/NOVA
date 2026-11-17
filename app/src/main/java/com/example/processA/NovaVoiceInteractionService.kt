package com.example.processA

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import com.example.MainActivity

/**
 * System Digital Assistant VoiceInteractionService (Process A).
 */
class NovaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        NovaVoiceForegroundService.start(this)
    }
}

class NovaVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return NovaVoiceInteractionSession(this)
    }
}

class NovaVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("open_voice_overlay", true)
        }
        runCatching { context.startActivity(launch) }
        hide()
    }
}

class NovaVoiceRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        // Delegates live recognition cleanly to system SpeechRecognizer in UI/foreground loop
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
