"""ElevenLabs low-latency streaming text-to-speech.

The phone plays the MP3 as it arrives, so speech starts before the whole clip is generated.
If ElevenLabs is not configured or fails, the phone falls back to Android's built-in voice.
"""

from collections.abc import AsyncIterator

import httpx

from . import config

ELEVEN_URL = "https://api.elevenlabs.io/v1/text-to-speech/{voice}/stream"

_http = httpx.AsyncClient(timeout=httpx.Timeout(20.0, connect=5.0))


def enabled() -> bool:
    return bool(config.ELEVENLABS_API_KEY)


async def open_stream(text: str) -> httpx.Response:
    """Opens the ElevenLabs stream and checks the status before we commit to a 200 response."""
    req = _http.build_request(
        "POST",
        ELEVEN_URL.format(voice=config.ELEVENLABS_VOICE_ID),
        params={"output_format": "mp3_44100_64", "optimize_streaming_latency": "3"},
        headers={"xi-api-key": config.ELEVENLABS_API_KEY, "accept": "audio/mpeg"},
        json={
            "text": text,
            "model_id": config.ELEVENLABS_MODEL,
            "voice_settings": {"stability": 0.5, "similarity_boost": 0.75},
        },
    )
    resp = await _http.send(req, stream=True)
    if resp.status_code != 200:
        body = await resp.aread()
        await resp.aclose()
        raise RuntimeError(f"ElevenLabs {resp.status_code}: {body[:200]!r}")
    return resp


async def iter_audio(resp: httpx.Response) -> AsyncIterator[bytes]:
    try:
        async for chunk in resp.aiter_bytes():
            yield chunk
    finally:
        await resp.aclose()
