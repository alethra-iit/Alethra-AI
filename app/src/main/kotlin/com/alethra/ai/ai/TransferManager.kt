package com.alethra.ai.ai

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.alethra.ai.download.DownloadService
import com.alethra.ai.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * [paused] = unduhan dijeda (berkas sebagian tetap disimpan, tidak ada proses berjalan).
 * [canPause] = hanya unduhan dari internet yang bisa dijeda; impor berkas lokal tidak.
 */
data class TransferInfo(
    val name: String, val progress: Float,
    val paused: Boolean = false, val canPause: Boolean = false
)

/**
 * Pengelola unduh/impor model yang hidup sepanjang proses aplikasi (bukan terikat layar).
 * Selama ada proses berjalan, [DownloadService] menjaga aplikasi tetap hidup di latar belakang
 * dan menampilkan notifikasi progres dengan tombol Batalkan.
 */
class TransferManager(
    private val ctx: Context,
    private val store: ModelStore,
    private val prefs: Prefs,
    private val assistant: Assistant
) {
    // Semua akses ke jobs/targets terjadi di thread utama.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val jobs = mutableMapOf<ModelKind, Job>()
    private val targets = mutableMapOf<ModelKind, File>()
    private val activePresets = mutableMapOf<ModelKind, Preset>()  // unduhan yang sedang berjalan
    private val pausedPresets = mutableMapOf<ModelKind, Preset>()  // unduhan yang dijeda
    private val pausing = mutableSetOf<ModelKind>()                // sedang dalam proses dijeda

    private val _state = MutableStateFlow<Map<ModelKind, TransferInfo>>(emptyMap())
    /** Proses yang sedang berjalan, per jenis model. Kosong = tidak ada. */
    val state: StateFlow<Map<ModelKind, TransferInfo>> = _state.asStateFlow()

    private val _version = MutableStateFlow(0)
    /** Naik setiap kali daftar model terpasang berubah (agar UI memuat ulang daftar). */
    val version: StateFlow<Int> = _version.asStateFlow()

    private var doneId = 100

    fun download(kind: ModelKind, preset: Preset) = begin(
        kind, preset.id, preset.name, preset,
        { "Unduhan terhenti: ${it.message}. Tekan Unduh lagi untuk melanjutkan." }
    ) { target, onProgress -> store.download(preset.url, target, onProgress) }

    fun import(kind: ModelKind, uri: Uri) {
        val id = store.customIdFor(uri)
        begin(kind, id, store.displayName(kind, id), null, { it.message ?: "Gagal impor" }) { target, onProgress ->
            store.import(uri, target, onProgress)
        }
    }

    /** Jeda unduhan: proses berhenti, berkas sebagian disimpan agar bisa dilanjutkan. */
    fun pause(kind: ModelKind) {
        val job = jobs[kind] ?: return
        if (!activePresets.containsKey(kind)) return // impor berkas tidak bisa dijeda
        pausing += kind
        job.cancel()
    }

    /** Lanjutkan unduhan yang dijeda dari bagian terakhir. */
    fun resume(kind: ModelKind) {
        if (jobs.containsKey(kind)) return
        val preset = pausedPresets[kind] ?: return
        download(kind, preset)
    }

    fun pauseAll() = ModelKind.values().forEach { pause(it) }
    /** Mengembalikan jumlah unduhan yang benar-benar dilanjutkan. */
    fun resumeAll(): Int {
        var n = 0
        ModelKind.values().forEach { k ->
            if (pausedPresets.containsKey(k) && !jobs.containsKey(k)) { resume(k); n++ }
        }
        return n
    }

    /** Batalkan (juga untuk yang sedang dijeda), tunggu benar-benar berhenti, lalu buang berkas sementaranya. */
    fun cancel(kind: ModelKind) {
        pausing.remove(kind) // batal mengalahkan jeda
        val job = jobs[kind]
        val target = targets[kind]
        if (job == null) {
            if (_state.value[kind]?.paused == true) {
                target?.let { store.discardPartial(it) }
                targets.remove(kind)
                pausedPresets.remove(kind)
                _state.update { it - kind }
                toast("Unduhan dibatalkan")
            }
            return
        }
        scope.launch {
            job.cancelAndJoin()
            target?.let { store.discardPartial(it) }
            toast("Proses dibatalkan")
        }
    }

    fun cancelAll() = ModelKind.values().forEach { cancel(it) }

    private fun begin(
        kind: ModelKind, id: String, name: String, preset: Preset?,
        errorText: (Exception) -> String,
        block: suspend (File, (Float) -> Unit) -> Unit
    ) {
        if (jobs.containsKey(kind)) {
            toast("Masih ada proses yang berjalan untuk jenis model ini")
            return
        }
        val target = store.fileFor(kind, id)
        targets[kind] = target
        pausedPresets.remove(kind)
        if (preset != null) activePresets[kind] = preset
        // State diisi dulu sebelum service dinyalakan, supaya service tidak langsung berhenti.
        // Bila melanjutkan unduhan yang dijeda, progres awal = progres terakhir.
        val startProgress = _state.value[kind]?.progress ?: 0f
        _state.update { it + (kind to TransferInfo(name, startProgress, paused = false, canPause = preset != null)) }
        DownloadService.start(ctx)

        val job = scope.launch {
            var paused = false
            try {
                // Hanya bongkar model dari memori bila berkas yang sama akan ditimpa.
                if (store.activeFile(kind) == target) unload(kind)
                block(target) { p -> _state.update { it + (kind to TransferInfo(name, p, false, preset != null)) } }
                prefs.setActive(kind, id)
                _version.update { it + 1 }
                toast("$name siap dipakai")
                notifyResult("Model siap dipakai", name)
            } catch (e: CancellationException) {
                paused = kind in pausing
                throw e
            } catch (e: Exception) {
                toast(errorText(e))
                notifyResult("Unduhan terhenti", name)
            } finally {
                pausing.remove(kind)
                activePresets.remove(kind)
                jobs.remove(kind)
                if (paused && preset != null) {
                    pausedPresets[kind] = preset
                    _state.update { cur ->
                        cur + (kind to TransferInfo(name, cur[kind]?.progress ?: 0f, paused = true, canPause = true))
                    }
                    toast("Unduhan dijeda")
                } else {
                    targets.remove(kind)
                    _state.update { it - kind }
                }
            }
        }
        jobs[kind] = job
    }

    private suspend fun unload(kind: ModelKind) =
        if (kind == ModelKind.LLM) assistant.llama.unload() else assistant.whisper.unload()

    private fun toast(text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()

    @SuppressLint("MissingPermission")
    private fun notifyResult(title: String, text: String) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val sys = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        sys.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL, "Hasil unduhan model", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(
            doneId++,
            NotificationCompat.Builder(ctx, RESULT_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title).setContentText(text)
                .setContentIntent(open).setAutoCancel(true)
                .build()
        )
    }

    private companion object {
        const val RESULT_CHANNEL = "alethra_download_result"
    }
}
