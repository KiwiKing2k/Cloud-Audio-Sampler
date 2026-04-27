import io
import numpy as np
import soundfile as sf
from fastapi import FastAPI, File, UploadFile, HTTPException
from fastapi.responses import StreamingResponse
from pedalboard import Pedalboard, Delay, Reverb, Gain, PeakFilter, Distortion, Compressor, Clipping
from pedalboard.io import AudioFile

app = FastAPI(title="Pro Bass FX Engine")

@app.post("/process")
async def process_audio(
        file: UploadFile = File(...),
        fuzz_drive: float = 0.0,
        eq_40: float = 0.0, eq_80: float = 0.0, eq_160: float = 0.0, eq_320: float = 0.0,
        eq_640: float = 0.0, eq_1280: float = 0.0, eq_2560: float = 0.0, eq_5120: float = 0.0,
        comp_threshold: float = 0.0, comp_ratio: float = 1.0,
        delay_feedback: float = 0.0, delay_time: float = 0.5, delay_level: float = 0.0,
        reverb_room: float = 0.0, reverb_wet: float = 0.0,
        clip_threshold: float = 0.0, master_gain: float = 0.0
):
    raw = await file.read()
    if not raw: raise HTTPException(status_code=400, detail="Empty file")

    with AudioFile(io.BytesIO(raw)) as f:
        sample_rate = f.samplerate
        audio_in = f.read(f.frames)

    # Adăugăm 2 secunde la final pt delay
    silence_padding = np.zeros((audio_in.shape[0], int(sample_rate * 2)))
    audio_with_tail = np.concatenate([audio_in, silence_padding], axis=1)

    board = Pedalboard([
        Distortion(drive_db=fuzz_drive) if fuzz_drive > 0 else Gain(0),
        PeakFilter(cutoff_frequency_hz=40, gain_db=eq_40),
        PeakFilter(cutoff_frequency_hz=80, gain_db=eq_80),
        PeakFilter(cutoff_frequency_hz=160, gain_db=eq_160),
        PeakFilter(cutoff_frequency_hz=320, gain_db=eq_320),
        PeakFilter(cutoff_frequency_hz=640, gain_db=eq_640),
        PeakFilter(cutoff_frequency_hz=1280, gain_db=eq_1280),
        PeakFilter(cutoff_frequency_hz=2560, gain_db=eq_2560),
        PeakFilter(cutoff_frequency_hz=5120, gain_db=eq_5120),
        Compressor(threshold_db=comp_threshold, ratio=comp_ratio),
        # 'mix' în pedalboard: 0.0 = dry, 1.0 = wet. 0.3-0.5 e ideal pentru ecou
        Delay(delay_seconds=delay_time, feedback=delay_feedback, mix=delay_level),
        Reverb(room_size=reverb_room, wet_level=reverb_wet),
        Clipping(threshold_db=clip_threshold),
        Gain(gain_db=master_gain),
    ])

    audio_out = board(audio_with_tail, sample_rate)

    buf = io.BytesIO()
    sf.write(buf, audio_out.T, sample_rate, format="WAV", subtype="PCM_16")
    buf.seek(0)
    return StreamingResponse(buf, media_type="audio/wav")