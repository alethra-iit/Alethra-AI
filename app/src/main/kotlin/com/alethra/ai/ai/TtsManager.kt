package com.alethra.ai.ai

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * TTS dengan mode streaming: teks yang masuk sedikit demi sedikit dipecah per kalimat/klausa
 * dan langsung dibacakan, tanpa menunggu jawaban selesai.
 *
 * Pemakaian: beginStream() -> feed(potongan)* -> endStream().  speak(teks) = ketiganya sekaligus.
 * `speaking` menyala selama masih ada kalimat yang diputar/antre; awaitDone() menunggu sampai habis.
 */
class TtsManager(ctx: Context) {
    private var ready = false
    private val buf = StringBuilder()
    private var seq = 0
    /** 0 = Langsung (per beberapa kata), 1 = Seimbang, 2 = Mulus. Makin tinggi, makin banyak teks ditampung sebelum diucapkan. */
    @Volatile var smooth = 0
    private var firstTokenAt = 0L
    private var first = true   // utterance berikutnya yang dikirim adalah yang pertama (flush antrean lama)

    // "Generasi" dipasang di id utterance supaya callback sisa dari sesi lama (setelah stop/flush) diabaikan.
    private val gen = AtomicInteger(0)
    private val pending = AtomicInteger(0)

    /** true selama ada kalimat yang sedang diputar atau masih antre. */
    val speaking = MutableStateFlow(false)
    val isSpeaking: Boolean get() = speaking.value

    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale("id", "ID"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault())
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finished(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finished(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            })
            ready = true
        }
    }

    private fun finished(id: String?) {
        val g = id?.split('-')?.getOrNull(1)?.toIntOrNull() ?: return
        if (g != gen.get()) return          // callback dari sesi lama
        if (pending.updateAndGet { maxOf(0, it - 1) } == 0) speaking.value = false
    }

    private fun resetSession() {
        gen.incrementAndGet()
        pending.set(0)
        speaking.value = false
    }

    /** Mulai sesi baca baru: hentikan suara yang sedang berjalan dan kosongkan buffer. */
    @Synchronized fun beginStream() {
        resetSession()
        if (ready) tts.stop()
        buf.setLength(0)
        first = true
        firstTokenAt = 0L
    }

    /** Tambahkan potongan teks dari LLM; kalimat yang sudah lengkap langsung dibacakan. */
    @Synchronized fun feed(delta: String) {
        if (firstTokenAt == 0L) firstTokenAt = SystemClock.elapsedRealtime()
        buf.append(delta)
        while (true) {
            val cut = findCut()
            if (cut <= 0) break
            val part = buf.substring(0, cut)
            buf.delete(0, cut)
            enqueue(part)
        }
    }

    /** Teks sudah selesai: bacakan sisa buffer. */
    @Synchronized fun endStream() {
        if (buf.isNotEmpty()) {
            val rest = buf.toString()
            buf.setLength(0)
            enqueue(rest)
        }
    }

    /** Baca teks utuh (mis. tombol putar ulang di balon chat). */
    fun speak(text: String) {
        beginStream()
        feed(text)
        endStream()
    }

    /** Hentikan suara seketika dan buang antrean. */
    @Synchronized fun stop() {
        buf.setLength(0)
        first = true
        firstTokenAt = 0L
        resetSession()
        if (ready) tts.stop()
    }

    /** Menunggu semua kalimat selesai diputar. Bila dibatalkan, suara ikut dihentikan. */
    suspend fun awaitDone(timeoutMs: Long = 180_000) {
        try {
            withTimeoutOrNull(timeoutMs) {
                while (pending.get() > 0 || synchronized(this@TtsManager) { buf.isNotEmpty() }) delay(40)
            }
        } catch (e: CancellationException) {
            stop()
            throw e
        }
    }

    /**
     * Panjang minimum (karakter) sebuah potongan sebelum boleh diucapkan. Potongan terlalu pendek membuat
     * suara terputus-putus: tiap utterance butuh waktu sintesis sendiri, dan bila LLM menulis lebih lambat
     * daripada kecepatan bicara, antrean suara kosong dan terdengar jeda. Menunggu sedikit lebih banyak teks
     * mengurangi jeda itu, dengan harga suara mulai sedikit lebih lambat.
     */
    private fun minChunk(): Int {
        val waited = if (firstTokenAt == 0L) 0L else SystemClock.elapsedRealtime() - firstTokenAt
        return when (smooth) {
            0 -> 0
            1 -> if (first) (if (waited > 2500) 20 else 50) else 40
            else -> if (first) (if (waited > 4000) 30 else 90) else 60
        }
    }

    /**
     * Mode Langsung: tidak menunggu akhir kalimat. Potong setiap beberapa kata utuh (3 kata untuk potongan
     * pertama agar suara cepat mulai, 4 kata untuk berikutnya), atau lebih awal bila ketemu tanda baca.
     * Kata yang belum selesai ditulis model tidak ikut dipotong.
     */
    private fun findCutLive(): Int {
        val minWords = if (first) 3 else 4
        var words = 0
        for (i in 0 until buf.length) {
            val c = buf[i]
            if (c == '\n') return i + 1
            if (i + 1 >= buf.length) break
            val nextIsSpace = buf[i + 1].isWhitespace()
            if (nextIsSpace && (c == '.' || c == '!' || c == '?' || c == '…')) return i + 1
            if (nextIsSpace && (c == ',' || c == ';' || c == ':') && words >= 2) return i + 1
            if (c.isWhitespace() && i > 0 && !buf[i - 1].isWhitespace()) {
                words++
                if (words >= minWords) return i + 1
            }
        }
        return -1
    }

    /** Posisi potong (eksklusif) di buffer, atau -1 bila belum ada batas yang aman. */
    private fun findCut(): Int {
        if (smooth == 0) return findCutLive()
        val min = minChunk()
        val minComma = maxOf(min, if (first) 24 else 60)
        for (i in 0 until buf.length) {
            val c = buf[i]
            if (c == '\n') { if (i + 1 >= min) return i + 1; continue }
            // Perlu karakter setelahnya agar "3.5" atau "dll.," tidak terpotong keliru.
            if (i + 1 >= buf.length) break
            if (!buf[i + 1].isWhitespace()) continue
            if ((c == '.' || c == '!' || c == '?' || c == '…') && i + 1 >= min) return i + 1
            if ((c == ',' || c == ';' || c == ':') && i >= minComma) return i + 1
        }
        // Tidak ada tanda baca tapi sudah panjang: potong di spasi terakhir.
        if (buf.length > 200) {
            val sp = buf.lastIndexOf(" ", 180)
            if (sp > 0) return sp + 1
        }
        return -1
    }

    private fun enqueue(raw: String) {
        if (!ready) return
        val clean = raw
            .replace(Regex("[*#`>_~|]"), "")
            .replace(Regex("[\\p{So}\\p{Cs}]"), "")   // emoji/simbol agar tidak dibacakan namanya
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.none { it.isLetterOrDigit() }) return
        val mode = if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        first = false
        val id = "alethra-${gen.get()}-${seq++}"
        pending.incrementAndGet()
        speaking.value = true
        if (tts.speak(clean.take(3500), mode, null, id) != TextToSpeech.SUCCESS) finished(id)
    }
}
