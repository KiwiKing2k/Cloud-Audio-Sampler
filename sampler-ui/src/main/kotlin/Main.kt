import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioSystem
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

// ── Piano key definitions ─────────────────────────────────────────────────────

data class PianoKey(
    val label: String,
    val semitone: Int,       // relative to C4 (0 = C4, 12 = C5, etc.)
    val isBlack: Boolean,
    val keyChar: Char?,
    val whiteIndex: Int = -1,    // white keys: index in the white-key row
    val blackOffset: Float = 0f  // black keys: fractional white-key position for x offset
)

val WHITE_KEYS = listOf(
    PianoKey("C4",  0, false, 'a', 0),
    PianoKey("D4",  2, false, 's', 1),
    PianoKey("E4",  4, false, 'd', 2),
    PianoKey("F4",  5, false, 'f', 3),
    PianoKey("G4",  7, false, 'g', 4),
    PianoKey("A4",  9, false, 'h', 5),
    PianoKey("B4", 11, false, 'j', 6),
    PianoKey("C5", 12, false, 'k', 7),
    PianoKey("D5", 14, false, 'l', 8),
    PianoKey("E5", 16, false, null, 9),
    PianoKey("F5", 17, false, null, 10),
    PianoKey("G5", 19, false, null, 11),
    PianoKey("A5", 21, false, null, 12),
    PianoKey("B5", 23, false, null, 13),
)

val BLACK_KEYS = listOf(
    PianoKey("C#4",  1, true, 'w', blackOffset = 0.67f),
    PianoKey("D#4",  3, true, 'e', blackOffset = 1.67f),
    PianoKey("F#4",  6, true, 't', blackOffset = 3.67f),
    PianoKey("G#4",  8, true, 'y', blackOffset = 4.67f),
    PianoKey("A#4", 10, true, 'u', blackOffset = 5.67f),
    PianoKey("C#5", 13, true, 'o', blackOffset = 7.67f),
    PianoKey("D#5", 15, true, 'p', blackOffset = 8.67f),
    PianoKey("F#5", 18, true, null, blackOffset = 10.67f),
    PianoKey("G#5", 20, true, null, blackOffset = 11.67f),
    PianoKey("A#5", 22, true, null, blackOffset = 12.67f),
)

val CHAR_TO_KEY: Map<Char, PianoKey> =
    (WHITE_KEYS + BLACK_KEYS).filter { it.keyChar != null }.associateBy { it.keyChar!! }

// ── HTTP + Audio ──────────────────────────────────────────────────────────────

val httpClient = HttpClient(CIO)

suspend fun processAndPlay(
    file: File,
    semitones: Int,
    lowpass: Float,
    highpass: Float,
    reverb: Float,
    gain: Float,
) = withContext(Dispatchers.IO) {
    val response: io.ktor.client.statement.HttpResponse = httpClient.post("http://127.0.0.1:8000/process") {
        parameter("pitch_shift", semitones)
        parameter("lowpass_cutoff", lowpass)
        parameter("highpass_cutoff", highpass)
        parameter("reverb_room", reverb)
        parameter("gain_db", gain)
        setBody(MultiPartFormDataContent(formData {
            append("file", file.readBytes(), Headers.build {
                append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"${file.name}\"")
                append(HttpHeaders.ContentType, "audio/mpeg")
            })
        }))
    }
    playWavBytes(response.readRawBytes())
}

fun playWavBytes(bytes: ByteArray) {
    Thread {
        try {
            val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
            val clip = AudioSystem.getClip()
            clip.open(stream)
            clip.start()
        } catch (e: Exception) {
            System.err.println("Playback error: ${e.message}")
        }
    }.start()
}

// ── Piano keyboard component ──────────────────────────────────────────────────

@Composable
fun PianoKeyboard(pressedSemitones: Set<Int>, onPress: (Int) -> Unit) {
    val whiteW = 48.dp
    val whiteH = 150.dp
    val blackW = 30.dp
    val blackH = 95.dp

    Box {
        // White keys
        Row {
            WHITE_KEYS.forEach { key ->
                val pressed = key.semitone in pressedSemitones
                Box(
                    modifier = Modifier
                        .width(whiteW)
                        .height(whiteH)
                        .padding(horizontal = 1.dp)
                        .background(if (pressed) Color(0xFF90CAF9) else Color.White)
                        .border(1.dp, Color(0xFF9E9E9E))
                        .clickable { onPress(key.semitone) },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    if (key.keyChar != null) {
                        Text(
                            key.keyChar.uppercaseChar().toString(),
                            fontSize = 10.sp,
                            color = Color(0xFF757575),
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
            }
        }

        // Black keys (overlaid with absolute offset)
        BLACK_KEYS.forEach { key ->
            val pressed = key.semitone in pressedSemitones
            Box(
                modifier = Modifier
                    .offset(x = whiteW * key.blackOffset - blackW / 2)
                    .width(blackW)
                    .height(blackH)
                    .background(if (pressed) Color(0xFF1565C0) else Color(0xFF212121))
                    .clickable { onPress(key.semitone) }
            )
        }
    }
}

// ── Slider row ────────────────────────────────────────────────────────────────

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Text(label, modifier = Modifier.width(170.dp), color = Color.White, fontSize = 13.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF42A5F5),
                activeTrackColor = Color(0xFF1E88E5)
            )
        )
        Text(
            "%.1f".format(value),
            modifier = Modifier.width(52.dp),
            color = Color(0xFF90A4AE),
            fontSize = 12.sp
        )
    }
}

// ── Main app ──────────────────────────────────────────────────────────────────

@Composable
fun App() {
    var sampleFile by remember { mutableStateOf<File?>(null) }
    var pitchOffset by remember { mutableStateOf(0f) }
    var lowpass    by remember { mutableStateOf(20000f) }
    var highpass   by remember { mutableStateOf(20f) }
    var reverb     by remember { mutableStateOf(0f) }
    var gain       by remember { mutableStateOf(0f) }
    var pressedSemitones by remember { mutableStateOf(setOf<Int>()) }
    var status by remember { mutableStateOf("No sample loaded — click \"Load Sample\" to begin") }

    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    fun triggerKey(semitone: Int) {
        val file = sampleFile ?: run { status = "Load a sample first!"; return }
        val totalPitch = semitone + pitchOffset.toInt()
        status = "Playing note (semitone $totalPitch)…"
        scope.launch {
            try {
                processAndPlay(file, totalPitch, lowpass, highpass, reverb, gain)
                status = "Ready — sample: ${file.name}"
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            }
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    MaterialTheme(colors = darkColors()) {
        Surface(
            color = Color(0xFF0F0F1A),
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    val char = event.utf16CodePoint.toChar().lowercaseChar()
                    val key = CHAR_TO_KEY[char] ?: return@onKeyEvent false
                    when (event.type) {
                        KeyEventType.KeyDown -> {
                            if (key.semitone !in pressedSemitones) {
                                pressedSemitones = pressedSemitones + key.semitone
                                triggerKey(key.semitone)
                            }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            pressedSemitones = pressedSemitones - key.semitone
                            true
                        }
                        else -> false
                    }
                }
        ) {
            Column(modifier = Modifier.padding(28.dp).verticalScroll(rememberScrollState())) {

                // Header
                Text("Cloud Sampler", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text("DSP · Microservices · Kotlin + Python", fontSize = 11.sp, color = Color(0xFF546E7A))
                Spacer(Modifier.height(20.dp))

                // Load sample row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            Thread {
                                val chooser = JFileChooser()
                                chooser.fileFilter = FileNameExtensionFilter("Audio files", "wav", "mp3", "flac")
                                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                                    sampleFile = chooser.selectedFile
                                    status = "Ready — sample: ${chooser.selectedFile.name}"
                                }
                            }.start()
                        },
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF1565C0))
                    ) { Text("Load Sample", color = Color.White) }
                    Spacer(Modifier.width(16.dp))
                    Text(status, color = Color(0xFF90CAF9), fontSize = 13.sp)
                }

                Spacer(Modifier.height(24.dp))

                // Piano keyboard
                Text(
                    "Piano  (A S D F G H J K L = white keys  |  W E T Y U O P = black keys)",
                    fontSize = 11.sp, color = Color(0xFF546E7A)
                )
                Spacer(Modifier.height(8.dp))
                PianoKeyboard(pressedSemitones = pressedSemitones, onPress = ::triggerKey)

                Spacer(Modifier.height(32.dp))

                // Sliders
                Divider(color = Color(0xFF1E2A3A))
                Spacer(Modifier.height(16.dp))
                LabeledSlider("Pitch Offset (semitones)", pitchOffset, -12f..12f) { pitchOffset = it }
                LabeledSlider("Low-pass cutoff (Hz)",    lowpass,    200f..20000f) { lowpass = it }
                LabeledSlider("High-pass cutoff (Hz)",   highpass,   20f..5000f)   { highpass = it }
                LabeledSlider("Reverb (room size)",       reverb,    0f..1f)        { reverb = it }
                LabeledSlider("Gain (dB)",                gain,      -12f..12f)    { gain = it }
            }
        }
    }
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Cloud Sampler") {
        App()
    }
}
