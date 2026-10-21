package com.alethra.ai.ai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("alethra", Context.MODE_PRIVATE)
    var speakReplies: Boolean
        get() = sp.getBoolean("speak", true)
        set(v) = sp.edit().putBoolean("speak", v).apply()
    val threads: Int
        get() = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
}

/** Model disimpan di penyimpanan internal aplikasi dan diimpor dari file yang dipilih pengguna. */
class ModelStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "models").apply { mkdirs() }
    val llmFile = File(dir, "llm.gguf")
    val sttFile = File(dir, "whisper.bin")

    fun hasLlm() = llmFile.length() > 0
    fun hasStt() = sttFile.length() > 0

    fun delete(target: File) {
        target.delete()
        File(dir, target.name + ".part").delete()
        File(dir, target.name + ".tmp").delete()
    }

    suspend fun import(uri: Uri, target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val total = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val tmp = File(dir, target.name + ".tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                while (true) {
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    done += r
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        target.delete()
        check(tmp.renameTo(target)) { "Gagal menyimpan model" }
    }

    companion object {
        const val LLM_URL = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf"
        const val STT_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
    }

    /** Unduh dengan resume: jika terputus, panggil lagi dan lanjut dari bagian yang sudah ada. */
    suspend fun download(url: String, target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val part = File(dir, target.name + ".part")
        val offset = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = true
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = conn.responseCode
            check(code == 200 || code == 206) { "Server menjawab HTTP $code" }
            val resumed = code == 206
            val total = conn.contentLengthLong.let { if (it > 0) it + (if (resumed) offset else 0L) else -1L }
            var done = if (resumed) offset else 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            target.delete()
            check(part.renameTo(target)) { "Gagal menyimpan model" }
        } finally {
            conn.disconnect()
        }
    }
}
