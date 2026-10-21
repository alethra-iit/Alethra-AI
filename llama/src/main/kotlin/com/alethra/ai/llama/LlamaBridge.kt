package com.alethra.ai.llama

interface TokenCallback {
    /** Dipanggil untuk setiap potongan teks (UTF-8). Kembalikan false untuk menghentikan generasi. */
    fun onToken(bytes: ByteArray): Boolean
}

object LlamaBridge {
    init { System.loadLibrary("alethra_llama") }

    external fun load(path: String, nCtx: Int, nThreads: Int): Long
    external fun generate(
        handle: Long, roles: Array<String>, contents: Array<ByteArray>,
        maxTokens: Int, temperature: Float, cb: TokenCallback
    ): Int
    external fun stop(handle: Long)
    external fun free(handle: Long)
}
