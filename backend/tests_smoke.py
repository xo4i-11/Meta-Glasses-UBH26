"""Smoke test the backend in mock mode:  .venv/Scripts/python tests_smoke.py"""
import io, os
os.environ["GEMINI_API_KEY"] = ""; os.environ["ELEVENLABS_API_KEY"] = ""; os.environ["DATABASE_URL"] = ""; os.environ["APP_TOKEN"] = ""
from fastapi.testclient import TestClient
from app import config
config.GEMINI_API_KEY = ""; config.ELEVENLABS_API_KEY = ""; config.APP_TOKEN = ""
from app.main import app

c = TestClient(app)
print("health", c.get("/health").json())
with c.websocket_connect("/ws") as ws:
    print("ws hello", ws.receive_json()["type"], ws.receive_json()["type"])
    r = c.post("/api/describe", files={"image": ("f.jpg", io.BytesIO(b"\xff\xd8fakejpeg"), "image/jpeg")}, data={"mode": "scan"})
    print("describe", r.status_code, r.json()["text"], "| hazard:", r.json()["hazard"])
    print("ws got", ws.receive_json()["type"], ws.receive_json()["type"])
r = c.post("/api/events", json={"kind": "cane", "distance_cm": 80, "direction": "ahead", "source": "cane"})
print("event", r.status_code)
print("memory", c.post("/api/memory/ask", json={"question": "Where are my keys?"}).json())
print("tts (expect 503 w/o key)", c.post("/api/tts", json={"text": "hi"}).status_code)
print("sky", c.get("/api/sky", params={"lat": 37.77, "lon": -122.42, "az": 180, "alt": 30}).json()["text"])
print("events", len(c.get("/api/events").json()), "stats", c.get("/api/stats").json())
print("latest.jpg", c.get("/api/latest.jpg").status_code)
print("live", c.post("/api/live", json={"distance_cm": 42, "cane_connected": True}).json())
print("tts GET (expect 503 w/o key)", c.get("/api/tts", params={"text": "hello"}).status_code)
print("dashboard", c.get("/").status_code, "bytes", len(c.get("/").content))

