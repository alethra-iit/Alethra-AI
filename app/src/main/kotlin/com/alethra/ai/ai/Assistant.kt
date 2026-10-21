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
        "Kamu adalah Alethra, asisten AI pribadi yang berjalan sepenuhnya offline di perangkat pengguna. " +
        "Jawablah dalam bahasa Indonesia yang jelas, ringkas, dan ramah, kecuali pengguna memakai bahasa lain. " +
        "Jika tidak tahu, katakan terus terang."

    suspend fun newConversation(): Long =
        dao.insertConversation(ConversationEntity(title = "Percakapan baru", updatedAt = System.currentTimeMillis()))

    /** Rekam suara lalu ubah menjadi teks. Mengembalikan "" jika tidak ada ucapan atau terjadi error. */
    suspend fun listen(shouldStop: () -> Boolean): String {
        try {
            status.value = Status.Listening
            val pcm = Recorder.record(shouldStop = shouldStop)
            if (pcm.isEmpty()) return ""
            status.value = Status.Transcribing
            if (!whisper.ensureLoaded()) {
                error.value = "Model Whisper belum diimpor. Buka Pengaturan → Impor model suara."
                return ""
            }
            return whisper.transcribe(pcm, "id").replace(Regex("\\[[^\\]]*]|\\([^)]*\\)"), "").trim()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = e.message ?: "Gagal merekam suara"
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
                error.value = "Model AI belum diimpor. Buka Pengaturan → Impor model LLM (.gguf)."
                return ""
            }
            status.value = Status.Thinking
            llama.generate(history).collect { sb.append(it); onPartial(sb.toString()) }
        } catch (e: CancellationException) {
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
        if (speak && sb.isNotBlank()) tts.speak(sb.toString())
        return sb.toString()
    }
}
