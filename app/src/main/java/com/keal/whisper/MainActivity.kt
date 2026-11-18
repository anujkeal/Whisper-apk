package com.keal.whisper

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WhisperApp() }
    }
}

@Composable
private fun WhisperApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var audioUri by remember { mutableStateOf<Uri?>(null) }
    var audioName by remember { mutableStateOf("No audio selected") }
    var model by remember { mutableStateOf("large-v3-turbo-q5_0") }
    var language by remember { mutableStateOf("auto") }
    var wordMode by remember { mutableStateOf(true) }
    var transcript by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Choose an audio file to begin.") }
    var progress by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            audioUri = uri
            audioName = uri.lastPathSegment?.substringAfterLast('/') ?: "Selected audio"
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            transcript = ""
            status = "Audio ready. Select Transcribe."
        }
    }
    val txtSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) saveText(context, uri, transcript)
    }
    val srtSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) saveText(context, uri, toSrt(transcript))
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("WHISPER", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Offline audio transcription", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("1. Audio file", fontWeight = FontWeight.SemiBold)
                        Text(audioName, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Choose MP3 / WAV / M4A")
                        }
                    }
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("2. Transcription settings", fontWeight = FontWeight.SemiBold)
                        ChoiceMenu("Model", model, listOf("tiny-q5_1", "base-q5_1", "small-q5_1", "medium-q5_0", "large-v3-turbo-q5_0", "large-v3-q5_0", "large-v3")) { model = it }
                        Text(when (model) {
                            "large-v3" -> "Absolute maximum · full Large v3 · about 2.9 GiB · extreme RAM and storage demand"
                            "large-v3-q5_0" -> "Maximum quantized option · about 1.1 GB · requires substantial free RAM and storage"
                            "large-v3-turbo-q5_0" -> "Best high-accuracy mobile balance · about 574 MB"
                            "medium-q5_0" -> "High accuracy · about 514 MB"
                            "small-q5_1" -> "Good quality for mid-range phones · about 181 MB"
                            "base-q5_1" -> "Lightweight · about 57 MB"
                            else -> "Smallest and fastest · about 31 MB"
                        }, style = MaterialTheme.typography.bodySmall)
                        if (model == "large-v3-q5_0" || model == "large-v3") {
                            Text("This model can fail to load on phones with limited available memory. Use Large v3 Turbo if it does.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        ChoiceMenu("Language", language, listOf("auto", "en", "hi")) { language = it }
                        Text(when (language) { "en" -> "English"; "hi" -> "Hindi"; else -> "Automatic detection" })
                        ChoiceMenu("Timestamp mode", if (wordMode) "word" else "sentence", listOf("sentence", "word")) { wordMode = it == "word" }
                    }
                }

                Button(
                    onClick = {
                        val selected = audioUri
                        if (selected == null || busy) return@Button
                        busy = true
                        progress = 0
                        status = "Preparing model…"
                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.IO) {
                                    val modelFile = ModelManager.ensureModel(context, model) { progress = it }
                                    status = "Loading Whisper model…"
                                    val nativeContext = WhisperNative.initModel(modelFile.absolutePath)
                                    if (nativeContext == 0L) error("Could not load the Whisper model. Re-download it and try again.")
                                    try {
                                        status = "Decoding audio…"
                                        val samples = AudioDecoder.decodeTo16kMono(context, selected)
                                        status = "Transcribing audio…"
                                        WhisperNative.transcribe(nativeContext, samples, language, if (wordMode) 1 else 0)
                                    } finally {
                                        WhisperNative.releaseModel(nativeContext)
                                    }
                                }
                                transcript = result
                                status = "Transcription complete."
                            } catch (e: Exception) {
                                status = "Error: ${e.message ?: "Something went wrong"}"
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = audioUri != null && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (busy) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                    else Text("Transcribe audio")
                }

                if (busy) {
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(if (progress in 1..99) "Model download: $progress%" else status, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("3. Transcript", fontWeight = FontWeight.SemiBold)
                        if (transcript.isBlank()) {
                            Text("Your timestamped transcript will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(transcript, style = MaterialTheme.typography.bodyMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { txtSaver.launch("transcript.txt") }, modifier = Modifier.weight(1f)) { Text("Export TXT") }
                                OutlinedButton(onClick = { srtSaver.launch("transcript.srt") }, modifier = Modifier.weight(1f)) { Text("Export SRT") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("High-accuracy mode uses beam search. Word timestamps come from Whisper's experimental token aligner; review names, numbers and unclear speech manually. Processing stays on this device after the model is downloaded.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChoiceMenu(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }) {
            Text(when (selected) {
                "tiny-q5_1" -> "Tiny · Q5"; "base-q5_1" -> "Base · Q5"
                "small-q5_1" -> "Small · Q5"; "medium-q5_0" -> "Medium · Q5"
                "large-v3-turbo-q5_0" -> "Large v3 Turbo · Q5"; "large-v3-q5_0" -> "Large v3 · Q5"; "large-v3" -> "Large v3 · Full"
                "auto" -> "Auto detect"; "en" -> "English"; "hi" -> "Hindi"
                "word" -> "Word-level"; else -> "Sentence-level"
            })
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(when (option) {
                        "tiny-q5_1" -> "Tiny · Q5"; "base-q5_1" -> "Base · Q5"
                        "small-q5_1" -> "Small · Q5"; "medium-q5_0" -> "Medium · Q5"
                        "large-v3-turbo-q5_0" -> "Large v3 Turbo · Q5"; "large-v3-q5_0" -> "Large v3 · Q5"; "large-v3" -> "Large v3 · Full"
                        "auto" -> "Auto detect"
                        "en" -> "English"; "hi" -> "Hindi"; "word" -> "Word-level"
                        else -> "Sentence-level"
                    }) },
                    onClick = { onSelect(option); expanded = false }
                )
            }
        }
    }
}

private fun saveText(context: android.content.Context, uri: Uri, content: String) {
    try { context.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) } } catch (_: Exception) {}
}

private fun toSrt(text: String): String {
    val entries = text.lineSequence().mapNotNull { line ->
        val match = Regex(
            """^\[(\d{2}):(\d{2}):(\d{2})\.(\d{2})\s*-->\s*(\d{2}):(\d{2}):(\d{2})\.(\d{2})\]\s*(.*)$"""
        ).matchEntire(line.trim()) ?: return@mapNotNull null
        fun timeAt(offset: Int): Int {
            val h = match.groupValues[offset].toInt()
            val m = match.groupValues[offset + 1].toInt()
            val s = match.groupValues[offset + 2].toInt()
            val cs = match.groupValues[offset + 3].toInt()
            return (((h * 60 + m) * 60 + s) * 100) + cs
        }
        Triple(timeAt(1), timeAt(5), match.groupValues[9])
    }.toList()

    return entries.mapIndexed { index, entry ->
        val end = maxOf(entry.first + 1, entry.second)
        "${index + 1}\n${srtTime(entry.first)} --> ${srtTime(end)}\n${entry.third.trim()}\n"
    }.joinToString("\n")
}

private fun srtTime(centiseconds: Int): String {
    val milliseconds = centiseconds * 10
    val h = milliseconds / 3_600_000
    val m = (milliseconds % 3_600_000) / 60_000
    val s = (milliseconds % 60_000) / 1_000
    val ms = milliseconds % 1_000
    return String.format(Locale.US, "%02d:%02d:%02d,%03d", h, m, s, ms)
}
