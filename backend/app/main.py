"""Smart Eyes backend: keeps API keys off the phone and connects Gemini, ElevenLabs, Tiger Data
and the live dashboard.

Run locally:   uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload   (from backend/)
"""

import asyncio
import json
import re
import time
from typing import Optional

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, Response, StreamingResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import config, gemini, sky, tts
from .db import Database, now_iso

app = FastAPI(title="Smart Eyes")
db = Database()

# Latest frame is kept in RAM only (for the live dashboard). Never written to disk or the DB.
latest_frame: dict = {"bytes": None, "ts": None}


# ---- auth -------------------------------------------------------------------------------


def require_token(x_app_token: Optional[str] = Header(default=None)) -> None:
    if config.APP_TOKEN and x_app_token != config.APP_TOKEN:
        raise HTTPException(status_code=401, detail="bad or missing X-App-Token")


# ---- live dashboard websocket -------------------------------------------------------------


class Hub:
    def __init__(self) -> None:
        self.clients: set[WebSocket] = set()

    async def connect(self, ws: WebSocket) -> None:
        await ws.accept()
        self.clients.add(ws)

    def disconnect(self, ws: WebSocket) -> None:
        self.clients.discard(ws)

    async def broadcast(self, message: dict) -> None:
        dead = []
        for ws in list(self.clients):
            try:
                await ws.send_json(message)
            except Exception:
                dead.append(ws)
        for ws in dead:
            self.disconnect(ws)


hub = Hub()


@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket):
    await hub.connect(ws)
    try:
        await ws.send_json({"type": "hello", "db": db.backend_name, "gemini": bool(config.GEMINI_API_KEY), "tts": tts.enabled()})
        await ws.send_json({"type": "live", "data": {"cane_connected": bool(devices.canes), "phone_connected": bool(devices.phones)}})
        while True:
            await ws.receive_text()  # dashboard doesn't send anything meaningful; keep alive
    except WebSocketDisconnect:
        hub.disconnect(ws)


# ---- models -------------------------------------------------------------------------------


class EventIn(BaseModel):
    kind: str  # hazard | cane | sos | fall | describe | status
    label: Optional[str] = None
    distance_cm: Optional[float] = None
    direction: Optional[str] = None  # left | ahead | right
    source: Optional[str] = None  # cane | yolo | gemini | button
    latency_ms: Optional[float] = None
    lat: Optional[float] = None
    lon: Optional[float] = None
    detail: Optional[dict] = None


class TtsIn(BaseModel):
    text: str


class AskIn(BaseModel):
    question: str


class SosIn(BaseModel):
    to: str  # caregiver email
    reason: str
    lat: Optional[float] = None
    lon: Optional[float] = None


# ---- routes -------------------------------------------------------------------------------


@app.get("/health")
def health():
    return {"ok": True, "db": db.backend_name, "gemini": bool(config.GEMINI_API_KEY), "gemini_model": config.GEMINI_MODEL, "gemini_last_used": gemini.last_model, "gemini_fallbacks": config.GEMINI_FALLBACK_MODELS, "tts": tts.enabled()}


@app.post("/api/describe", dependencies=[Depends(require_token)])
async def describe(
    image: UploadFile = File(...),
    mode: str = Form("describe"),  # describe | scan | read | ask
    question: str = Form(""),
    source: str = Form("glasses"),
    lat: Optional[float] = Form(None),
    lon: Optional[float] = Form(None),
):
    data = await image.read()
    if not data:
        raise HTTPException(400, "empty image")
    latest_frame.update(bytes=data, ts=now_iso())

    try:
        result, latency_ms = await gemini.analyze_image(data, mode=mode, question=question, mime_type=image.content_type or "image/jpeg")
    except Exception as exc:
        print(f"[describe] {mode} failed: {type(exc).__name__}: {str(exc)[:200]}")
        raise HTTPException(502, f"Gemini failed: {exc}") from exc

    objects = gemini.objects_as_dicts(result)
    try:  # a database hiccup must never cost the user their answer
        sighting = await run_in_threadpool(db.add_sighting, source, mode, result.sentence, objects, lat, lon)
    except Exception as exc:
        print(f"[db] sighting not saved: {type(exc).__name__}: {str(exc)[:120]}")
        sighting = {"ts": now_iso(), "source": source, "mode": mode, "description": result.sentence, "objects": objects, "lat": lat, "lon": lon}
    await hub.broadcast({"type": "sighting", "data": sighting, "latency_ms": round(latency_ms), "hazard": result.hazard, "frame_ts": latest_frame["ts"]})

    if result.hazard:
        ev = await run_in_threadpool(
            db.add_event, kind="hazard", label=result.hazard, source="gemini", latency_ms=latency_ms, lat=lat, lon=lon
        )
        await hub.broadcast({"type": "event", "data": ev})

    return {"text": result.sentence, "hazard": result.hazard, "objects": objects, "latency_ms": round(latency_ms), "model": gemini.last_model}


@app.post("/api/tts", dependencies=[Depends(require_token)])
async def speak(body: TtsIn):
    return await _tts_response(body.text)


@app.get("/api/tts")
async def speak_get(text: str, token: str = ""):
    """GET variant so Android's MediaPlayer can stream straight from a URL (starts playing immediately)."""
    if config.APP_TOKEN and token != config.APP_TOKEN:
        raise HTTPException(401, "bad token")
    return await _tts_response(text)


async def _tts_response(text: str):
    text = text.strip()[:600]
    if not text:
        raise HTTPException(400, "empty text")
    if not tts.enabled():
        raise HTTPException(503, "ElevenLabs not configured; use on-device TTS")
    try:
        resp = await tts.open_stream(text)
    except Exception as exc:
        raise HTTPException(502, str(exc)) from exc
    return StreamingResponse(tts.iter_audio(resp), media_type="audio/mpeg")


@app.post("/api/events", dependencies=[Depends(require_token)])
async def add_event(ev: EventIn):
    row = await run_in_threadpool(db.add_event, **ev.model_dump())
    await hub.broadcast({"type": "event", "data": row})
    return row


def send_sos_email(to: str, reason: str, lat: Optional[float], lon: Optional[float]) -> None:
    """Plain-text email via SMTP (STARTTLS). Raises on failure so the phone can say so honestly."""
    import smtplib
    from email.message import EmailMessage

    if not (config.SMTP_USER and config.SMTP_PASS):
        raise RuntimeError("email not set up on the server (SMTP_USER / SMTP_PASS)")
    where = (
        f"https://maps.google.com/?q={lat:.6f},{lon:.6f}\n(latitude {lat:.6f}, longitude {lon:.6f})"
        if lat is not None and lon is not None
        else "Location unknown (no GPS fix)."
    )
    msg = EmailMessage()
    msg["Subject"] = ("[TEST] " if reason.startswith("TEST") else "") + "SMART EYES SOS: your person may need help"
    msg["From"] = f"Smart Eyes <{config.SMTP_USER}>"
    msg["To"] = to
    msg.set_content(
        f"Smart Eyes sent an SOS.\n\nWhat happened: {reason}\nTime: {time.strftime('%Y-%m-%d %H:%M:%S %Z')}\n\n"
        f"Where they are:\n{where}\n\nPlease check on them right away."
    )
    with smtplib.SMTP(config.SMTP_HOST, config.SMTP_PORT, timeout=12) as s:
        s.starttls()
        s.login(config.SMTP_USER, config.SMTP_PASS)
        s.send_message(msg)


@app.post("/api/sos", dependencies=[Depends(require_token)])
async def sos(body: SosIn):
    """Phone asks the server to email the caregiver (the phone has no SIM, so no SMS)."""
    row = await run_in_threadpool(db.add_event, kind="sos", label=body.reason, source="phone", lat=body.lat, lon=body.lon)
    await hub.broadcast({"type": "event", "data": row})
    try:
        await run_in_threadpool(send_sos_email, body.to, body.reason, body.lat, body.lon)
    except Exception as e:
        print(f"[sos] email failed: {type(e).__name__}: {e}")
        raise HTTPException(status_code=502, detail=f"email failed: {e}")
    return {"ok": True}


@app.post("/api/live", dependencies=[Depends(require_token)])
async def live(payload: dict):
    """High-rate telemetry (cane distance, connection status) for the dashboard only. Not stored."""
    await hub.broadcast({"type": "live", "data": payload})
    return {"ok": True}


@app.get("/api/events")
async def list_events(limit: int = 100):
    return await run_in_threadpool(db.recent_events, min(limit, 1000))


@app.get("/api/sightings")
async def list_sightings(limit: int = 50):
    return await run_in_threadpool(db.recent_sightings, min(limit, 500))


@app.get("/api/stats")
async def stats():
    return await run_in_threadpool(db.stats)


STOP_WORDS = {"where", "what", "when", "did", "leave", "left", "have", "seen", "with", "that", "this", "were", "your", "find", "last", "time", "the", "my", "are", "is"}


@app.post("/api/memory/ask", dependencies=[Depends(require_token)])
async def memory_ask(body: AskIn):
    started = time.perf_counter()
    words = [w for w in re.findall(r"[a-zA-Z]+", body.question.lower()) if len(w) > 2 and w not in STOP_WORDS]
    # Also try singular forms: "keys" -> "key"
    words += [w[:-1] for w in words if w.endswith("s") and len(w) > 3]
    matched, recent = await asyncio.gather(
        run_in_threadpool(db.search_sightings, words, 30), run_in_threadpool(db.recent_sightings, 20)
    )
    seen = set()
    merged = []
    for s in matched + recent:
        key = (s["ts"], s["description"])
        if key not in seen:
            seen.add(key)
            merged.append(s)
    merged.sort(key=lambda s: str(s["ts"]), reverse=True)
    answer = await gemini.answer_from_memory(body.question, merged)
    latency_ms = (time.perf_counter() - started) * 1000
    await hub.broadcast({"type": "memory", "question": body.question, "answer": answer, "latency_ms": round(latency_ms)})
    return {"answer": answer, "matches": len(matched), "latency_ms": round(latency_ms)}


@app.get("/api/sky")
async def sky_mode(lat: float, lon: float, az: float, alt: float):
    result = await run_in_threadpool(sky.what_am_i_facing, lat, lon, az, alt)
    await hub.broadcast({"type": "sky", "data": result})
    return result


@app.get("/api/latest.jpg")
def latest_jpg():
    if not latest_frame["bytes"]:
        raise HTTPException(404, "no frame yet")
    return Response(latest_frame["bytes"], media_type="image/jpeg", headers={"Cache-Control": "no-store"})


class SightingIn(BaseModel):
    description: str
    objects: list[dict] = []
    source: str = "yolo"
    lat: Optional[float] = None
    lon: Optional[float] = None


@app.post("/api/sightings", dependencies=[Depends(require_token)])
async def add_sighting(s: SightingIn):
    """On-phone YOLO logs what it sees (labels only) so 'where's my backpack?' works without Gemini."""
    row = await run_in_threadpool(db.add_sighting, s.source, "yolo", s.description, s.objects, s.lat, s.lon)
    await hub.broadcast({"type": "sighting", "data": row, "latency_ms": None})
    return {"ok": True}


# ---- cane <-> phone relay -------------------------------------------------------------------
#
# The cane (ESP32-C3) and the phone app never talk directly. Both open a WebSocket here:
#   /ws/device?role=cane&token=...      /ws/device?role=phone&token=...
# Messages are small JSON objects with a "t" (type) field. See README "Cane <-> phone protocol".


class DeviceHub:
    def __init__(self) -> None:
        self.canes: set[WebSocket] = set()
        self.phones: set[WebSocket] = set()
        self.phone_loc: dict | None = None  # last {"lat":..,"lon":..} reported by the phone
        self.cane_close = False  # hysteresis for logging "obstacle" events

    async def send(self, group: set[WebSocket], msg: dict) -> int:
        sent = 0
        for ws in list(group):
            try:
                await ws.send_json(msg)
                sent += 1
            except Exception:
                group.discard(ws)
        return sent

    async def status(self) -> None:
        await hub.broadcast({"type": "live", "data": {"cane_connected": bool(self.canes), "phone_connected": bool(self.phones)}})
        await self.send(self.phones, {"t": "status", "cane": bool(self.canes)})


devices = DeviceHub()


async def log_event(**fields) -> dict:
    row = await run_in_threadpool(db.add_event, **fields)
    await hub.broadcast({"type": "event", "data": row})
    return row


async def handle_cane(msg: dict) -> None:
    t = msg.get("t")
    if t == "tele":  # ~4 Hz: distance, auto mode
        d = msg.get("d", -1)
        await hub.broadcast({"type": "live", "data": {"distance_cm": d, "auto": msg.get("auto")}})
        await devices.send(devices.phones, msg)
        if isinstance(d, (int, float)) and 0 < d <= 80 and not devices.cane_close:
            devices.cane_close = True
            await log_event(kind="cane", label="obstacle", distance_cm=d, direction="ahead", source="cane")
        elif not isinstance(d, (int, float)) or d < 0 or d > 120:
            devices.cane_close = False
        return

    loc = devices.phone_loc or {}
    if t == "btn":
        await hub.broadcast({"type": "button", "data": {"b": msg.get("b"), "ts": now_iso()}})
    elif t == "fall":
        await log_event(kind="fall", label="cane down, counting down to SOS", source="cane-imu", lat=loc.get("lat"), lon=loc.get("lon"))
    elif t == "cancel":
        await log_event(kind="status", label="SOS countdown cancelled by user", source="cane")
    elif t == "sos":
        msg = {**msg, "lat": loc.get("lat"), "lon": loc.get("lon")}
        await log_event(kind="sos", label=f"SOS: {msg.get('reason', 'button')}", source="cane", lat=loc.get("lat"), lon=loc.get("lon"))
    await devices.send(devices.phones, msg)


async def handle_phone(msg: dict) -> None:
    t = msg.get("t")
    if t == "loc":
        devices.phone_loc = {"lat": msg.get("lat"), "lon": msg.get("lon")}
        return
    # tone / find / auto / cfg -> cane
    await devices.send(devices.canes, msg)


@app.websocket("/ws/device")
async def device_ws(ws: WebSocket, role: str = "cane", token: str = ""):
    if config.APP_TOKEN and token != config.APP_TOKEN:
        await ws.close(code=1008)
        return
    await ws.accept()
    group = devices.canes if role == "cane" else devices.phones
    group.add(ws)
    await devices.status()
    try:
        while True:
            text = await ws.receive_text()
            try:
                msg = json.loads(text)
            except json.JSONDecodeError:
                continue
            if not isinstance(msg, dict):
                continue
            await (handle_cane(msg) if role == "cane" else handle_phone(msg))
    except WebSocketDisconnect:
        pass
    finally:
        group.discard(ws)
        await devices.status()


# ---- dashboard ----------------------------------------------------------------------------

if config.DASHBOARD_DIR.exists():
    app.mount("/static", StaticFiles(directory=config.DASHBOARD_DIR), name="static")

    @app.get("/")
    def dashboard():
        return FileResponse(config.DASHBOARD_DIR / "index.html")
