from app import db
from app.gemini import Vision
from tests.conftest import H, JPG, post_frame


def test_frame_requires_secret(client):
    r = client.post("/frame", files={"image": ("f.jpg", JPG)}, data={"mode": "describe"})
    assert r.status_code == 401


def test_frame_bad_mode(client):
    r = client.post("/frame", headers=H, files={"image": ("f.jpg", JPG)}, data={"mode": "dance"})
    assert r.status_code == 422


def test_frame_empty_image(client):
    r = client.post("/frame", headers=H, files={"image": ("f.jpg", b"")}, data={"mode": "describe"})
    assert r.status_code == 400


def test_frame_describe(client, fake_vision, fake_speak):
    fake_vision(Vision(hazard=False, say="A red backpack on a table.", objects=["backpack on table"], caption="x"))
    r = post_frame(client, "describe")
    assert r["say"] == "A red backpack on a table." and r["hazard"] is False and r["audio_id"] == "a1"
    a = client.get("/audio/a1", headers=H)
    assert a.status_code == 200 and a.content == b"mp3" and a.headers["content-type"] == "audio/mpeg"


def test_audio_unknown_404(client):
    assert client.get("/audio/nope", headers=H).status_code == 404


def test_audio_key_query_param(client, fake_vision, fake_speak):
    fake_vision(Vision(hazard=False, say="A door.", objects=[], caption=""))
    post_frame(client, "describe")
    assert client.get("/audio/a1?key=test").status_code == 200


def test_frame_gemini_error_describe_apologizes(client, gemini_raises, fake_speak):
    r = post_frame(client, "describe")
    assert r["say"] == "Sorry, I couldn't see that." and r["audio_id"] == "a1"


def test_frame_gemini_error_hazard_silent(client, gemini_raises):
    r = post_frame(client, "hazard")
    assert r["say"] == "" and r["hazard"] is False and r["audio_id"] is None


def test_frame_tts_failure_returns_say(client, fake_vision, speak_fails):
    fake_vision(Vision(hazard=False, say="A door.", objects=[], caption=""))
    r = post_frame(client, "describe")
    assert r["say"] == "A door." and r["audio_id"] is None


def test_frame_logs_event(client, fake_vision, fake_speak):
    fake_vision(Vision(hazard=False, say="A door.", objects=["door on right"], caption="hall"))
    post_frame(client, "describe", client_ms="2400")
    ev = db.RECENT[-1]
    assert ev["mode"] == "describe" and ev["objects"] == ["door on right"] and ev["latency_ms"] == 2400


def test_hazard_none_is_silent(client, fake_vision):
    fake_vision(Vision(hazard=False, say="Chair ahead", objects=[], caption=""))
    r = post_frame(client, "hazard")
    assert r["audio_id"] is None and r["say"] == "" and r["hazard"] is False


def test_hazard_uses_cached_clip(client, fake_vision):
    fake_vision(Vision(hazard=True, say="Duck, branch ahead", objects=[], caption=""))
    r = post_frame(client, "hazard")
    assert r["audio_id"] == "hazard_duck" and r["hazard"] is True


def test_hazard_stop_clip_default(client, fake_vision):
    fake_vision(Vision(hazard=True, say="Stop, sign ahead", objects=[], caption=""))
    assert post_frame(client, "hazard")["audio_id"] == "hazard_stop"


def test_hazard_cooldown(client, fake_vision):
    fake_vision(Vision(hazard=True, say="Stop, head height", objects=[], caption=""))
    assert post_frame(client, "hazard")["audio_id"] == "hazard_stop"
    second = post_frame(client, "hazard")
    assert second["audio_id"] is None and second["say"] == ""  # within 10 s


def test_events_newest_first(client, fake_vision, fake_speak):
    fake_vision(Vision(hazard=False, say="first", objects=[], caption=""))
    post_frame(client, "describe")
    fake_vision(Vision(hazard=False, say="second", objects=[], caption=""))
    post_frame(client, "describe")
    ev = client.get("/events", headers=H).json()
    assert [e["say"] for e in ev[:2]] == ["second", "first"]


def test_events_requires_secret(client):
    assert client.get("/events").status_code == 401


def test_log_without_db_does_not_fail(client, fake_vision, fake_speak):
    fake_vision(Vision(hazard=False, say="A door.", objects=["door"], caption=""))
    assert client.post("/frame", headers=H, files={"image": ("f.jpg", JPG)}, data={"mode": "describe"}).status_code == 200
    assert client.get("/events", headers=H).json()[0]["say"] == "A door."


def test_dashboard_served(client):
    r = client.get("/?key=test")
    assert r.status_code == 200 and "Smart Eyes" in r.text


def test_recent_observations_filters_empty_and_old(client):
    from datetime import datetime, timedelta, timezone
    now = datetime.now(timezone.utc)
    db.log_event({"ts": (now - timedelta(minutes=90)).isoformat(), "mode": "describe", "say": "", "objects": ["old keys"], "caption": "", "hazard": False, "latency_ms": 1})
    db.log_event({"ts": now.isoformat(), "mode": "hazard", "say": "", "objects": [], "caption": "", "hazard": False, "latency_ms": 1})
    db.log_event({"ts": now.isoformat(), "mode": "describe", "say": "", "objects": ["keys on table"], "caption": "", "hazard": False, "latency_ms": 1})
    rows = db.recent_observations(minutes=60)
    assert [r["objects"] for r in rows] == [["keys on table"]]
