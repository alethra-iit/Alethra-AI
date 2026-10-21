package com.alethra.ai.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.alethra.ai.AlethraApp
import com.alethra.ai.ai.Installed
import com.alethra.ai.ai.ModelKind
import com.alethra.ai.ai.TransferInfo
import com.alethra.ai.overlay.FloatingService
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, requestMic: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as AlethraApp
    val scope = rememberCoroutineScope()

    var llmList by remember { mutableStateOf(app.store.installed(ModelKind.LLM)) }
    var sttList by remember { mutableStateOf(app.store.installed(ModelKind.STT)) }
    var picker by remember { mutableStateOf<ModelKind?>(null) }
    var speak by remember { mutableStateOf(app.prefs.speakReplies) }
    var smooth by remember { mutableStateOf(app.prefs.ttsSmooth) }
    var autoHf by remember { mutableStateOf(app.prefs.autoHandsFree) }
    var barge by remember { mutableStateOf(app.prefs.bargeIn) }
    val floating by FloatingService.running.collectAsState()

    // Unduh/impor berjalan di TransferManager + DownloadService, jadi tetap lanjut walau layar ini
    // ditutup atau aplikasi ke latar belakang. Layar ini hanya menampilkan statusnya.
    val transfers by app.transfers.state.collectAsState()
    val listVersion by app.transfers.version.collectAsState()

    fun refresh() {
        llmList = app.store.installed(ModelKind.LLM)
        sttList = app.store.installed(ModelKind.STT)
    }
    LaunchedEffect(listVersion) { refresh() }

    suspend fun unload(kind: ModelKind) =
        if (kind == ModelKind.LLM) app.assistant.llama.unload() else app.assistant.whisper.unload()

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    /** Android 13+: tanpa izin notifikasi, progres di latar belakang tidak terlihat (unduhan tetap jalan). */
    fun askNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun select(kind: ModelKind, m: Installed) {
        app.prefs.setActive(kind, m.id)
        scope.launch { unload(kind) } // bebaskan RAM; model baru dimuat saat dipakai
    }

    fun delete(kind: ModelKind, m: Installed) {
        scope.launch {
            unload(kind)
            app.store.delete(m)
            refresh()
        }
    }

    val pickLlm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { askNotifPermission(); app.transfers.import(ModelKind.LLM, uri) }
    }
    val pickStt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { askNotifPermission(); app.transfers.import(ModelKind.STT, uri) }
    }

    picker?.let { kind ->
        val installedIds = (if (kind == ModelKind.LLM) llmList else sttList).map { it.id }.toSet()
        AlertDialog(
            onDismissRequest = { picker = null },
            title = { Text(if (kind == ModelKind.LLM) "Pilih model bahasa" else "Pilih model suara") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    app.store.presets(kind).forEach { p ->
                        val have = p.id in installedIds
                        Surface(
                            onClick = {
                                picker = null
                                if (have) {
                                    app.store.installed(kind).firstOrNull { it.id == p.id }?.let { select(kind, it) }
                                } else {
                                    askNotifPermission()
                                    app.transfers.download(kind, p)
                                }
                            },
                            shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("${p.name} • ${p.size}", style = MaterialTheme.typography.titleSmall)
                                Text(p.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (have) Text(
                                    "✓ Sudah terpasang — ketuk untuk memakai",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ picker = null }) { Text("Batal") } }
        )
    }
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
            Section("Tampilan")
            Text("Mode", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Ikuti sistem" to ThemeMode.SYSTEM, "Terang" to ThemeMode.LIGHT, "Gelap" to ThemeMode.DARK)
                    .forEach { (label, mode) ->
                        FilterChip(
                            selected = app.prefs.themeMode == mode,
                            onClick = { app.prefs.updateThemeMode(mode) },
                            label = { Text(label) }
                        )
                    }
            }
            Text("Warna", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Palettes.forEachIndexed { i, p ->
                    PaletteSwatch(p, selected = app.prefs.paletteIndex == i) { app.prefs.updatePalette(i) }
                }
            }

            Section("Model AI (offline)")
            ModelGroup(
                title = "Model bahasa (LLM)",
                hint = "Pasang beberapa model, lalu pilih satu yang dipakai. Unduh preset (Qwen2.5, Gemma 3, Llama 3.2) atau impor .gguf sendiri.",
                models = llmList,
                activeId = (llmList.firstOrNull { it.id == app.prefs.activeLlm } ?: llmList.firstOrNull())?.id,
                transfer = transfers[ModelKind.LLM],
                onSelect = { select(ModelKind.LLM, it) },
                onDelete = { delete(ModelKind.LLM, it) },
                onDownload = { picker = ModelKind.LLM },
                onPick = { pickLlm.launch(arrayOf("*/*")) },
                onPause = { app.transfers.pause(ModelKind.LLM) },
                onResume = { app.transfers.resume(ModelKind.LLM) },
                onCancel = { app.transfers.cancel(ModelKind.LLM) }
            )
            ModelGroup(
                title = "Model suara (Whisper)",
                hint = "Pilih satu yang dipakai untuk mengubah suara ke teks. Model khusus Indonesia lebih akurat; atau impor berkas .bin (format GGML whisper.cpp) sendiri.",
                models = sttList,
                activeId = (sttList.firstOrNull { it.id == app.prefs.activeStt } ?: sttList.firstOrNull())?.id,
                transfer = transfers[ModelKind.STT],
                onSelect = { select(ModelKind.STT, it) },
                onDelete = { delete(ModelKind.STT, it) },
                onDownload = { picker = ModelKind.STT },
                onPick = { pickStt.launch(arrayOf("*/*")) },
                onPause = { app.transfers.pause(ModelKind.STT) },
                onResume = { app.transfers.resume(ModelKind.STT) },
                onCancel = { app.transfers.cancel(ModelKind.STT) }
            )

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

            Text("Kelancaran suara", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Langsung: bicara per beberapa kata mengikuti tulisan AI, tanpa menunggu kalimat selesai (intonasi bisa kurang natural). Seimbang dan Mulus: menunggu lebih banyak teks dulu, suaranya lebih natural tapi mulai lebih lambat.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Langsung", "Seimbang", "Mulus").forEachIndexed { i, label ->
                    if (smooth == i) Button({}, Modifier.weight(1f)) { Text(label) }
                    else OutlinedButton({
                        smooth = i; app.prefs.ttsSmooth = i; app.assistant.tts.smooth = i
                    }, Modifier.weight(1f)) { Text(label) }
                }
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

            SwitchRow("Mode bebas tangan otomatis", "Saat floating dibuka, AI langsung mendengarkan dan membalas bergantian tanpa menekan tombol. Bisa juga dinyalakan dari ikon telinga di panel.", autoHf) {
                autoHf = it; app.prefs.autoHandsFree = it
            }
            SwitchRow("Potong suara AI saat saya bicara", "Di mode bebas tangan, suara AI berhenti begitu Anda mulai berbicara. Matikan bila speaker membuat AI terpotong sendiri (atau pakai headset).", barge) {
                barge = it; app.prefs.bargeIn = it
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

private fun formatSize(bytes: Long): String =
    if (bytes >= 1L shl 30) String.format(Locale("id", "ID"), "%.1f GB", bytes / (1L shl 30).toDouble())
    else String.format(Locale("id", "ID"), "%d MB", bytes shr 20)

@Composable
private fun ModelGroup(
    title: String, hint: String,
    models: List<Installed>, activeId: String?, transfer: TransferInfo?,
    onSelect: (Installed) -> Unit, onDelete: (Installed) -> Unit,
    onDownload: () -> Unit, onPick: () -> Unit,
    onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit
) {
    val progress = transfer?.progress
    var toDelete by remember { mutableStateOf<Installed?>(null) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (models.isEmpty()) {
                Text("Belum ada model terpasang", color = MaterialTheme.colorScheme.error)
            } else models.forEach { m ->
                val active = m.id == activeId
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(enabled = progress == null) { onSelect(m) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = active, onClick = null, Modifier.padding(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(m.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            formatSize(m.bytes) + if (active) " • Sedang dipakai" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton({ toDelete = m }, enabled = progress == null) {
                        Icon(Icons.Default.Delete, "Hapus ${m.name}", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            if (transfer != null) {
                LinearProgressIndicator(progress = { transfer.progress }, Modifier.fillMaxWidth())
                Column {
                    Text(
                        "${(transfer.progress * 100).toInt()}%" + if (transfer.paused) " • Dijeda" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        if (transfer.paused) "Sisa unduhan tersimpan; tekan Lanjutkan untuk meneruskan."
                        else "Berjalan di latar belakang; aman ditinggal.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (transfer.paused) {
                        Button(onResume) { Text("Lanjutkan") }
                    } else if (transfer.canPause) {
                        FilledTonalButton(onPause) { Text("Jeda") }
                    }
                    OutlinedButton(
                        onCancel,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Close, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Batalkan")
                    }
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onDownload) { Text("Unduh model") }
                OutlinedButton(onPick) { Text("Impor berkas") }
            }
        }
    }
    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Hapus model?") },
            text = { Text("“${m.name}” akan dihapus dari perangkat. Anda bisa mengunduhnya lagi nanti.") },
            confirmButton = { TextButton({ toDelete = null; onDelete(m) }) { Text("Hapus") } },
            dismissButton = { TextButton({ toDelete = null }) { Text("Batal") } }
        )
    }
}

@Composable
private fun PaletteSwatch(p: AlethraPalette, selected: Boolean, onClick: () -> Unit) {
    // Pratinjau mengikuti mode yang sedang aktif (terang/gelap).
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val cs = if (isDark) p.dark else p.light
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape)
                .background(Brush.linearGradient(listOf(cs.primary, cs.secondary)))
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outlineVariant,
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) { if (selected) Icon(Icons.Default.Check, "Dipilih", tint = cs.onPrimary) }
        Text(p.name, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall)
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
