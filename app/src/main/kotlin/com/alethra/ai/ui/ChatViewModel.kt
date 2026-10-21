package com.alethra.ai.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alethra.ai.AlethraApp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val a = app as AlethraApp
    private val dao = a.db.dao()
    val assistant = a.assistant
    val prefs = a.prefs

    val conversations = dao.conversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val currentId = MutableStateFlow<Long?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val messages = currentId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else dao.messages(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val streaming = MutableStateFlow<String?>(null)
    val status = assistant.status
    val error = assistant.error

    private var job: Job? = null
    private var stopListening = false

    private suspend fun runTurn(text: String, speak: Boolean) {
        val id = currentId.value ?: assistant.newConversation().also { currentId.value = it }
        streaming.value = ""
        try {
            assistant.ask(id, text, speak) { streaming.value = it }
        } finally {
            streaming.value = null
        }
    }

    fun send(text: String) {
        if (text.isBlank() || job?.isActive == true) return
        job = viewModelScope.launch { runTurn(text.trim(), prefs.speakReplies) }
    }

    /** Tekan sekali untuk mulai merekam, tekan lagi untuk menyelesaikan rekaman. */
    fun voice() {
        if (job?.isActive == true) { stopListening = true; return }
        stopListening = false
        job = viewModelScope.launch {
            val t = assistant.listen { stopListening }
            if (t.isNotBlank()) runTurn(t, true)
        }
    }

    fun stop() {
        stopListening = true
        assistant.tts.stop()
        job?.cancel()
    }

    fun speak(text: String) = assistant.tts.speak(text)
    fun newChat() { stop(); currentId.value = null }
    fun open(id: Long) { stop(); currentId.value = id }
    fun delete(id: Long) {
        if (currentId.value == id) { stop(); currentId.value = null }
        viewModelScope.launch { dao.deleteConversation(id) }
    }
}
