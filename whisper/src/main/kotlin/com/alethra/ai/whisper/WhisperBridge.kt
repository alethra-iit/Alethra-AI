package com.alethra.ai.whisper

object WhisperBridge {
    init { System.loadLibrary("alethra_whisper") }

    external fun load(path: String): Long
    /** Mengembalikan teks UTF-8. Audio: PCM float mono 16 kHz. */
    external fun transcribe(handle: Long, audio: FloatArray, lang: String, threads: Int): ByteArray
    external fun free(handle: Long)
}
