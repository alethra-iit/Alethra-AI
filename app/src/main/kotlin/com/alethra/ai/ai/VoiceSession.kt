package com.alethra.ai.ai

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Satu sesi rekaman mikrofon yang tetap terbuka selama mode bebas tangan, sehingga tidak ada
 * celah audio antara "memantau potongan suara" (barge-in) dan "merekam ucapan".
 *
 * Memakai sumber VOICE_COMMUNICATION + peredam gema (AEC) bila perangkat mendukung, supaya suara
 * AI dari speaker tidak dianggap sebagai ucapan pengguna. Headset tetap paling andal.
 */
class VoiceSession(private val onLevel: (Float) -> Unit = {}) : AutoCloseable {
    private companion object {
        const val SR = 16000
        const val SPEECH_RMS = 0.012f       // ambang ucapan saat mendengarkan giliran pengguna
        const val PRE_ROLL_SAMPLES = SR * 8 / 10   // 0,8 dtk audio sebelum terdeteksi, ikut diteruskan ke Whisper
    }

    private var rec: AudioRecord? = null
    private val effects = mutableListOf<android.media.audiofx.AudioEffect>()

    @SuppressLint("MissingPermission")
    fun start() {
        if (rec != null) return
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, SR,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SR * 4)
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("Mikrofon tidak dapat diakses")
        }
        // Efek audio opsional: bila tidak tersedia/gagal dibuat, rekaman tetap jalan tanpanya.
        try { if (AcousticEchoCanceler.isAvailable()) track(AcousticEchoCanceler.create(r.audioSessionId)) } catch (_: Exception) {}
        try { if (NoiseSuppressor.isAvailable()) track(NoiseSuppressor.create(r.audioSessionId)) } catch (_: Exception) {}
        try { if (AutomaticGainControl.isAvailable()) track(AutomaticGainControl.create(r.audioSessionId)) } catch (_: Exception) {}
        r.startRecording()
        rec = r
    }

    private fun track(e: android.media.audiofx.AudioEffect?) {
        if (e == null) return
        e.enabled = true
        effects += e
    }

    /** Hentikan rekaman sementara (mikrofon benar-benar mati), mis. saat AI berbicara tanpa barge-in. */
    fun pause() { try { rec?.stop() } catch (_: Exception) {} }

    /** Lanjutkan rekaman setelah [pause]. */
    fun resume() { try { rec?.startRecording() } catch (_: Exception) {} }

    /** Buang audio lama yang menumpuk di buffer (mis. selama transkripsi atau sisa gema suara AI). */
    private fun drain() {
        val r = rec ?: return
        val tmp = ShortArray(SR / 10)
        var guard = 0
        while (guard++ < 100 && r.read(tmp, 0, tmp.size, AudioRecord.READ_NON_BLOCKING) > 0) { /* buang */ }
    }

    private fun rms(a: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) { val f = a[i] / 32768f; sum += f * f }
        return sqrt(sum / n).toFloat()
    }

    /**
     * Rekam satu ucapan pengguna; berhenti otomatis setelah jeda diam [silenceMs].
     * [preRoll] = audio yang sudah tertangkap saat barge-in (ucapan dilanjutkan tanpa terpotong).
     * Mengembalikan array kosong bila tidak ada ucapan dalam [startTimeoutMs].
     */
    suspend fun listenUtterance(
        preRoll: FloatArray? = null,
        maxSeconds: Int = 25,
        startTimeoutMs: Int = 7000,
        silenceMs: Int = 900,
        shouldStop: () -> Boolean = { false }
    ): FloatArray = withContext(Dispatchers.IO) {
        val r = rec ?: throw IllegalStateException("Sesi suara belum dimulai")
        val out = FloatArray(SR * maxSeconds)
        var n = 0
        var speech = false
        var silentMs = 0
        var waitedMs = 0
        if (preRoll != null && preRoll.isNotEmpty()) {
            val k = minOf(preRoll.size, out.size)
            System.arraycopy(preRoll, preRoll.size - k, out, 0, k)
            n = k
            speech = true
        } else {
            drain()
        }
        val chunk = ShortArray(SR / 10)
        while (isActive && n + chunk.size <= out.size && !shouldStop()) {
            val got = r.read(chunk, 0, chunk.size)
            if (got <= 0) break
            for (i in 0 until got) out[n + i] = chunk[i] / 32768f
            n += got
            val level = rms(chunk, got)
            onLevel(level)
            when {
                level > SPEECH_RMS -> { speech = true; silentMs = 0 }
                speech -> silentMs += 100
                else -> waitedMs += 100
            }
            if (speech && silentMs >= silenceMs) break
            if (!speech && waitedMs >= startTimeoutMs) break
        }
        if (speech) out.copyOf(n) else FloatArray(0)
    }

    /**
     * Pantau mikrofon sampai pengguna mulai berbicara (ucapan terus-menerus ≥ [minMs]).
     * Mengembalikan audio terakhir (pre-roll) untuk diteruskan ke [listenUtterance], atau null bila
     * dibatalkan / mikrofon error. [threshold] dievaluasi tiap potongan: pakai nilai lebih tinggi saat
     * AI sedang berbicara supaya gema speaker tidak memicu pemotongan.
     */
    suspend fun waitForBargeIn(minMs: Int = 300, threshold: () -> Float): FloatArray? = withContext(Dispatchers.IO) {
        val r = rec ?: return@withContext null
        drain()
        val chunk = ShortArray(SR / 20)   // 50 ms
        val ring = FloatArray(PRE_ROLL_SAMPLES)
        var w = 0
        var filled = 0
        var loudMs = 0
        while (isActive) {
            val got = r.read(chunk, 0, chunk.size)
            if (got <= 0) return@withContext null
            for (i in 0 until got) {
                ring[w] = chunk[i] / 32768f
                w = (w + 1) % ring.size
                if (filled < ring.size) filled++
            }
            val level = rms(chunk, got)
            onLevel(level)
            if (level > threshold()) loudMs += 50 else loudMs = maxOf(0, loudMs - 25)
            if (loudMs >= minMs) {
                val res = FloatArray(filled)
                val start = if (filled < ring.size) 0 else w
                for (i in 0 until filled) res[i] = ring[(start + i) % ring.size]
                return@withContext res
            }
        }
        null
    }

    override fun close() {
        effects.forEach { try { it.release() } catch (_: Exception) {} }
        effects.clear()
        rec?.let { try { it.stop() } catch (_: Exception) {}; it.release() }
        rec = null
    }
}
