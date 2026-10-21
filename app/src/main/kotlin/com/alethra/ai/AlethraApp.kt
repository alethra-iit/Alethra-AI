package com.alethra.ai

import android.app.Application
import androidx.room.Room
import com.alethra.ai.ai.*
import com.alethra.ai.data.AppDb

class AlethraApp : Application() {
    lateinit var db: AppDb
    lateinit var prefs: Prefs
    lateinit var store: ModelStore
    lateinit var assistant: Assistant
    lateinit var transfers: TransferManager

    override fun onCreate() {
        super.onCreate()
        db = Room.databaseBuilder(this, AppDb::class.java, "alethra.db").build()
        prefs = Prefs(this)
        store = ModelStore(this, prefs)
        assistant = Assistant(
            db.dao(), LlamaEngine(store, prefs), WhisperEngine(store, prefs), TtsManager(this)
        )
        assistant.tts.smooth = prefs.ttsSmooth
        transfers = TransferManager(this, store, prefs, assistant)
    }
}
