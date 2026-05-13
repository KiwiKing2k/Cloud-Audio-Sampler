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

// --- MODELE DE DATE ---

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

val httpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            encodeDefaults = true
        })
    }
}

// --- LOGICA DE RETEA MODIFICATA ---

suspend fun processAndDownloadAudio(file: File, p: FXParams): ByteArray = withContext(Dispatchers.IO) {
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
        throw Exception("HTTP ${response.status}: ${response.bodyAsText()}")
    }

    val result: ProcessResponse = response.body()

    // REPARARE URL: Inlocuim dinamic orice hostname intre http:// si :9000 cu 127.0.0.1
    val accessibleUrl = result.fileUrl.replace(Regex("http://[^:]+:9000"), "http://127.0.0.1:9000")

    println("DEBUG: Downloading from: $accessibleUrl")

    val audioBytes: ByteArray = httpClient.get(accessibleUrl).body()

    val header = if (audioBytes.size > 4) String(audioBytes.take(4).toByteArray()) else ""
    if (header != "RIFF") {
        throw Exception("MinIO Error: Fisier invalid de la $accessibleUrl")
    }

    return@withContext audioBytes
}

// --- RESTUL COMPONENTELOR (AUDIO, UI, APP) ---

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
            e.printStackTrace()
            onComplete()
        }
    }.start()
}

@Composable
fun Knob(value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier.size(54.dp), color: Color = Color(0xFF42A5F5)) {
    val initialNormalized = (value - range.start) / (range.endInclusive - range.start)
    var dragAccumulator by remember { mutableStateOf(initialNormalized) }
    LaunchedEffect(value) { dragAccumulator = (value - range.start) / (range.endInclusive - range.start) }
    Canvas(modifier = modifier.pointerInput(Unit) {
        detectDragGestures { change, dragAmount ->
            change.consume()
            dragAccumulator = (dragAccumulator - dragAmount.y * 0.003f).coerceIn(0f, 1f)
            onValueChange(range.start + dragAccumulator * (range.endInclusive - range.start))
        }
    }) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2.2f
        drawArc(Color(0xFF2A2A3A), 135f, 270f, false, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
        drawArc(color, 135f, 270f * dragAccumulator, false, style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round))
        val angleRad = (135f + 270f * dragAccumulator) * (Math.PI / 180f).toFloat()
        drawCircle(Color.White, 2.dp.toPx(), center = Offset(center.x + (radius - 4.dp.toPx()) * cos(angleRad), center.y + (radius - 4.dp.toPx()) * sin(angleRad)))
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
        Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp); Spacer(Modifier.height(8.dp)); content()
    }
}

@Composable
fun App() {
    var sampleFile by remember { mutableStateOf<File?>(null) }
    var p by remember { mutableStateOf(FXParams()) }
    var status by remember { mutableStateOf("Ready") }
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
                        val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("Audio", "wav") }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) { sampleFile = chooser.selectedFile; status = "Loaded: ${sampleFile?.name}" }
                    }) { Text("LOAD SAMPLE", fontSize = 11.sp) }
                }

                Spacer(Modifier.height(16.dp))

                Section("CLOUD PRESET MANAGEMENT") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(value = presetInputName, onValueChange = { presetInputName = it }, label = { Text("Name") }, modifier = Modifier.weight(1f).height(54.dp), textStyle = TextStyle(fontSize = 12.sp, color = Color.White))
                        Button(modifier = Modifier.height(54.dp), onClick = {
                            scope.launch {
                                try {
                                    val resp: HttpResponse = httpClient.post("http://127.0.0.1:8000/presets/save") {
                                        contentType(ContentType.Application.Json)
                                        setBody(p.copy(name = presetInputName))
                                    }
                                    status = "Save: ${resp.status}"
                                } catch (e: Exception) { e.printStackTrace(); status = "Save Error: ${e.toString()}" }
                            }
                        }) { Text("SAVE") }
                        Button(modifier = Modifier.height(54.dp), onClick = {
                            scope.launch {
                                try {
                                    cloudPresets = httpClient.get("http://127.0.0.1:8000/presets").body()
                                    status = "Synced ${cloudPresets.size}"
                                } catch (e: Exception) { e.printStackTrace(); status = "Sync Error: ${e.toString()}" }
                            }
                        }) { Text("SYNC") }
                    }
                    Row(modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState())) {
                        cloudPresets.forEach { remote ->
                            Card(modifier = Modifier.padding(end = 8.dp).clickable { p = remote; presetInputName = remote.name }, backgroundColor = Color(0xFF2A2A3A)) {
                                Text(remote.name, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 11.sp, color = Color.Cyan)
                            }
                        }
                    }
                }

                Section("8-BAND EQ") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        listOf("40", "80", "160", "320", "640", "1.2k", "2.5k", "5k").forEachIndexed { i, l ->
                            LabeledKnob(l, p.eq[i], -15f..15f) { v -> val n = p.eq.toMutableList(); n[i] = v; p = p.copy(eq = n) }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                        Section("CORE FX") {
                            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                                LabeledKnob("Pitch", p.pitch, -12f..12f) { p = p.copy(pitch = it) }
                                LabeledKnob("LPF", p.lpFilter, 200f..20000f) { p = p.copy(lpFilter = it) }
                                LabeledKnob("Fuzz", p.fuzz, 0f..30f) { p = p.copy(fuzz = it) }
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1.2f).padding(start = 4.dp)) {
                        Section("TIME & SPACE") {
                            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                                LabeledKnob("Chorus", p.chorus, 0f..1f) { p = p.copy(chorus = it) }
                                LabeledKnob("Time", p.delayTime, 0.1f..1.5f) { p = p.copy(delayTime = it) }
                                LabeledKnob("Wet", p.reverbWet, 0f..1f) { p = p.copy(reverbWet = it) }
                            }
                        }
                    }
                }

                Section("DYNAMICS") {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        LabeledKnob("Thresh", p.compThreshold, -40f..0f) { p = p.copy(compThreshold = it) }
                        LabeledKnob("Clip", p.clip, -20f..0f) { p = p.copy(clip = it) }
                        LabeledKnob("Gain", p.master, -20f..20f) { p = p.copy(master = it) }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(modifier = Modifier.weight(1f).height(45.dp), onClick = {
                        val file = sampleFile ?: return@Button
                        scope.launch {
                            try {
                                status = "Processing..."
                                val bytes = processAndDownloadAudio(file, p)
                                playWavBytes(bytes, { c, t -> currentPos = c; totalPos = t; isPlaying = true }, { isPlaying = false; status = "Done" })
                            } catch (e: Exception) { e.printStackTrace(); status = "Engine Error: ${e.toString().take(150)}"; isPlaying = false }
                        }
                    }) { Text("PREVIEW") }

                    Button(modifier = Modifier.weight(1f).height(45.dp), colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1B5E20)), onClick = {
                        val file = sampleFile ?: return@Button
                        val chooser = JFileChooser().apply { selectedFile = File("output.wav") }
                        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                            scope.launch {
                                try {
                                    Files.write(chooser.selectedFile.toPath(), processAndDownloadAudio(file, p))
                                    status = "Exported"
                                } catch (e: Exception) { e.printStackTrace(); status = "Export Error: ${e.toString()}" }
                            }
                        }
                    }) { Text("EXPORT") }
                }

                Text(status, color = Color.Gray, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                if (isPlaying) LinearProgressIndicator(progress = currentPos / totalPos, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}

fun main() = application { Window(onCloseRequest = ::exitApplication, title = "FX Cloud", state = rememberWindowState(width = 850.dp, height = 820.dp)) { App() } }