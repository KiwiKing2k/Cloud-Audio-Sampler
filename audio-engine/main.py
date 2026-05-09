import io
import os
import json # necesar pentru debug
import numpy as np
import soundfile as sf
from fastapi import FastAPI, File, UploadFile, HTTPException
from fastapi.responses import StreamingResponse, JSONResponse # adăugat JSONResponse
from fastapi.exceptions import RequestValidationError # adăugat pentru prinderea erorilor
from pedalboard import Pedalboard, Delay, Reverb, Gain, PeakFilter, Distortion, Compressor, Clipping, PitchShift, LowpassFilter, Chorus
from pedalboard.io import AudioFile
from motor.motor_asyncio import AsyncIOMotorClient
from pydantic import BaseModel
from typing import List

app = FastAPI(title="Pro Bass FX Engine")

# --- modul detectiv: printează în consola docker de ce dă eroarea 422 ---
@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request, exc):
    error_details = exc.errors()
    print("❌ EROARE DE VALIDARE DETECTATĂ:")
    print(json.dumps(error_details, indent=2))
    return JSONResponse(
        status_code=422,
        content={"detail": error_details},
    )

# --- configurare mongodb ---
MONGO_URL = os.getenv("MONGO_URL", "mongodb://database:27017")
client = AsyncIOMotorClient(MONGO_URL)
db = client.sound_fx_db
presets_collection = db.presets

# --- modelul de date (oglindă perfectă cu fxparams din kotlin) ---
class Preset(BaseModel):
    name: str
    pitch: float
    lp_filter: float
    chorus: float
    fuzz: float
    eq: List[float]
    comp_threshold: float
    comp_ratio: float
    delay_feed: float
    delay_time: float
    delay_level: float
    reverb_room: float
    reverb_wet: float
    clip: float
    master: float

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

    silence = np.zeros((audio_in.shape[0], int(sample_rate * 2.5)))
    audio_buffer = np.concatenate([audio_in, silence], axis=1)

    board = Pedalboard([
        PitchShift(semitones=pitch_shift),
        Chorus(depth=chorus_depth) if chorus_depth > 0 else Gain(0),
        LowpassFilter(cutoff_frequency_hz=lp_cutoff),
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

@app.post("/presets/save")
async def save_preset(preset: Preset):
    try:
        preset_dict = preset.dict()
        result = await presets_collection.insert_one(preset_dict)
        return {"status": "success", "id": str(result.inserted_id)}
    except Exception as e:
        print(f"debug mongo error: {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/presets")
async def get_all_presets():
    presets = []
    async for doc in presets_collection.find():
        doc["_id"] = str(doc["_id"])
        presets.append(doc)
    return presets