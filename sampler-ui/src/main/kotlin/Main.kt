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
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import java.io.*
import java.nio.file.Files
import kotlin.math.cos
import kotlin.math.sin
import javax.sound.sampled.AudioSystem
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

val httpClient = HttpClient(CIO)

data class FXParams(
    val fuzz: Float = 0f,
    val eq: List<Float> = List(8) { 0f },
    val compThreshold: Float = 0f, val compRatio: Float = 1f,
    val delayFeed: Float = 0f, val delayTime: Float = 0.5f, val delayLevel: Float = 0f,
    val reverbRoom: Float = 0.2f, val reverbWet: Float = 0f,
    val clip: Float = 0f, val master: Float = 0f
)

suspend fun fetchProcessedAudio(file: File, p: FXParams): ByteArray = withContext(Dispatchers.IO) {
    val response: HttpResponse = httpClient.post("http://127.0.0.1:8000/process") {
        parameter("fuzz_drive", p.fuzz)
        p.eq.forEachIndexed { i, v -> parameter("eq_${listOf(40,80,160,320,640,1280,2560,5120)[i]}", v) }
        parameter("comp_threshold", p.compThreshold); parameter("comp_ratio", p.compRatio)
        parameter("delay_feedback", p.delayFeed); parameter("delay_time", p.delayTime); parameter("delay_level", p.delayLevel)
        parameter("reverb_room", p.reverbRoom); parameter("reverb_wet", p.reverbWet)
        parameter("clip_threshold", p.clip); parameter("master_gain", p.master)
        setBody(MultiPartFormDataContent(formData {
            append("file", file.readBytes(), Headers.build {
                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${file.name}\"")
                append(HttpHeaders.ContentType, "audio/wav")
            })
        }))
    }
    response.readRawBytes()
}

fun playWavBytes(bytes: ByteArray) {
    Thread {
        try {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
            val clip = AudioSystem.getClip()
            clip.open(stream); clip.start()
        } catch (e: Exception) { e.printStackTrace() }
    }.start()
}

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
            val sensitivity = 0.003f
            dragAccumulator = (dragAccumulator - dragAmount.y * sensitivity).coerceIn(0f, 1f)
            onValueChange(range.start + dragAccumulator * (range.endInclusive - range.start))
        }
    }) {
        val center = Offset(size.width / 2, size.height / 2)
        val radius = size.minDimension / 2.2f
        val strokeWidth = 4.dp.toPx()

        drawArc(
            color = Color(0xFF2A2A3A), startAngle = 135f, sweepAngle = 270f,
            useCenter = false, style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
        drawArc(
            color = color, startAngle = 135f, sweepAngle = 270f * dragAccumulator,
            useCenter = false, style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        val angleInRadians = (135f + 270f * dragAccumulator) * (Math.PI / 180f).toFloat()
        val indicatorPos = Offset(
            x = center.x + (radius - 4.dp.toPx()) * cos(angleInRadians),
            y = center.y + (radius - 4.dp.toPx()) * sin(angleInRadians)
        )
        drawCircle(color = Color.White, radius = 2.dp.toPx(), center = indicatorPos)
    }
}

@Composable
fun LabeledKnob(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(4.dp)) {
        Text(label, color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        Knob(value = value, range = range, onValueChange = onValueChange)
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

@Composable
fun App() {
    var sampleFile by remember { mutableStateOf<File?>(null) }
    var p by remember { mutableStateOf(FXParams()) }
    var status by remember { mutableStateOf("Ready to process") }
    val scope = rememberCoroutineScope()

    MaterialTheme(colors = darkColors()) {
        Surface(color = Color(0xFF0A0A0F), modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("BASS MONSTER FX", fontSize = 22.sp, fontWeight = FontWeight.Black, color = Color.White)

                Button(modifier = Modifier.padding(vertical = 12.dp), onClick = {
                    val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("Audio", "wav", "mp3") }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) sampleFile = chooser.selectedFile
                }) { Text("LOAD SAMPLE", fontSize = 11.sp) }

                Section("8-BAND GRAPHIC EQ") {
                    val freqs = listOf("40Hz", "80Hz", "160Hz", "320Hz", "640Hz", "1.2k", "2.5k", "5k")
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        freqs.forEachIndexed { i, label ->
                            LabeledKnob(label, p.eq[i], -15f..15f) { v ->
                                val newEq = p.eq.toMutableList(); newEq[i] = v; p = p.copy(eq = newEq)
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f).padding(end = 4.dp)) {
                        Section("DYNAMICS & FUZZ") {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                LabeledKnob("Fuzz", p.fuzz, 0f..30f) { p = p.copy(fuzz = it) }
                                LabeledKnob("Comp Th.", p.compThreshold, -40f..0f) { p = p.copy(compThreshold = it) }
                                LabeledKnob("Ratio", p.compRatio, 1f..20f) { p = p.copy(compRatio = it) }
                            }
                        }
                    }
                    Column(modifier = Modifier.weight(1.2f).padding(start = 4.dp)) {
                        Section("ECHO & AMBIENCE") {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                // Grouped Delay Controls
                                LabeledKnob("Dly Time", p.delayTime, 0.1f..1.5f) { p = p.copy(delayTime = it) }
                                LabeledKnob("Dly Feed", p.delayFeed, 0f..0.9f) { p = p.copy(delayFeed = it) }
                                LabeledKnob("Dly Mix", p.delayLevel, 0f..1f) { p = p.copy(delayLevel = it) }
                                Divider(modifier = Modifier.width(1.dp).height(40.dp).align(Alignment.CenterVertically), color = Color.DarkGray)
                                // Grouped Reverb Controls
                                LabeledKnob("Rev Wet", p.reverbWet, 0f..1f) { p = p.copy(reverbWet = it) }
                                LabeledKnob("Rev Room", p.reverbRoom, 0.1f..2.0f) { p = p.copy(reverbRoom = it) }
                            }
                        }
                    }
                }

                Section("MASTER OUTPUT") {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        LabeledKnob("Hard Clip", p.clip, -20f..0f) { p = p.copy(clip = it) }
                        LabeledKnob("Out Gain", p.master, -20f..20f) { p = p.copy(master = it) }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(modifier = Modifier.weight(1f).height(45.dp), onClick = {
                        val file = sampleFile ?: return@Button
                        scope.launch { try { playWavBytes(fetchProcessedAudio(file, p)) } catch (e: Exception) { status = "Engine error: ${e.message}" } }
                    }) { Text("PREVIEW") }

                    Button(modifier = Modifier.weight(1f).height(45.dp), colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1B5E20)), onClick = {
                        val file = sampleFile ?: return@Button
                        val chooser = JFileChooser().apply { selectedFile = File("processed_${file.nameWithoutExtension}.wav") }
                        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                            scope.launch { try { Files.write(chooser.selectedFile.toPath(), fetchProcessedAudio(file, p)) } catch (e: Exception) {}}
                        }
                    }) { Text("EXPORT WAV") }
                }
                Text(status, color = Color.DarkGray, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

fun main() = application { Window(onCloseRequest = ::exitApplication, title = "Bass Monster FX", state = rememberWindowState(width = 850.dp, height = 750.dp)) { App() } }