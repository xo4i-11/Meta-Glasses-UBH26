from app import db
from tests.conftest import H


def test_hazard_event_logged_and_listed(client):
    db.HAZARDS.clear()
    r = client.post("/hazard_event", headers=H, json={"source": "yolo", "label": "umbrella", "confidence": 0.82, "latency_ms": 95, "status": "spoken"})
    assert r.status_code == 200 and r.json() == {"ok": True}
    client.post("/hazard_event", headers=H, json={"source": "gemini", "label": "Duck, head height", "confidence": None, "latency_ms": 2400, "status": "muted"})
    hz = client.get("/hazards", headers=H).json()
    assert [h["source"] for h in hz[:2]] == ["gemini", "yolo"]
    assert hz[0]["confidence"] is None and hz[0]["status"] == "muted" and hz[0]["latency_ms"] == 2400
    assert hz[1]["label"] == "umbrella" and abs(hz[1]["confidence"] - 0.82) < 1e-6 and "ts" in hz[1]


def test_hazard_event_bad_status_422(client):
    r = client.post("/hazard_event", headers=H, json={"source": "yolo", "label": "x", "confidence": 0.5, "latency_ms": 1, "status": "shouted"})
    assert r.status_code == 422


def test_hazard_event_bad_source_422(client):
    r = client.post("/hazard_event", headers=H, json={"source": "radar", "label": "x", "confidence": 0.5, "latency_ms": 1, "status": "spoken"})
    assert r.status_code == 422


def test_hazard_event_requires_secret(client):
    assert client.post("/hazard_event", json={}).status_code == 401
