package com.alethra.ai.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Jenis model yang bisa dipasang lebih dari satu; hanya satu per jenis yang aktif dipakai. */
enum class ModelKind(val dirName: String, val ext: String) {
    LLM("llm", "gguf"),
    STT("stt", "bin")
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("alethra", Context.MODE_PRIVATE)
    var speakReplies: Boolean
        get() = sp.getBoolean("speak", true)
        set(v) = sp.edit().putBoolean("speak", v).apply()

    /** Floating assistant: langsung masuk mode ngobrol bebas tangan saat dibuka. */
    var autoHandsFree: Boolean
        get() = sp.getBoolean("auto_hands_free", false)
        set(v) = sp.edit().putBoolean("auto_hands_free", v).apply()

    /** Mode bebas tangan: pengguna boleh memotong suara AI dengan berbicara. */
    var bargeIn: Boolean
        get() = sp.getBoolean("barge_in", true)
        set(v) = sp.edit().putBoolean("barge_in", v).apply()

    /** Mode tampilan (0 = ikuti sistem, 1 = terang, 2 = gelap). Default gelap seperti sebelumnya. */
    var themeMode by mutableIntStateOf(sp.getInt("theme_mode", 2))
        private set
    /** Indeks palet warna di `Palettes` (ui/Theme.kt). */
    var paletteIndex by mutableIntStateOf(sp.getInt("palette", 0))
        private set

    fun updateThemeMode(v: Int) { themeMode = v; sp.edit().putInt("theme_mode", v).apply() }
    fun updatePalette(v: Int) { paletteIndex = v; sp.edit().putInt("palette", v).apply() }

    /** Id model yang dipilih pengguna. Jika berkasnya hilang, ModelStore memakai model pertama yang ada. */
    var activeLlm by mutableStateOf(sp.getString("active_llm", "") ?: "")
        private set
    var activeStt by mutableStateOf(sp.getString("active_stt", "") ?: "")
        private set

    fun activeId(kind: ModelKind) = if (kind == ModelKind.LLM) activeLlm else activeStt
    fun setActive(kind: ModelKind, id: String) {
        if (kind == ModelKind.LLM) activeLlm = id else activeStt = id
        sp.edit().putString(if (kind == ModelKind.LLM) "active_llm" else "active_stt", id).apply()
    }

    val threads: Int
        get() = (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
}

data class Preset(val id: String, val name: String, val size: String, val desc: String, val url: String)

data class Installed(val id: String, val name: String, val file: File) {
    val bytes: Long get() = file.length()
}

/**
 * Model disimpan di penyimpanan internal aplikasi: models/llm/<id>.gguf dan models/stt/<id>.bin.
 * Boleh ada banyak model; yang dipakai adalah yang dipilih di Pengaturan (Prefs.activeLlm / activeStt).
 */
class ModelStore(private val ctx: Context, private val prefs: Prefs) {
    private val root = File(ctx.filesDir, "models").apply { mkdirs() }

    init { migrateLegacy() }

    private fun dir(kind: ModelKind) = File(root, kind.dirName).apply { mkdirs() }

    fun fileFor(kind: ModelKind, id: String) = File(dir(kind), "$id.${kind.ext}")

    /** Versi 1.x menyimpan satu model di models/llm.gguf dan models/whisper.bin; pindahkan ke struktur baru. */
    private fun migrateLegacy() {
        val oldLlm = File(root, "llm.gguf")
        val oldStt = File(root, "whisper.bin")
        if (oldLlm.length() > 0) oldLlm.renameTo(fileFor(ModelKind.LLM, LEGACY_ID))
        if (oldStt.length() > 0) oldStt.renameTo(fileFor(ModelKind.STT, LEGACY_ID))
        listOf("llm.gguf.part", "llm.gguf.tmp", "whisper.bin.part", "whisper.bin.tmp").forEach { File(root, it).delete() }
    }

    fun presets(kind: ModelKind) = if (kind == ModelKind.LLM) LLM_PRESETS else STT_PRESETS

    fun displayName(kind: ModelKind, id: String): String = when {
        id == LEGACY_ID -> "Model terpasang sebelumnya"
        id.startsWith(CUSTOM_PREFIX) -> id.removePrefix(CUSTOM_PREFIX).replace('_', ' ') + " (impor)"
        else -> presets(kind).firstOrNull { it.id == id }?.name ?: id
    }

    fun installed(kind: ModelKind): List<Installed> =
        (dir(kind).listFiles { f -> f.isFile && f.extension == kind.ext && f.length() > 0 } ?: emptyArray())
            .sortedBy { it.name }
            .map { Installed(it.nameWithoutExtension, displayName(kind, it.nameWithoutExtension), it) }

    /** Model yang benar-benar dipakai: pilihan pengguna, atau model pertama bila pilihan itu tidak ada. */
    fun active(kind: ModelKind): Installed? {
        val list = installed(kind)
        return list.firstOrNull { it.id == prefs.activeId(kind) } ?: list.firstOrNull()
    }

    fun activeFile(kind: ModelKind): File? = active(kind)?.file
    fun hasLlm() = active(ModelKind.LLM) != null
    fun hasStt() = active(ModelKind.STT) != null

    fun delete(item: Installed) {
        item.file.delete()
        discardPartial(item.file)
    }

    /** Buang sisa unduhan/impor yang belum selesai (dipakai saat dibatalkan). */
    fun discardPartial(target: File) {
        val d = target.parentFile ?: return
        File(d, target.name + ".part").delete()
        File(d, target.name + ".part.src").delete()
        File(d, target.name + ".tmp").delete()
    }

    /** Nama berkas yang dipilih pengguna → id aman untuk nama berkas. */
    fun customIdFor(uri: Uri): String {
        val raw = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: "model"
        val base = raw.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9_-]"), "_").take(60).ifBlank { "model" }
        return CUSTOM_PREFIX + base
    }

    suspend fun import(uri: Uri, target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val total = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            ctx.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    while (true) {
                        ensureActive() // berhenti segera jika dibatalkan
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
        } finally {
            tmp.delete() // no-op jika sudah di-rename; membersihkan sisa bila gagal/dibatalkan
        }
    }

    /**
     * Unduh dengan resume: jika terputus, panggil lagi dan lanjut dari bagian yang sudah ada
     * (hanya bila sumbernya sama). Bisa dibatalkan dengan membatalkan coroutine-nya; koneksi
     * langsung diputus supaya tidak menunggu timeout.
     */
    suspend fun download(url: String, target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val d = target.parentFile!!
        val part = File(d, target.name + ".part")
        val src = File(d, target.name + ".part.src")
        // Sisa unduhan dari sumber lain tidak boleh disambung ke URL baru.
        if (part.exists() && (!src.exists() || src.readText() != url)) { part.delete() }
        src.writeText(url)

        val offset = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20000; readTimeout = 30000; instanceFollowRedirects = true
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        // Saat dibatalkan, putuskan koneksi agar read() yang sedang menunggu langsung berhenti.
        val cancelHook: DisposableHandle? = coroutineContext[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException) conn.disconnect()
        }
        try {
            ensureActive()
            val code = conn.responseCode
            check(code == 200 || code == 206) { "Server menjawab HTTP $code" }
            val resumed = code == 206
            val total = conn.contentLengthLong.let { if (it > 0) it + (if (resumed) offset else 0L) else -1L }
            var done = if (resumed) offset else 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, resumed).use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        ensureActive()
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
            src.delete()
        } finally {
            cancelHook?.dispose()
            conn.disconnect()
        }
    }

    companion object {
        const val LEGACY_ID = "lama"
        const val CUSTOM_PREFIX = "custom-"

        private fun hf(path: String) = "https://huggingface.co/$path"

        val LLM_PRESETS = listOf(
            Preset("qwen25-7b", "Qwen2.5 7B Instruct", "±4,7 GB", "Seimbang. Bagus untuk bahasa Indonesia. Rekomendasi utama.",
                hf("bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf")),
            Preset("gemma3-4b", "Gemma 3 4B", "±2,5 GB", "Lebih ringan dan cepat, kualitas multibahasa baik.",
                hf("bartowski/google_gemma-3-4b-it-GGUF/resolve/main/google_gemma-3-4b-it-Q4_K_M.gguf")),
            Preset("llama32-3b", "Llama 3.2 3B Instruct", "±2,0 GB", "Paling ringan dan cepat; bahasa Indonesia cukup, kurang lancar.",
                hf("bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf")),
            Preset("gemma3-12b", "Gemma 3 12B", "±7,3 GB", "Kualitas terbaik tapi berat dan lambat; butuh RAM lega (tutup app lain).",
                hf("bartowski/google_gemma-3-12b-it-GGUF/resolve/main/google_gemma-3-12b-it-Q4_K_M.gguf"))
        )

        /** Semua berformat GGML untuk whisper.cpp, jadi langsung jalan di engine yang sudah ada. */
        val STT_PRESETS = listOf(
            Preset("whisper-small-id", "Whisper Small • Indonesia", "±264 MB",
                "Fine-tune khusus Indonesia (maleo-ai). Jauh lebih akurat dari Small multibahasa: WER ±9–10% vs ±19–21% (tes FLEURS/Common Voice). Rekomendasi utama. Keluaran huruf kecil tanpa tanda baca.",
                hf("maleo-ai/whisper-small-id/resolve/main/ggml-small-id-q8_0.bin")),
            Preset("whisper-medium-id", "Whisper Medium • Indonesia (cahya)", "±514 MB",
                "Versi GGML Q5_0 dari cahya/whisper-medium-id. Paling akurat (WER ±3,8% Common Voice, ±9,7% FLEURS) tapi paling berat dan lambat.",
                hf("rafdiraf/whisper-medium-id-ggml/resolve/main/ggml-model-medium-id-q5_0.bin")),
            Preset("whisper-tiny-id", "Whisper Tiny • Indonesia", "±43 MB",
                "Sangat kecil dan cepat untuk ponsel lemah, tapi akurasi dasar (WER ±18–19%). Keluaran huruf kecil tanpa tanda baca.",
                hf("maleo-ai/whisper-tiny-id/resolve/main/ggml-tiny-id-q8_0.bin")),
            Preset("whisper-small-multi", "Whisper Small • Multibahasa", "±470 MB",
                "Model umum OpenAI untuk banyak bahasa. Pilih ini bila sering berbicara dalam bahasa selain Indonesia.",
                hf("ggerganov/whisper.cpp/resolve/main/ggml-small.bin"))
        )
    }
}
