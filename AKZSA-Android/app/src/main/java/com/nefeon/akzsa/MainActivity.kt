package com.nefeon.akzsa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nefeon.akzsa.ui.AkzsaApp
import com.nefeon.akzsa.files.FileIndexAutoMonitor
import com.nefeon.akzsa.files.FileIndexWorkScheduler
import com.nefeon.akzsa.files.AkzsaFileAiStartup
import com.nefeon.akzsa.files.LocalFileIndexer
import android.content.Intent
import android.os.Environment

class MainActivity : ComponentActivity() {
    private var requestMicPermission by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()   // required look & feel on Android 15+; Scaffold/TopAppBar consume the insets
        super.onCreate(savedInstanceState)
        FileIndexAutoMonitor.start(applicationContext)
        FileIndexWorkScheduler.ensurePeriodic(applicationContext)
        AkzsaFileAiStartup.start(applicationContext)
        requestMicPermission = intent?.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false) == true
        setContent {
            AkzsaApp(requestMicPermission = requestMicPermission)
        }
        // File AI access is requested only when the user opens File AI (just-in-time), never automatically on first launch.
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestMicPermission = intent.getBooleanExtra(EXTRA_REQUEST_MIC_PERMISSION, false)
    }

    companion object {
        const val EXTRA_REQUEST_MIC_PERMISSION = "com.nefeon.akzsa.REQUEST_MIC_PERMISSION"
    }
}
