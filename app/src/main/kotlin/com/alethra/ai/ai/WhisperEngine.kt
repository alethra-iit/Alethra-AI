package com.alethra.ai.ai

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.alethra.ai.whisper.WhisperBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

class WhisperEngine(private val store: ModelStore, private val prefs: Prefs) {
    private var handle = 0L
    private val lock = Mutex()

    suspend fun ensureLoaded(): Boolean = lock.withLock {
        if (handle == 0L && store.hasStt()) {
            handle = withContext(Dispatchers.Default) { WhisperBridge.load(store.sttFile.absolutePath) }
        }
        handle != 0L
    }

    suspend fun unload() = lock.withLock {
        if (handle != 0L) { WhisperBridge.free(handle); handle = 0L }
    }

    suspend fun transcribe(pcm: FloatArray, lang: String = "id"): String = lock.withLock {
        val data = if (pcm.size < 16800) pcm.copyOf(16800) else pcm // whisper butuh >= ~1 detik
        withContext(Dispatchers.Default) {
            String(WhisperBridge.transcribe(handle, data, lang, prefs.threads), Charsets.UTF_8)
        }
    }
}

/** Merekam mikrofon 16 kHz mono; berhenti otomatis setelah jeda bicara ~1,5 detik. */
object Recorder {
    private const val SR = 16000

    @SuppressLint("MissingPermission")
    suspend fun record(maxSeconds: Int = 25, shouldStop: () -> Boolean): FloatArray =
        withContext(Dispatchers.IO) {
            val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SR * 2)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                throw IllegalStateException("Mikrofon tidak dapat diakses")
            }
            val out = FloatArray(SR * maxSeconds)
            val chunk = ShortArray(SR / 10)
            var n = 0
            var speech = false
            var silentMs = 0
            var waitedMs = 0
            rec.startRecording()
            try {
                while (isActive && n + chunk.size <= out.size && !shouldStop()) {
                    val r = rec.read(chunk, 0, chunk.size)
                    if (r <= 0) break
                    var sum = 0.0
                    for (i in 0 until r) {
                        val f = chunk[i] / 32768f
                        out[n + i] = f
                        sum += f * f
                    }
                    n += r
                    val rms = sqrt(sum / r)
                    when {
                        rms > 0.012 -> { speech = true; silentMs = 0 }
                        speech -> silentMs += 100
                        else -> waitedMs += 100
                    }
                    if (speech && silentMs >= 1500) break
                    if (!speech && waitedMs >= 7000) break
                }
            } finally {
                rec.stop()
                rec.release()
            }
            if (speech) out.copyOf(n) else FloatArray(0)
        }
}
