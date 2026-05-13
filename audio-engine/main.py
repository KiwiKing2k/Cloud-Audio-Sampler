import io
import os
import json
import uuid
import numpy as np
import soundfile as sf
import aioboto3
from fastapi import FastAPI, File, UploadFile, HTTPException
from fastapi.responses import JSONResponse
from fastapi.exceptions import RequestValidationError
from pedalboard import Pedalboard, Delay, Reverb, Gain, PeakFilter, Distortion, Compressor, Clipping, PitchShift, LowpassFilter, Chorus
from pedalboard.io import AudioFile
from motor.motor_asyncio import AsyncIOMotorClient
from pydantic import BaseModel
from typing import List

app = FastAPI(title="Pro Bass FX Engine - Cloud Edition")

@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request, exc):
    error_details = exc.errors()
    print("eroare de validare detectata:", json.dumps(error_details, indent=2))
    return JSONResponse(status_code=422, content={"detail": error_details})

# configurare stocare obiecte s3/minio
S3_ENDPOINT = os.getenv("S3_ENDPOINT", "http://storage:9000")
S3_ACCESS_KEY = os.getenv("S3_ACCESS_KEY", "admin")
S3_SECRET_KEY = os.getenv("S3_SECRET_KEY", "password123")
BUCKET_NAME = "processed-audio"

session = aioboto3.Session()

# configurare baza de date mongodb
MONGO_URL = os.getenv("MONGO_URL", "mongodb://database:27017")
client = AsyncIOMotorClient(MONGO_URL)
db = client.sound_fx_db
presets_collection = db.presets

class Preset(BaseModel):
    name: str = "Untitled"
    pitch: float = 0.0
    lp_filter: float = 20000.0
    chorus: float = 0.0
    fuzz: float = 0.0
    eq: List[float] = [0.0] * 8
    comp_threshold: float = 0.0
    comp_ratio: float = 1.0
    delay_feed: float = 0.0
    delay_time: float = 0.5
    delay_level: float = 0.0
    reverb_room: float = 0.2
    reverb_wet: float = 0.0
    clip: float = 0.0
    master: float = 0.0

@app.on_event("startup")
async def startup_event():
    async with session.client("s3", endpoint_url=S3_ENDPOINT,
                              aws_access_key_id=S3_ACCESS_KEY,
                              aws_secret_access_key=S3_SECRET_KEY) as s3:
        try:
            await s3.create_bucket(Bucket=BUCKET_NAME)
        except:
            pass

        # setare politica publica pentru accesul frontend-ului la wav-uri
        public_policy = {
            "Version": "2012-10-17",
            "Statement": [{
                "Effect": "Allow",
                "Principal": {"AWS": ["*"]},
                "Action": ["s3:GetObject"],
                "Resource": [f"arn:aws:s3:::{BUCKET_NAME}/*"]
            }]
        }
        await s3.put_bucket_policy(Bucket=BUCKET_NAME, Policy=json.dumps(public_policy))

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

    # algoritm de tail padding dinamic bazat pe dimensiunea camerei
    # durata cozii in secunde: $1.5 + (reverb\_room \cdot 3.0)$
    tail_duration = 1.5 + (reverb_room * 3.0)
    padding_samples = int(sample_rate * tail_duration)
    silence = np.zeros((audio_in.shape[0], padding_samples))
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

    # stocare rezultat in cloud si returnare url
    file_id = f"{uuid.uuid4()}.wav"
    async with session.client("s3", endpoint_url=S3_ENDPOINT,
                              aws_access_key_id=S3_ACCESS_KEY,
                              aws_secret_access_key=S3_SECRET_KEY) as s3:
        await s3.upload_fileobj(buf, BUCKET_NAME, file_id)

    return {"status": "processed", "file_url": f"{S3_ENDPOINT}/{BUCKET_NAME}/{file_id}"}

@app.post("/presets/save")
async def save_preset(preset: Preset):
    try:
        preset_dict = preset.dict()
        result = await presets_collection.insert_one(preset_dict)
        return {"status": "success", "id": str(result.inserted_id)}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/presets")
async def get_all_presets():
    presets = []
    async for doc in presets_collection.find():
        doc["_id"] = str(doc["_id"])
        presets.append(doc)
    return presets