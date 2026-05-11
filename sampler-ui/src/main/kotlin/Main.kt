import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.io.File
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

// --- MODELE DE DATE (Sincronizate cu Backend-ul Python) ---

@Serializable
data class FXParams(
    val name: String = "Untitled",
    val pitch: Float = 0f,
    @SerialName("lp_filter") val lpFilter: Float = 20000f,
    val chorus: Float = 0f,
    val fuzz: Float = 0f,
    val eq: List<Float> = List(8) { 0f },
    @SerialName("comp_threshold") val compThreshold: Float = 0f,
    @SerialName("comp_ratio") val compRatio: Float = 1f,
    @SerialName("delay_feed") val delayFeed: Float = 0f,
    @SerialName("delay_time") val delayTime: Float = 0.5f,
    @SerialName("delay_level") val delayLevel: Float = 0f,
    @SerialName("reverb_room") val reverbRoom: Float = 0.2f,
    @SerialName("reverb_wet") val reverbWet: Float = 0f,
    val clip: Float = 0f,
    val master: Float = 0f
)

@Serializable
data class ProcessResponse(
    val status: String,
    @SerialName("file_url") val fileUrl: String
)

// --- CONFIGURARE CLIENT HTTP ---

val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true // Trimite toate câmpurile pentru a evita eroarea 422
        })
    }
}

// --- LOGICĂ DE PROCESARE ȘI DOWNLOAD (Arhitectură Distribuită) ---

suspend fun processAndDownloadAudio(file: File, p: FXParams): ByteArray = withContext(Dispatchers.IO) {
    // 1. Trimitem fișierul pentru procesare asincronă [cite: 132]
    val response: HttpResponse = httpClient.post("http://127.0.0.1:8000/process") {
        parameter("pitch_shift", p.pitch)
        parameter("lp_cutoff", p.lpFilter)
        parameter("chorus_depth", p.chorus)
        parameter("fuzz_drive", p.fuzz)
        p.eq.forEachIndexed { i, v -> parameter("eq_${listOf(40, 80, 160, 320, 640, 1280, 2560, 5120)[i]}", v) }
        parameter("comp_threshold", p.compThreshold)
        parameter("comp_ratio", p.compRatio)
        parameter("delay_feedback", p.delayFeed)
        parameter("delay_time", p.delayTime)
        parameter("delay_level", p.delayLevel)
        parameter("reverb_room", p.reverbRoom)
        parameter("reverb_wet", p.reverbWet)
        parameter("clip_threshold", p.clip)
        parameter("master_gain", p.master)

        setBody(MultiPartFormDataContent(formData {
            append("file", file.readBytes(), Headers.build {
                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${file.name}\"")
                append(HttpHeaders.ContentType, "audio/wav")
            })
        }))
    }

    if (!response.status.isSuccess()) {
        val errorBody = response.bodyAsText()
        throw Exception("Server Error ${response.status}: $errorBody")
    }

    // 2. Extragem URL-ul din Object Storage (MinIO)
    val result: ProcessResponse = response.body()

    // Translatăm adresa din rețeaua Docker în Localhost pentru Windows
    val accessibleUrl = result.fileUrl.replace("storage", "127.0.0.1")

    // 3. Descărcăm fișierul audio final
    val audioBytes: ByteArray = httpClient.get(accessibleUrl).body()

    // VALIDARE CRITICĂ: Verificăm dacă fișierul este un WAV valid (începe cu RIFF)
    val header = if (audioBytes.size > 4) String(audioBytes.take(4).toByteArray()) else ""
    if (header != "RIFF") {
        val errorContent = String(audioBytes.take(100).toByteArray())
        throw Exception("MinIO Access Error! Conținutul primit nu este audio (probabil Access Denied XML): $errorContent")
    }

    return@withContext audioBytes
}

// --- REDARE AUDIO (Corectată) ---

var currentClip: Clip? = null

fun playWavBytes(bytes: ByteArray, onProgress: (Float, Float) -> Unit, onComplete: () -> Unit) {
    Thread {
        try {
            currentClip?.stop()
            currentClip?.close()

            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
            val clip = AudioSystem.getClip()
            currentClip = clip
            clip.open(stream)

            val duration = clip.microsecondLength / 1_000_000f
            onProgress(0f, duration)
            clip.start()

            while (clip.isActive || clip.isRunning) {
                onProgress(clip.microsecondPosition / 1_000_000f, duration)
                Thread.sleep(50)
            }
            onProgress(duration, duration)
            onComplete()
        } catch (e: Exception) {
            System.err.println("Audio Playback Error: ${e.message}")
            onComplete()
        }
    }.start()
}

// --- COMPONENTE UI (Minimalism Funcțional) [cite: 176, 180] ---

@Composable
fun Knob(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier.size(54.dp),
    color: Color = Color(0xFF42A5F5)
) {
    val initialNormalized = (value - range.start) / (range.endInclusive - range.start)
    var dragAccumulator by remember { mutableStateOf(initialNormalized) }

    LaunchedEffect(value) {
        dragAccumulator = (value - range.start) / (range.endInclusive - range.start)
    }

    Canvas(modifier = modifier.pointerInput(Unit) {
        detectDragGestures { change, dragAmount ->
            change.consume()
            val sensitivity = 0.003f // Atenuare pentru precizie industrială [cite: 190]
            dragAccumulator = (dragAccumulator - dragAmount.y * sensitivity).coerceIn(0f, 1f)
            onValueChange(range.start + dragAccumulator * (range.endInclusive - range.start))
        }
    }) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2.2f
        val strokeWidth = 4.dp.toPx()

        drawArc(
            color = Color(0xFF2A2A3A),
            startAngle = 135f, sweepAngle = 270f, useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        drawArc(
            color = color,
            startAngle = 135f, sweepAngle = 270f * dragAccumulator, useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        val angleRad = (135f + 270f * dragAccumulator) * (Math.PI / 180f).toFloat()
        drawCircle(
            color = Color.White, radius = 2.dp.toPx(),
            center = Offset(center.x + (radius - 4.dp.toPx()) * cos(angleRad), center.y + (radius - 4.dp.toPx()) * sin(angleRad))
        )
    }
}

@Composable
fun LabeledKnob(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
        Text(label, color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Knob(value, range, onValueChange)
        Text("%.1f".format(value), color = Color(0xFF42A5F5), fontSize = 10.sp)
    }
}

@Composable
fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(vertical = 6.dp).background(Color(0xFF16161E), MaterialTheme.shapes.medium).padding(12.dp)) {
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

// --- APLICAȚIA PRINCIPALĂ ---

@Composable
fun App() {
    var sampleFile by remember { mutableStateOf<File?>(null) }
    var p by remember { mutableStateOf(FXParams()) }
    var status by remember { mutableStateOf("Ready - Storage Integrated") }
    var presetInputName by remember { mutableStateOf("New Preset") }
    var cloudPresets by remember { mutableStateOf<List<FXParams>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPos by remember { mutableStateOf(0f) }
    var totalPos by remember { mutableStateOf(0f) }

    val scope = rememberCoroutineScope()

    MaterialTheme(colors = darkColors()) {
        Surface(color = Color(0xFF0A0A0F), modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Cloud-Based Sound FX", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)
                    Button(onClick = {
                        val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("Audio", "wav", "mp3") }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            sampleFile = chooser.selectedFile
                            status = "Loaded: ${chooser.selectedFile.name}"
                        }
                    }) { Text("LOAD SAMPLE", fontSize = 11.sp) }
                }

                Spacer(Modifier.height(16.dp))

                Section("CLOUD PRESET MANAGEMENT") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = presetInputName,
                            onValueChange = { presetInputName = it },
                            label = { Text("Preset Name", fontSize = 10.sp) },
                            modifier = Modifier.weight(1f).height(54.dp),
                            textStyle = TextStyle(fontSize = 12.sp, color = Color.White)
                        )
                        Button(modifier = Modifier.height(54.dp), onClick = {
                            scope.launch {
                                try {
                                    val response: HttpResponse = httpClient.post("http://127.0.0.1:8000/presets/save") {
                                        contentType(ContentType.Application.Json)
                                        setBody(p.copy(name = presetInputName))
                                    }
                                    status = if (response.status.isSuccess()) "Preset saved to MongoDB" else "Save failed: ${response.status}"
                                } catch (e: Exception) { status = "Error: ${e.message}" }
                            }
                        }) { Text("SAVE", fontSize = 10.sp) }

                        Button(modifier = Modifier.height(54.dp), colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF303F9F)), onClick = {
                            scope.launch {
                                try {
                                    cloudPresets = httpClient.get("http://127.0.0.1:8000/presets").body()
                                    status = "Synced ${cloudPresets.size} presets"
                                } catch (e: Exception) { status = "Sync error: ${e.message}" }
                            }
                        }) { Text("SYNC", fontSize = 10.sp) }
                    }

                    if (cloudPresets.isNotEmpty()) {
                        Row(modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                            cloudPresets.forEach { remote ->
                                Card(modifier = Modifier.padding(end = 8.dp).clickable { p = remote; presetInputName = remote.name }, backgroundColor = Color(0xFF2A2A3A)) {
                                    Text(remote.name, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 11.sp, color = Color.Cyan)
                                }
                            }
                        }
                    }
                }

                Section("8-BAND GRAPHIC EQ") {
                    val f = listOf("40Hz", "80Hz", "160Hz", "320Hz", "640Hz", "1.2k", "2.5k", "5k")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        f.forEachIndexed { i, label ->
                            LabeledKnob(label, p.eq[i], -15f..15f) { v ->
                                val newEq = p.eq.toMutableList(); newEq[i] = v; p = p.copy(eq = newEq)
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                        Section("PITCH, FILTER & FUZZ") {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                LabeledKnob("Pitch", p.pitch, -12f..12f) { p = p.copy(pitch = it) }
                                LabeledKnob("LP Filter", p.lpFilter, 200f..20000f) { p = p.copy(lpFilter = it) }
                                LabeledKnob("Fuzz", p.fuzz, 0f..30f) { p = p.copy(fuzz = it) }
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1.2f).padding(start = 4.dp)) {
                        Section("AMBIENCE & CHORUS") {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                LabeledKnob("Chorus", p.chorus, 0f..1f) { p = p.copy(chorus = it) }
                                LabeledKnob("Dly Time", p.delayTime, 0.1f..1.5f) { p = p.copy(delayTime = it) }
                                LabeledKnob("Dly Mix", p.delayLevel, 0f..1f) { p = p.copy(delayLevel = it) }
                                Divider(modifier = Modifier.width(1.dp).height(40.dp).align(Alignment.CenterVertically), color = Color.DarkGray)
                                LabeledKnob("Rev Wet", p.reverbWet, 0f..1f) { p = p.copy(reverbWet = it) }
                            }
                        }
                    }
                }

                Section("DYNAMICS & MASTER") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        LabeledKnob("Thresh", p.compThreshold, -40f..0f) { p = p.copy(compThreshold = it) }
                        LabeledKnob("Hard Clip", p.clip, -20f..0f) { p = p.copy(clip = it) }
                        LabeledKnob("Out Gain", p.master, -20f..20f) { p = p.copy(master = it) }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(modifier = Modifier.weight(1f).height(45.dp), onClick = {
                        val file = sampleFile ?: return@Button
                        status = "Cloud Processing (S3 Integration)..."
                        scope.launch {
                            try {
                                val bytes = processAndDownloadAudio(file, p)
                                status = "Playing Wet Data from MinIO"
                                playWavBytes(bytes, onProgress = { cur, tot -> currentPos = cur; totalPos = tot; isPlaying = true }, onComplete = { isPlaying = false; status = "Playback finished" })
                            } catch (e: Exception) { status = "Engine error: ${e.message}"; isPlaying = false }
                        }
                    }) { Text("PREVIEW / PROCESS") }

                    Button(modifier = Modifier.weight(1f).height(45.dp), colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1B5E20)), onClick = {
                        val file = sampleFile ?: return@Button
                        val chooser = JFileChooser().apply { selectedFile = File("processed_${file.nameWithoutExtension}.wav") }
                        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                            scope.launch {
                                try {
                                    status = "Exporting from Cloud..."
                                    Files.write(chooser.selectedFile.toPath(), processAndDownloadAudio(file, p))
                                    status = "Saved: ${chooser.selectedFile.name}"
                                } catch (e: Exception) { status = "Save error: ${e.message}" }
                            }
                        }
                    }) { Text("EXPORT WAV") }
                }

                Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(status, color = Color.Gray, fontSize = 12.sp)
                        if (isPlaying || totalPos > 0) {
                            Text("Playing: %.2fs / %.2fs".format(currentPos, totalPos), color = Color(0xFF42A5F5), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (isPlaying) {
                        LinearProgressIndicator(progress = if (totalPos > 0) currentPos / totalPos else 0f, modifier = Modifier.weight(1f).height(8.dp), color = Color(0xFF42A5F5), backgroundColor = Color(0xFF2A2A3A))
                    }
                }
            }
        }
    }
}

fun main() = application { Window(onCloseRequest = ::exitApplication, title = "Cloud-Based Sound FX", state = rememberWindowState(width = 850.dp, height = 820.dp)) { App() } }