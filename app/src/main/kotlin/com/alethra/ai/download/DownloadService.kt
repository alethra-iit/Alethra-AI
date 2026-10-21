package com.alethra.ai.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.alethra.ai.AlethraApp
import com.alethra.ai.ai.TransferInfo
import com.alethra.ai.ai.ModelKind
import com.alethra.ai.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service yang menjaga unduh/impor model tetap berjalan saat aplikasi di latar belakang
 * atau layar mati. Tugasnya hanya menampilkan notifikasi progres dan menahan proses tetap hidup;
 * pekerjaannya sendiri ada di [com.alethra.ai.ai.TransferManager].
 */
class DownloadService : Service() {
    companion object {
        private const val CHANNEL = "alethra_download"
        private const val NOTIF_ID = 2
        private const val ACTION_CANCEL = "com.alethra.ai.DOWNLOAD_CANCEL"
        private const val ACTION_PAUSE = "com.alethra.ai.DOWNLOAD_PAUSE"
        private const val ACTION_RESUME = "com.alethra.ai.DOWNLOAD_RESUME"

        fun start(c: Context) =
            ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java))
    }

    /** active = ada yang sedang berjalan; canPause = ada unduhan berjalan yang bisa dijeda. */
    private data class Rendered(
        val text: String, val percent: Int, val empty: Boolean, val active: Boolean, val canPause: Boolean
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wake: PowerManager.WakeLock? = null
    private val transfers get() = (application as AlethraApp).transfers

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
            NotificationChannel(CHANNEL, "Unduhan model", NotificationManager.IMPORTANCE_LOW)
        )
        // Cegah CPU tidur saat layar mati; dilepas otomatis setelah 6 jam sebagai pengaman.
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "alethra:download")
            .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                transfers.cancelAll()
                // Bila service tidak sedang memantau (mis. hanya ada unduhan dijeda), bersihkan sendiri.
                if (watcher == null) {
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> { transfers.pauseAll(); return START_NOT_STICKY }
            ACTION_RESUME -> {
                // Bila ada yang dilanjutkan, DownloadService.start() dipanggil lagi dan instance ini naik ke foreground.
                if (transfers.resumeAll() == 0 && watcher == null) stopSelf()
                return START_NOT_STICKY
            }
        }
        // Wajib segera memanggil startForeground (batas 5 detik setelah startForegroundService).
        ServiceCompat.startForeground(
            this, NOTIF_ID, build(render(transfers.state.value)), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (watcher == null) {
            watcher = scope.launch {
                transfers.state.map { render(it) }.distinctUntilChanged().collect { r ->
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    when {
                        r.empty -> {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                        !r.active -> {
                            // Semua dijeda: lepas status foreground (hemat baterai), tapi biarkan notifikasi
                            // "Dijeda" dengan tombol Lanjutkan/Batalkan.
                            stopForeground(STOP_FOREGROUND_DETACH)
                            nm.notify(NOTIF_ID, build(r))
                            stopSelf()
                        }
                        else -> nm.notify(NOTIF_ID, build(r))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun render(state: Map<ModelKind, TransferInfo>): Rendered {
        if (state.isEmpty()) return Rendered("", 0, empty = true, active = false, canPause = false)
        fun pct(i: TransferInfo) = (i.progress * 100).toInt().coerceIn(0, 100)
        val text = state.values.joinToString(" • ") { "${it.name} ${pct(it)}%" + if (it.paused) " (dijeda)" else "" }
        val running = state.values.filter { !it.paused }
        val shown = running.ifEmpty { state.values.toList() }
        return Rendered(
            text, shown.map { pct(it) }.average().toInt(),
            empty = false, active = running.isNotEmpty(), canPause = running.any { it.canPause }
        )
    }

    private fun action(code: Int, action: String) = PendingIntent.getService(
        this, code, Intent(this, DownloadService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE
    )

    private fun build(r: Rendered) = run {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setContentText(r.text.ifEmpty { "Menyiapkan…" })
            .setProgress(100, r.percent, false)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (r.active) {
            b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Alethra AI: memproses model")
                .setOngoing(true)
            if (r.canPause) b.addAction(0, "Jeda", action(3, ACTION_PAUSE))
        } else {
            b.setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle("Alethra AI: unduhan dijeda")
                .setOngoing(false)
                .addAction(0, "Lanjutkan", action(4, ACTION_RESUME))
        }
        b.addAction(0, "Batalkan", action(1, ACTION_CANCEL)).build()
    }

    override fun onDestroy() {
        scope.cancel()
        wake?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }
}
