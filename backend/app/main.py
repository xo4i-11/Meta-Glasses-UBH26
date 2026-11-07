import os
import time
from datetime import datetime, timezone
from typing import Literal

from dotenv import load_dotenv
from fastapi import BackgroundTasks, Depends, FastAPI, File, Form, HTTPException, Request, Response, UploadFile
from fastapi.concurrency import run_in_threadpool

from app import db, gemini, tts

load_dotenv()

SORRY = "Sorry, I couldn't see that."


def auth(request: Request):
    secret = os.environ.get("SHARED_SECRET", "")
    got = request.headers.get("X-Secret") or request.query_params.get("key")
    if not secret or got != secret:
        raise HTTPException(401, "bad secret")


app = FastAPI(dependencies=[Depends(auth)])


@app.post("/frame")
async def frame(bg: BackgroundTasks,
                image: UploadFile = File(...),
                mode: Literal["describe", "read_text", "hazard"] = Form(...),
                client_ms: int | None = Form(None)):
    t0 = time.monotonic()
    jpeg = await image.read()
    if not jpeg:
        raise HTTPException(400, "empty image")
    try:
        v = await run_in_threadpool(gemini.analyze, jpeg, mode)
    except gemini.GeminiError as e:
        print("gemini failed:", e)
        v = gemini.Vision(hazard=False, say="" if mode == "hazard" else SORRY, objects=[], caption="")

    say, audio_id = v.say, None
    if say:
        audio_id = await run_in_threadpool(tts.speak, say)

    server_ms = int((time.monotonic() - t0) * 1000)
    bg.add_task(db.log_event, {
        "ts": datetime.now(timezone.utc).isoformat(), "mode": mode, "say": say, "objects": v.objects,
        "caption": v.caption, "hazard": v.hazard, "latency_ms": client_ms if client_ms is not None else server_ms,
    })
    return {"say": say, "hazard": v.hazard, "audio_id": audio_id, "server_ms": server_ms}


@app.get("/audio/{audio_id}")
def audio(audio_id: str):
    data = tts.get(audio_id)
    if data is None:
        raise HTTPException(404)
    return Response(data, media_type="audio/mpeg")
