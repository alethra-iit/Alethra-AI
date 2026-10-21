package com.alethra.ai.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AlethraTheme { AlethraRoot() } }
    }
}

@Composable
fun AlethraRoot() {
    val vm: ChatViewModel = viewModel()
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf("chat") }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pending?.invoke()
        pending = null
    }
    val requestMic: (() -> Unit) -> Unit = { action ->
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pending = action
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    BackHandler(screen != "chat") { screen = "chat" }
    if (screen == "chat") ChatScreen(vm, onSettings = { screen = "settings" }, requestMic = requestMic)
    else SettingsScreen(onBack = { screen = "chat" }, requestMic = requestMic)
}
