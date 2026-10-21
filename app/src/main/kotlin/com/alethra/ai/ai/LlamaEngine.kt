package com.alethra.ai.ai

import com.alethra.ai.llama.LlamaBridge
import com.alethra.ai.llama.TokenCallback
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LlamaEngine(private val store: ModelStore, private val prefs: Prefs) {
    private var handle = 0L
    private val loadLock = Mutex()
    private val runLock = Mutex()

    val isLoaded get() = handle != 0L

    suspend fun ensureLoaded(): Boolean = loadLock.withLock {
        if (handle == 0L && store.hasLlm()) {
            handle = withContext(Dispatchers.Default) {
                LlamaBridge.load(store.llmFile.absolutePath, 4096, prefs.threads)
            }
        }
        handle != 0L
    }

    suspend fun unload() = loadLock.withLock {
        if (handle != 0L) {
            LlamaBridge.stop(handle)
            runLock.withLock { LlamaBridge.free(handle); handle = 0L }
        }
    }

    /** history: pasangan (role, content) termasuk "system". */
    fun generate(history: List<Pair<String, String>>, maxTokens: Int = 768, temp: Float = 0.7f): Flow<String> =
        channelFlow {
            val h = handle
            check(h != 0L) { "Model belum dimuat" }
            val cb = object : TokenCallback {
                override fun onToken(bytes: ByteArray): Boolean {
                    trySend(String(bytes, Charsets.UTF_8))
                    return isActive
                }
            }
            val rc = runLock.withLock {
                withContext(Dispatchers.Default) {
                    LlamaBridge.generate(
                        h,
                        history.map { it.first }.toTypedArray(),
                        history.map { it.second.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                        maxTokens, temp, cb
                    )
                }
            }
            if (rc < 0) error(
                when (rc) {
                    -1 -> "Template chat model tidak valid"
                    -3 -> "Percakapan terlalu panjang, mulai percakapan baru"
                    else -> "Inferensi gagal (kode $rc)"
                }
            )
        }.buffer(Channel.UNLIMITED)
}
