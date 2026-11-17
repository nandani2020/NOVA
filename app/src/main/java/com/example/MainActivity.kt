package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.example.ui.NovaApp
import com.example.ui.NovaViewModel
import com.example.ui.theme.NovaTheme

class MainActivity : ComponentActivity() {

    private val viewModel: NovaViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Foreground notification renders gracefully either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val openVoice = intent?.getBooleanExtra("open_voice_overlay", false) == true

        setContent {
            NovaTheme {
                NovaApp(
                    viewModel = viewModel,
                    initialOpenVoice = openVoice
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Hardware Volume-Down kill switch for autonomous UI / voice loop
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val state = viewModel.uiState.value
            if (state.pendingConfirmation != null || state.voiceState.isListening) {
                viewModel.killSwitchAbort()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
