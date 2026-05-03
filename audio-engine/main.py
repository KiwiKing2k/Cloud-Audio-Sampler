import io
import numpy as np
import soundfile as sf
from fastapi import FastAPI, File, UploadFile, HTTPException
from fastapi.responses import StreamingResponse
from pedalboard import Pedalboard, Delay, Reverb, Gain, PeakFilter, Distortion, Compressor, Clipping, PitchShift, LowpassFilter, Chorus
from pedalboard.io import AudioFile

app = FastAPI(title="Pro Bass FX Engine")

@app.post("/process")
async def process_audio(
        file: UploadFile = File(...),
        pitch_shift: float = 0.0,
        chorus_depth: float = 0.0,
        lp_cutoff: float = 20000.0,
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

    # Buffer Management: Padding pentru tail processing (delay/reverb)
    silence = np.zeros((audio_in.shape[0], int(sample_rate * 2.5)))
    audio_buffer = np.concatenate([audio_in, silence], axis=1)

    board = Pedalboard([
        PitchShift(semitones=pitch_shift),
        Chorus(depth=chorus_depth) if chorus_depth > 0 else Gain(0),
        LowpassFilter(cutoff_frequency_hz=lp_cutoff), # Corectat aici (p mic)
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
        Delay(delay_seconds=delay_time, feedback=delay_feedback, mix=delay_level),
        Reverb(room_size=reverb_room, wet_level=reverb_wet),
        Clipping(threshold_db=clip_threshold),
        Gain(gain_db=master_gain),
    ])

    audio_out = board(audio_buffer, sample_rate)

    buf = io.BytesIO()
    sf.write(buf, audio_out.T, sample_rate, format="WAV", subtype="PCM_16")
    buf.seek(0)
    return StreamingResponse(buf, media_type="audio/wav")