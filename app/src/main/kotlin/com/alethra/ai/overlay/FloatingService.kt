package com.alethra.ai.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.alethra.ai.AlethraApp
import com.alethra.ai.ai.Status
import com.alethra.ai.ui.AlethraTheme
import com.alethra.ai.ui.MainActivity
import com.alethra.ai.ui.Teal
import com.alethra.ai.ui.Violet
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Gelembung asisten yang melayang di atas aplikasi lain (suara + teks). */
class FloatingService : Service(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    companion object {
        val running = MutableStateFlow(false)
        private const val ACTION_STOP = "com.alethra.ai.STOP"
        private const val CHANNEL = "alethra_overlay"

        fun start(c: Context) = ContextCompat.startForegroundService(c, Intent(c, FloatingService::class.java))
        fun stop(c: Context) { c.stopService(Intent(c, FloatingService::class.java)) }
    }

    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val vmStore = ViewModelStore()
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = vmStore

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var view: ComposeView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val assistant get() = (application as AlethraApp).assistant

    private var expanded by mutableStateOf(false)
    private var reply by mutableStateOf("")
    private var convId: Long? = null
    private var job: Job? = null
    private var stopListening = false
    private var bubbleX = 0
    private var bubbleY = 400

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val ch = NotificationChannel(CHANNEL, "Floating assistant", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        ServiceCompat.startForeground(this, 1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        if (view == null) addOverlay()
        return START_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, FloatingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Alethra AI aktif")
            .setContentText("Ketuk gelembung untuk berbicara dengan asisten")
            .setContentIntent(open)
            .addAction(0, "Tutup", stop)
            .setOngoing(true)
            .build()
    }

    private fun addOverlay() {
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX; y = bubbleY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        registry.currentState = Lifecycle.State.RESUMED
        val v = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@FloatingService)
            setViewTreeSavedStateRegistryOwner(this@FloatingService)
            setViewTreeViewModelStoreOwner(this@FloatingService)
            setContent { AlethraTheme { Overlay() } }
        }
        wm.addView(v, lp)
        view = v
    }

    private fun applyExpanded(e: Boolean) {
        val v = view ?: return
        if (e) {
            bubbleX = lp.x; bubbleY = lp.y
            val w = (resources.displayMetrics.widthPixels * 0.92f).toInt()
            lp.width = w
            lp.x = (resources.displayMetrics.widthPixels - w) / 2
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT
            lp.x = bubbleX
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        expanded = e
        wm.updateViewLayout(v, lp)
    }

    private fun sendText(text: String, speak: Boolean) {
        if (text.isBlank() || job?.isActive == true) return
        job = scope.launch {
            val id = convId ?: assistant.newConversation().also { convId = it }
            reply = ""
            assistant.ask(id, text.trim(), speak) { reply = it }
            assistant.error.value?.let { reply = it; assistant.error.value = null }
        }
    }

    private fun voice() {
        if (job?.isActive == true) { stopListening = true; return }
        stopListening = false
        job = scope.launch {
            val t = assistant.listen { stopListening }
            if (t.isBlank()) {
                assistant.error.value?.let { reply = it; assistant.error.value = null }
                return@launch
            }
            val id = convId ?: assistant.newConversation().also { convId = it }
            reply = ""
            assistant.ask(id, t, true) { reply = it }
            assistant.error.value?.let { reply = it; assistant.error.value = null }
        }
    }

    private fun cancelAll() { stopListening = true; assistant.tts.stop(); job?.cancel() }

    @Composable
    private fun Overlay() {
        val status by assistant.status.collectAsState()
        if (!expanded) Bubble(status) else Panel(status)
    }

    @Composable
    private fun Bubble(status: Status) {
        Box(
            Modifier.size(58.dp)
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Violet, Teal)))
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        lp.x += drag.x.toInt(); lp.y += drag.y.toInt()
                        view?.let { wm.updateViewLayout(it, lp) }
                    }
                }
                .pointerInput(Unit) { detectTapGestures(onTap = { applyExpanded(true) }) },
            contentAlignment = Alignment.Center
        ) {
            if (status == Status.Idle) Icon(Icons.Default.AutoAwesome, null, tint = Color.White)
            else CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 3.dp)
        }
    }

    @Composable
    private fun Panel(status: Status) {
        var input by remember { mutableStateOf("") }
        Surface(
            Modifier.shadow(12.dp, RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Alethra AI", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton({ applyExpanded(false) }) { Icon(Icons.Default.KeyboardArrowDown, "Kecilkan") }
                    IconButton({ cancelAll(); stopSelf() }) { Icon(Icons.Default.Close, "Tutup") }
                }
                Box(Modifier.heightIn(min = 60.dp, max = 240.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        reply.ifEmpty { "Ketuk mikrofon dan bicara, atau ketik pertanyaan Anda." },
                        color = if (reply.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    when (status) {
                        Status.Idle -> ""
                        Status.Loading -> "Memuat model…"
                        Status.Listening -> "Mendengarkan…"
                        Status.Transcribing -> "Mengubah suara ke teks…"
                        Status.Thinking -> "Berpikir…"
                    },
                    color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        input, { input = it }, Modifier.weight(1f), placeholder = { Text("Tulis pesan…") },
                        singleLine = true, shape = RoundedCornerShape(24.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    when {
                        status == Status.Thinking || status == Status.Loading || status == Status.Transcribing ->
                            FilledIconButton({ cancelAll() }) { Icon(Icons.Default.Stop, "Berhenti") }
                        input.isNotBlank() ->
                            FilledIconButton({ sendText(input, false); input = "" }) { Icon(Icons.AutoMirrored.Filled.Send, "Kirim") }
                        else ->
                            FilledIconButton({ voice() }) {
                                Icon(if (status == Status.Listening) Icons.Default.Stop else Icons.Default.Mic, "Bicara")
                            }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        view?.let { wm.removeView(it) }
        view = null
        registry.currentState = Lifecycle.State.DESTROYED
        vmStore.clear()
        running.value = false
        super.onDestroy()
    }
}
