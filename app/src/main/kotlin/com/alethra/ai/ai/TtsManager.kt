package com.alethra.ai.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class TtsManager(ctx: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale("id", "ID"))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.getDefault())
            }
            ready = true
        }
    }

    fun speak(text: String) {
        if (!ready) return
        val clean = text.replace(Regex("[*#`>_~|]"), "").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        clean.chunked(3500).forEachIndexed { i, part ->
            tts.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "alethra-$i")
        }
    }

    fun stop() { if (ready) tts.stop() }
}
