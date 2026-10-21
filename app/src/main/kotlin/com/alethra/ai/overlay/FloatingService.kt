package com.alethra.ai.overlay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.alethra.ai.ai.VoiceSession
import com.alethra.ai.ui.AlethraTheme
import com.alethra.ai.ui.MainActivity
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
    private var handsFree by mutableStateOf(false)
    private var micLevel by mutableFloatStateOf(0f)   // level suara mikrofon (RMS) untuk animasi gelombang
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
        if (view == null) {
            addOverlay()
            if ((application as AlethraApp).prefs.autoHandsFree) switchHandsFree(true)
        }
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

    /** Nyalakan/matikan mode ngobrol bebas tangan (mikrofon terus aktif selama mode ini menyala). */
    private fun switchHandsFree(on: Boolean) {
        if (on == handsFree) return
        cancelAll()
        handsFree = on
        if (on) runHandsFree()
    }

    /**
     * Loop percakapan: dengarkan → transkripsi → AI menjawab (dibacakan sambil menulis) → dengarkan lagi.
     * Selama AI berpikir/berbicara, mikrofon dipantau; bila pengguna mulai bicara, suara AI dipotong
     * dan ucapan pengguna langsung direkam (audio pembukanya ikut terbawa).
     */
    private fun runHandsFree() {
        stopListening = false
        job = scope.launch {
            val prefs = (application as AlethraApp).prefs
            val vs = VoiceSession { micLevel = it }
            try {
                vs.start()
                var carry: FloatArray? = null
                while (isActive && handsFree) {
                    assistant.status.value = Status.Listening
                    val pcm = try {
                        vs.listenUtterance(preRoll = carry)
                    } finally {
                        assistant.status.value = Status.Idle
                    }
                    carry = null
                    if (pcm.isEmpty()) continue                       // tidak ada ucapan: dengarkan lagi
                    val text = assistant.transcribe(pcm)
                    if (assistant.error.value != null) break
                    if (text.length < 2) continue                     // derau / halusinasi pendek
                    val id = convId ?: assistant.newConversation().also { convId = it }
                    reply = ""
                    carry = respondWithBargeIn(vs, id, text, prefs.bargeIn)
                    if (assistant.error.value != null) break
                    if (carry == null) delay(300)                     // beri waktu gema speaker mereda
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reply = e.message ?: "Mode bebas tangan berhenti"
            } finally {
                vs.close()
                micLevel = 0f
                assistant.status.value = Status.Idle
            }
            // Sampai di sini hanya bila loop berhenti sendiri (error), bukan karena dimatikan pengguna.
            assistant.error.value?.let { reply = it; assistant.error.value = null }
            handsFree = false
        }
    }

    /** Jalankan satu jawaban AI; kembalikan audio awal ucapan pengguna bila AI dipotong, selain itu null. */
    private suspend fun respondWithBargeIn(vs: VoiceSession, convId: Long, text: String, bargeIn: Boolean): FloatArray? {
        var pre: FloatArray? = null
        coroutineScope {
            val replyJob = launch {
                assistant.ask(convId, text, true) { reply = it }
                assistant.tts.awaitDone()
            }
            val bargeJob = if (!bargeIn) null else launch {
                // Ambang lebih tinggi saat suara AI keluar dari speaker, agar gemanya tidak memicu pemotongan.
                val p = vs.waitForBargeIn { if (assistant.tts.isSpeaking) 0.05f else 0.02f }
                if (p != null) { pre = p; replyJob.cancel() }
            }
            replyJob.join()
            bargeJob?.cancel()
        }
        return pre
    }

    private fun cancelAll() { stopListening = true; assistant.tts.stop(); job?.cancel() }

    @Composable
    private fun Overlay() {
        val status by assistant.status.collectAsState()
        val speaking by assistant.tts.speaking.collectAsState()
        if (!expanded) Bubble(status, speaking) else Panel(status, speaking)
    }

    private enum class Wave { None, User, Ai }

    /**
     * Lingkaran floating (tanpa kotak). Saat pengguna atau AI berbicara, ikonnya menjadi mikrofon dengan
     * gelombang di sekelilingnya: warna utama = pengguna (mengikuti kerasnya suara), warna kedua = AI berbicara.
     * Ketuk = buka panel, tahan lama = akhiri mode bebas tangan, seret = pindahkan.
     */
    @Composable
    private fun Bubble(status: Status, speaking: Boolean) {
        val busy = (status == Status.Thinking || status == Status.Loading || status == Status.Transcribing) && !speaking
        val wave = when {
            speaking -> Wave.Ai
            status == Status.Listening || (handsFree && !busy) -> Wave.User
            else -> Wave.None
        }
        val cs = MaterialTheme.colorScheme
        // User: ikuti level mikrofon (listen manual tanpa level -> gelombang sedang). AI: gelombang penuh.
        val target = when (wave) {
            Wave.Ai -> 1f
            Wave.User -> if (handsFree) (micLevel * 9f).coerceIn(0.12f, 1f) else 0.7f
            Wave.None -> 0f
        }
        val amp by animateFloatAsState(target, tween(120), label = "amp")

        Box(
            Modifier.size(88.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        lp.x += drag.x.toInt(); lp.y += drag.y.toInt()
                        view?.let { wm.updateViewLayout(it, lp) }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { applyExpanded(true) },
                        onLongPress = { if (handsFree) switchHandsFree(false) }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (wave != Wave.None) Waves(if (wave == Wave.Ai) cs.secondary else cs.primary, amp, Modifier.fillMaxSize())
            Box(
                Modifier.size(56.dp)
                    .shadow(8.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(cs.primary, cs.secondary))),
                contentAlignment = Alignment.Center
            ) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 3.dp)
                    wave != Wave.None || handsFree -> Icon(Icons.Default.Mic, "Mikrofon", tint = Color.White, modifier = Modifier.size(28.dp))
                    else -> Icon(Icons.Default.AutoAwesome, null, tint = Color.White)
                }
            }
        }
    }

    /** Tiga cincin yang memancar keluar dari tepi lingkaran; [amp] (0..1) mengatur jarak dan kejelasan. */
    @Composable
    private fun Waves(color: Color, amp: Float, modifier: Modifier) {
        val phase by rememberInfiniteTransition(label = "wave").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "phase"
        )
        Canvas(modifier) {
            val base = 28.dp.toPx()                       // jari-jari lingkaran
            val maxR = size.minDimension / 2f
            for (i in 0 until 3) {
                val p = (phase + i / 3f) % 1f
                val r = base + (maxR - base) * p * amp.coerceIn(0.15f, 1f)
                val a = (1f - p) * 0.6f * amp.coerceIn(0.25f, 1f)
                drawCircle(color.copy(alpha = a), r, center, style = Stroke(width = 3.dp.toPx()))
            }
        }
    }

    @Composable
    private fun Panel(status: Status, speaking: Boolean) {
        var input by remember { mutableStateOf("") }
        Surface(
            Modifier.shadow(12.dp, RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Alethra AI", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton({
                        switchHandsFree(!handsFree)
                        if (handsFree) applyExpanded(false)   // mode bebas tangan menyala -> sisakan lingkaran saja
                    }) {
                        Icon(
                            Icons.Default.Hearing, if (handsFree) "Matikan mode bebas tangan" else "Nyalakan mode bebas tangan",
                            tint = if (handsFree) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton({ applyExpanded(false) }) {
                        Icon(Icons.Default.KeyboardArrowDown, null)
                        Text("Kecilkan")
                    }
                    IconButton({ cancelAll(); stopSelf() }) { Icon(Icons.Default.Close, "Tutup") }
                }
                Box(Modifier.heightIn(min = 60.dp, max = 240.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        reply.ifEmpty {
                            if (handsFree) "Mode bebas tangan aktif. Silakan bicara kapan saja."
                            else "Ketuk mikrofon dan bicara, atau ketik pertanyaan Anda. Ikon telinga = mode ngobrol bebas tangan."
                        },
                        color = if (reply.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    when {
                        status == Status.Loading -> "Memuat model…"
                        status == Status.Transcribing -> "Mengubah suara ke teks…"
                        status == Status.Thinking && !speaking -> "Berpikir…"
                        speaking -> if (handsFree) "Berbicara… (bicara untuk memotong)" else "Berbicara…"
                        status == Status.Listening -> "Mendengarkan…"
                        else -> ""
                    },
                    color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.labelMedium
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        input, { input = it }, Modifier.weight(1f), placeholder = { Text("Tulis pesan…") },
                        singleLine = true, shape = RoundedCornerShape(24.dp), enabled = !handsFree,
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    when {
                        handsFree ->
                            FilledIconButton({ switchHandsFree(false) }) { Icon(Icons.Default.Stop, "Akhiri mode bebas tangan") }
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
        assistant.tts.stop()
        view?.let { wm.removeView(it) }
        view = null
        registry.currentState = Lifecycle.State.DESTROYED
        vmStore.clear()
        running.value = false
        super.onDestroy()
    }
}
