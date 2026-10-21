package com.alethra.ai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alethra.ai.ai.Status
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onSettings: () -> Unit, requestMic: (() -> Unit) -> Unit) {
    val msgs by vm.messages.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val convs by vm.conversations.collectAsStateWithLifecycle()
    val current by vm.currentId.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }

    LaunchedEffect(error) { error?.let { snack.showSnackbar(it); vm.error.value = null } }
    val total = msgs.size + if (streaming != null) 1 else 0
    LaunchedEffect(msgs.size, streaming) { if (total > 0) listState.scrollToItem(total - 1) }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Text("Riwayat", Modifier.padding(20.dp), style = MaterialTheme.typography.titleLarge)
                NavigationDrawerItem(
                    label = { Text("Percakapan baru") },
                    icon = { Icon(Icons.Default.Add, null) },
                    selected = false,
                    onClick = { vm.newChat(); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                LazyColumn {
                    items(convs, key = { it.id }) { c ->
                        NavigationDrawerItem(
                            label = { Text(c.title, maxLines = 1) },
                            selected = c.id == current,
                            onClick = { vm.open(c.id); scope.launch { drawer.close() } },
                            badge = {
                                IconButton(onClick = { vm.delete(c.id) }, Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, "Hapus", Modifier.size(18.dp))
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Alethra AI", fontWeight = FontWeight.SemiBold)
                            statusLabel(status)?.let {
                                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Riwayat") }
                    },
                    actions = {
                        IconButton(onSettings) { Icon(Icons.Default.Settings, "Pengaturan") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
                )
            }
        ) { inner ->
            Column(Modifier.padding(inner).fillMaxSize()) {
                if (total == 0) {
                    EmptyState(Modifier.weight(1f)) { vm.send(it) }
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth(),
                        state = listState,
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(msgs, key = { it.id }) { m ->
                            val user = m.role == "user"
                            Bubble(m.content, user, if (user) null else ({ vm.speak(m.content) }))
                        }
                        streaming?.let { s -> item { Bubble(s.ifEmpty { "…" }, false, null) } }
                    }
                }
                InputBar(
                    input, { input = it }, status, streaming != null,
                    onSend = { vm.send(input); input = "" },
                    onMic = { requestMic { vm.voice() } },
                    onStop = vm::stop
                )
            }
        }
    }
}

private fun statusLabel(s: Status) = when (s) {
    Status.Idle -> null
    Status.Loading -> "Memuat model…"
    Status.Listening -> "Mendengarkan…"
    Status.Transcribing -> "Mengubah suara ke teks…"
    Status.Thinking -> "Berpikir…"
}

@Composable
private fun EmptyState(modifier: Modifier, onPick: (String) -> Unit) {
    Column(modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Violet, Teal))),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Default.AutoAwesome, null, Modifier.size(40.dp), tint = Color.White) }
        Spacer(Modifier.height(20.dp))
        Text("Halo, saya Alethra", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Asisten AI pribadi yang berjalan 100% offline di ponsel Anda.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
        listOf("Jelaskan cara kerja fotosintesis", "Buatkan rencana belajar 7 hari", "Tulis email izin tidak masuk kerja").forEach {
            AssistChip(onClick = { onPick(it) }, label = { Text(it) }, modifier = Modifier.padding(vertical = 3.dp))
        }
    }
}

@Composable
private fun Bubble(text: String, user: Boolean, onSpeak: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 320.dp), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
            Surface(
                shape = RoundedCornerShape(20.dp, 20.dp, if (user) 20.dp else 4.dp, if (user) 4.dp else 20.dp),
                color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ) {
                SelectionContainer { Text(text, Modifier.padding(14.dp, 10.dp), style = MaterialTheme.typography.bodyLarge) }
            }
            if (onSpeak != null) {
                IconButton(onSpeak, Modifier.size(32.dp)) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, "Bacakan", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String, onChange: (String) -> Unit, status: Status, generating: Boolean,
    onSend: () -> Unit, onMic: () -> Unit, onStop: () -> Unit
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
            TextField(
                value = value, onValueChange = onChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Tulis pesan…") },
                shape = RoundedCornerShape(24.dp), maxLines = 4,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
            Spacer(Modifier.width(8.dp))
            val busy = generating || status == Status.Thinking || status == Status.Loading || status == Status.Transcribing
            when {
                busy -> FilledIconButton(onStop, Modifier.size(52.dp)) { Icon(Icons.Default.Stop, "Berhenti") }
                status == Status.Listening -> FilledIconButton(
                    onMic, Modifier.size(52.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) { Icon(Icons.Default.Stop, "Selesai merekam") }
                value.isNotBlank() -> FilledIconButton(onSend, Modifier.size(52.dp)) { Icon(Icons.AutoMirrored.Filled.Send, "Kirim") }
                else -> FilledIconButton(onMic, Modifier.size(52.dp)) { Icon(Icons.Default.Mic, "Bicara") }
            }
        }
    }
}
