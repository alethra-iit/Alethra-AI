package com.alethra.ai.ai

import com.alethra.ai.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

enum class Status { Idle, Loading, Listening, Transcribing, Thinking }

/** Orkestrator bersama (dipakai oleh UI utama dan floating assistant). */
class Assistant(
    private val dao: ChatDao,
    val llama: LlamaEngine,
    val whisper: WhisperEngine,
    val tts: TtsManager
) {
    val status = MutableStateFlow(Status.Idle)
    val error = MutableStateFlow<String?>(null)

    private val systemPrompt =
        "Kamu adalah Alethra, teman ngobrol pribadi yang berjalan offline di perangkat pengguna. " +
        "Ngobrollah santai dan natural seperti teman dekat, dalam bahasa Indonesia sehari-hari (kecuali pengguna memakai bahasa lain). " +
        "Balas singkat: biasanya 1 sampai 3 kalimat pendek, langsung ke inti, tanpa kalimat pembuka basa-basi. " +
        "Jangan memakai markdown, daftar, tabel, simbol, atau emoji, karena balasanmu dibacakan dengan suara. " +
        "Tanggapi perasaan dan konteks pengguna; sesekali boleh berkomentar atau bertanya balik agar obrolan mengalir, tapi jangan di setiap balasan. " +
        "Beri jawaban panjang hanya bila diminta. Jika tidak tahu, katakan terus terang."

    suspend fun newConversation(): Long =
        dao.insertConversation(ConversationEntity(title = "Percakapan baru", updatedAt = System.currentTimeMillis()))

    /** Rekam suara lalu ubah menjadi teks. Mengembalikan "" jika tidak ada ucapan atau terjadi error. */
    suspend fun listen(shouldStop: () -> Boolean): String {
        try {
            status.value = Status.Listening
            val pcm = Recorder.record(shouldStop = shouldStop)
            if (pcm.isEmpty()) return ""
            return transcribe(pcm)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = e.message ?: "Gagal merekam suara"
            return ""
        } finally {
            status.value = Status.Idle
        }
    }

    /** Ubah rekaman (16 kHz mono) menjadi teks. Mengembalikan "" bila gagal (pesan ada di [error]). */
    suspend fun transcribe(pcm: FloatArray): String {
        try {
            status.value = Status.Transcribing
            if (!whisper.ensureLoaded()) {
                error.value = "Belum ada model suara. Buka Pengaturan → Model suara untuk mengunduh atau mengimpor."
                return ""
            }
            return whisper.transcribe(pcm, "id").replace(Regex("\\[[^\\]]*]|\\([^)]*\\)"), "").trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = e.message ?: "Gagal mengubah suara ke teks"
            return ""
        } finally {
            status.value = Status.Idle
        }
    }

    suspend fun ask(convId: Long, text: String, speak: Boolean, onPartial: (String) -> Unit): String {
        val isFirst = dao.messagesOnce(convId).isEmpty()
        dao.insertMessage(MessageEntity(conversationId = convId, role = "user", content = text, createdAt = System.currentTimeMillis()))
        if (isFirst) dao.rename(convId, text.take(40))

        // Ambil riwayat terbaru dalam batas ~8000 karakter agar muat di konteks 4096 token.
        val all = dao.messagesOnce(convId)
        var budget = 8000
        val picked = ArrayDeque<MessageEntity>()
        for (m in all.asReversed()) {
            if (picked.isNotEmpty() && budget - m.content.length < 0) break
            budget -= m.content.length
            picked.addFirst(m)
        }
        val history = listOf("system" to systemPrompt) + picked.map { it.role to it.content.take(6000) }

        val sb = StringBuilder()
        try {
            status.value = if (llama.isLoaded) Status.Thinking else Status.Loading
            if (!llama.ensureLoaded()) {
                error.value = "Belum ada model bahasa. Buka Pengaturan → Model bahasa untuk mengunduh atau mengimpor."
                return ""
            }
            status.value = Status.Thinking
            // Streaming: setiap potongan teks langsung diteruskan ke TTS (dibacakan per kalimat),
            // jadi suara mulai saat kalimat pertama selesai, bukan setelah seluruh jawaban selesai.
            if (speak) tts.beginStream()
            llama.generate(history, maxTokens = 400).collect {
                sb.append(it)
                onPartial(sb.toString())
                if (speak) tts.feed(it)
            }
        } catch (e: CancellationException) {
            tts.stop()
            throw e
        } catch (e: Exception) {
            error.value = e.message ?: "Terjadi kesalahan"
        } finally {
            withContext(NonCancellable) {
                if (sb.isNotBlank()) {
                    dao.insertMessage(MessageEntity(conversationId = convId, role = "assistant", content = sb.toString().trim(), createdAt = System.currentTimeMillis()))
                    dao.touch(convId, System.currentTimeMillis())
                }
                status.value = Status.Idle
            }
        }
        if (speak) tts.endStream()   // bacakan sisa kalimat terakhir
        return sb.toString()
    }
}
