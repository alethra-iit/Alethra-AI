package com.alethra.ai.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.alethra.ai.AlethraApp
import com.alethra.ai.overlay.FloatingService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, requestMic: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as AlethraApp
    val scope = rememberCoroutineScope()

    var llmReady by remember { mutableStateOf(app.store.hasLlm()) }
    var sttReady by remember { mutableStateOf(app.store.hasStt()) }
    var llmProgress by remember { mutableStateOf<Float?>(null) }
    var sttProgress by remember { mutableStateOf<Float?>(null) }
    var speak by remember { mutableStateOf(app.prefs.speakReplies) }
    val floating by FloatingService.running.collectAsState()

    val pickLlm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            llmProgress = 0f
            try {
                app.assistant.llama.unload()
                app.store.import(uri, app.store.llmFile) { llmProgress = it }
                llmReady = true
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "Gagal impor", Toast.LENGTH_LONG).show()
            }
            llmProgress = null
        }
    }
    val pickStt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            sttProgress = 0f
            try {
                app.assistant.whisper.unload()
                app.store.import(uri, app.store.sttFile) { sttProgress = it }
                sttReady = true
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "Gagal impor", Toast.LENGTH_LONG).show()
            }
            sttProgress = null
        }
    }
    fun download(url: String, target: java.io.File, setP: (Float?) -> Unit, unload: suspend () -> Unit, done: () -> Unit) {
        scope.launch {
            setP(0f)
            try {
                unload()
                app.store.download(url, target) { setP(it) }
                done()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Unduhan terhenti: ${e.message}. Tekan Unduh lagi untuk melanjutkan.", Toast.LENGTH_LONG).show()
            }
            setP(null)
        }
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Pengaturan") },
                navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Kembali") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { inner ->
        Column(
            Modifier.padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Section("Model AI (offline)")
            ModelCard(
                "Model bahasa (LLM)", "Qwen2.5-7B-Instruct Q4_K_M (±4,7 GB). Unduh langsung atau impor .gguf lain.",
                llmReady, llmProgress,
                onDelete = {
                    scope.launch {
                        app.assistant.llama.unload()
                        app.store.delete(app.store.llmFile)
                        llmReady = false
                    }
                },
                onDownload = {
                    download(com.alethra.ai.ai.ModelStore.LLM_URL, app.store.llmFile, { llmProgress = it },
                        { app.assistant.llama.unload() }, { llmReady = true })
                }
            ) { pickLlm.launch(arrayOf("*/*")) }
            ModelCard(
                "Model suara (Whisper)", "ggml-small multibahasa (±470 MB). Unduh langsung atau impor berkas sendiri.",
                sttReady, sttProgress,
                onDelete = {
                    scope.launch {
                        app.assistant.whisper.unload()
                        app.store.delete(app.store.sttFile)
                        sttReady = false
                    }
                },
                onDownload = {
                    download(com.alethra.ai.ai.ModelStore.STT_URL, app.store.sttFile, { sttProgress = it },
                        { app.assistant.whisper.unload() }, { sttReady = true })
                }
            ) { pickStt.launch(arrayOf("*/*")) }

            Section("Data")
            var confirmAll by remember { mutableStateOf(false) }
            OutlinedButton({ confirmAll = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Hapus semua riwayat percakapan")
            }
            if (confirmAll) AlertDialog(
                onDismissRequest = { confirmAll = false },
                title = { Text("Hapus semua riwayat?") },
                text = { Text("Seluruh percakapan di perangkat ini akan dihapus permanen.") },
                confirmButton = {
                    TextButton({
                        confirmAll = false
                        scope.launch {
                            app.db.dao().clearAll()
                            Toast.makeText(ctx, "Riwayat dihapus", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Hapus") }
                },
                dismissButton = { TextButton({ confirmAll = false }) { Text("Batal") } }
            )

            Section("Suara")
            SwitchRow("Bacakan jawaban otomatis", "Menggunakan Android Text-to-Speech", speak) {
                speak = it; app.prefs.speakReplies = it
            }

            Section("Floating assistant")
            SwitchRow("Aktifkan floating assistant", "Gelembung asisten di atas aplikasi lain", floating) { on ->
                if (!on) {
                    FloatingService.stop(ctx)
                } else if (!Settings.canDrawOverlays(ctx)) {
                    ctx.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    Toast.makeText(ctx, "Izinkan tampil di atas aplikasi lain, lalu aktifkan lagi.", Toast.LENGTH_LONG).show()
                } else {
                    requestMic {
                        if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        FloatingService.start(ctx)
                    }
                }
            }

            Text(
                "Alethra AI 1.0 • Semua percakapan disimpan lokal di perangkat ini.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable private fun Section(t: String) =
    Text(t, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.secondary)

@Composable
private fun ModelCard(title: String, hint: String, ready: Boolean, progress: Float?, onDelete: () -> Unit, onDownload: () -> Unit, onPick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (ready) "✓ Terpasang" else "Belum dipasang",
                color = if (ready) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error
            )
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onDownload) { Text(if (ready) "Unduh ulang" else "Unduh") }
                OutlinedButton(onPick) { Text("Impor berkas") }
            }
            if (progress == null && ready) {
                var confirm by remember { mutableStateOf(false) }
                TextButton({ confirm = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Hapus model")
                }
                if (confirm) AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text("Hapus model?") },
                    text = { Text("Berkas model akan dihapus dari perangkat. Anda bisa mengunduhnya lagi nanti.") },
                    confirmButton = { TextButton({ confirm = false; onDelete() }) { Text("Hapus") } },
                    dismissButton = { TextButton({ confirm = false }) { Text("Batal") } }
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
