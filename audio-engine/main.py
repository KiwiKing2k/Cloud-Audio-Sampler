import io
import numpy as np
import soundfile as sf
from fastapi import FastAPI, File, UploadFile, HTTPException
from fastapi.responses import StreamingResponse
from pedalboard import Pedalboard, PitchShift, LowpassFilter, HighpassFilter, Reverb, Gain
from pedalboard.io import AudioFile

app = FastAPI(title="Cloud Sampler Audio Engine")


@app.get("/health")
def health():
    return {"status": "ok"}


@app.post("/process")
async def process_audio(
    file: UploadFile = File(...),
    pitch_shift: float = 0.0,       # semitones, e.g. 12 = one octave up
    lowpass_cutoff: float = 20000.0, # Hz (20000 = no filtering)
    highpass_cutoff: float = 20.0,   # Hz (20 = no filtering)
    reverb_room: float = 0.0,        # 0.0 – 1.0
    gain_db: float = 0.0,            # dB
):
    raw = await file.read()
    if not raw:
        raise HTTPException(status_code=400, detail="Empty file received")

    # Decode incoming audio (WAV, MP3, FLAC, OGG, …) via pedalboard
    with AudioFile(io.BytesIO(raw)) as f:
        sample_rate = f.samplerate
        audio_in = f.read(f.frames)  # shape: (channels, samples)

    board = Pedalboard([
        PitchShift(semitones=pitch_shift),
        LowpassFilter(cutoff_frequency_hz=lowpass_cutoff),
        HighpassFilter(cutoff_frequency_hz=highpass_cutoff),
        Reverb(room_size=reverb_room),
        Gain(gain_db=gain_db),
    ])

    audio_out = board(audio_in, sample_rate)

    # Encode back to WAV in memory
    buf = io.BytesIO()
    sf.write(buf, audio_out.T, sample_rate, format="WAV", subtype="PCM_16")
    buf.seek(0)

    return StreamingResponse(buf, media_type="audio/wav")
