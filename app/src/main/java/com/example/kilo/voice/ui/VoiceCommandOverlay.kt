package com.example.kilo.voice.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.kilo.MainViewModel
import com.example.kilo.voice.domain.VoiceCommand
import com.example.kilo.voice.domain.VoiceExecution
import com.example.kilo.voice.recognition.AndroidSpeechRecognitionRepository
import com.example.kilo.voice.recognition.RecognitionController
import com.example.kilo.voice.recognition.RecognitionRepository
import com.example.kilo.voice.routing.VoiceCommandRouter
import kotlinx.coroutines.launch

@Composable
fun VoiceCommandOverlay(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = viewModel.state.collectAsState().value.activeSkin.highlight
    var showDialog by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var transcript by remember { mutableStateOf("") }
    var partial by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<VoiceCommand?>(null) }
    val router = remember(context, viewModel) { VoiceCommandRouter(context, viewModel) }
    val recognition = remember(context) { RecognitionController(AndroidSpeechRecognitionRepository(context)) }

    val listener = remember(recognition, router) {
        object : RecognitionRepository.Listener {
            override fun onListening() {
                listening = true
                statusMessage = "Listening…"
            }

            override fun onPartialResult(text: String) { partial = text }

            override fun onFinalResult(text: String) {
                listening = false
                transcript = text
                partial = ""
                statusMessage = "Working…"
                scope.launch {
                    val execution = router.execute(text)
                    statusMessage = execution.message
                    confirmation = execution.command.takeIf { execution.requiresConfirmation }
                }
            }

            override fun onFailure(message: String) {
                listening = false
                partial = ""
                statusMessage = message
            }
        }
    }

    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) recognition.begin(listener)
        else statusMessage = "Microphone access is needed for voice commands."
    }
    // Keep the permission launch in an effect-free callback, after the launcher exists.
    val startWithPermission: () -> Unit = {
        showDialog = true
        transcript = ""
        partial = ""
        confirmation = null
        statusMessage = "Starting microphone…"
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            recognition.begin(listener)
        } else {
            statusMessage = "Allow microphone access to use voice commands."
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    DisposableEffect(recognition) { onDispose { recognition.close() } }

    Surface(
        modifier = modifier.size(54.dp),
        shape = CircleShape,
        color = accent,
        shadowElevation = 8.dp
    ) {
        IconButton(onClick = startWithPermission) {
            Icon(Icons.Default.Mic, contentDescription = "Voice commands", tint = Color.Black, modifier = Modifier.size(25.dp))
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = {
                recognition.stop()
                listening = false
                showDialog = false
            },
            title = { Text("Speak to KILO", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        transcript.ifBlank { partial.ifBlank {
                            "Try “play my downloads”, “create playlist Road Trip” (or say “create playlist”, then give it a name), “add this song to playlist Favourites”, “add Blinding Lights to playlist Road Trip”, “rename playlist Road Trip to Weekend”, “download playlist Road Trip”, or “skip”."
                        } },
                        color = Color.White.copy(alpha = 0.88f)
                    )
                    Text(statusMessage, color = accent, fontWeight = FontWeight.Medium)
                    Text(
                        "Commands are interpreted on-device. Speech availability depends on Android’s installed voice service and offline language model; online music searches need internet.",
                        color = Color.White.copy(alpha = 0.58f),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                if (confirmation != null) {
                    TextButton(onClick = {
                        val command = confirmation
                        confirmation = null
                        if (command != null) scope.launch { statusMessage = router.confirm(command).message }
                    }) { Text("Confirm", color = accent) }
                } else {
                    TextButton(onClick = startWithPermission) { Text("Listen again", color = accent) }
                }
            },
            dismissButton = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (listening) {
                        FilledTonalButton(onClick = { recognition.stop(); listening = false }) {
                            Icon(Icons.Default.Stop, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Stop")
                        }
                    }
                    TextButton(onClick = {
                        recognition.stop()
                        listening = false
                        showDialog = false
                    }) { Text("Close", color = Color.White.copy(alpha = 0.72f)) }
                }
            },
            containerColor = Color(0xFF17131F),
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.82f)
        )
    }
}
