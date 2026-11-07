import pytest
from fastapi.testclient import TestClient

from app import db, gemini, main, tts
from app.gemini import GeminiError, Vision

H = {"X-Secret": "test"}
JPG = b"\xff\xd8fake"


@pytest.fixture
def client(monkeypatch):
    monkeypatch.setenv("SHARED_SECRET", "test")
    monkeypatch.delenv("DATABASE_URL", raising=False)
    db.RECENT.clear()
    tts.STORE.clear()
    monkeypatch.setattr(tts, "preload_hazard_clips", lambda: tts.PINNED.update(hazard_stop=b"s", hazard_duck=b"d"))
    if hasattr(main, "COOLDOWN"):
        main.COOLDOWN.last.clear()
    with TestClient(main.app) as c:
        yield c


@pytest.fixture
def fake_vision(monkeypatch):
    def set_(v: Vision):
        monkeypatch.setattr(gemini, "analyze", lambda jpeg, mode: v)
    return set_


@pytest.fixture
def gemini_raises(monkeypatch):
    def boom(jpeg, mode):
        raise GeminiError("quota")
    monkeypatch.setattr(gemini, "analyze", boom)


@pytest.fixture
def fake_speak(monkeypatch):
    def speak(text):
        tts.STORE["a1"] = b"mp3"
        return "a1"
    monkeypatch.setattr(tts, "speak", speak)


@pytest.fixture
def speak_fails(monkeypatch):
    monkeypatch.setattr(tts, "speak", lambda text: None)


def post_frame(client, mode, **data):
    return client.post("/frame", headers=H, files={"image": ("f.jpg", JPG)}, data={"mode": mode, **data}).json()
