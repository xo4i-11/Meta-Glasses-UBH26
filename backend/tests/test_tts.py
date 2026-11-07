import elevenlabs.client

from app import tts


def test_elevenlabs_client_has_short_timeout(monkeypatch):
    seen = {}
    monkeypatch.setenv("ELEVEN_API_KEY", "k")
    monkeypatch.setattr(elevenlabs.client, "ElevenLabs", lambda **kw: seen.update(kw))
    tts._client()
    assert seen.get("timeout") is not None and seen["timeout"] <= 5  # phone gives up at 15 s
