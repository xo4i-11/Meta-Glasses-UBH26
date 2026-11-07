import os
import uuid
from collections import OrderedDict

STORE: "OrderedDict[str, bytes]" = OrderedDict()  # ponytail: in-memory, lost on restart; fine for a demo
MAX = 100


def _client():
    from elevenlabs.client import ElevenLabs
    return ElevenLabs(api_key=os.environ["ELEVEN_API_KEY"])


def _put(audio_id: str, data: bytes) -> str:
    STORE[audio_id] = data
    while len(STORE) > MAX:
        STORE.popitem(last=False)
    return audio_id


def _synth(text: str) -> bytes:
    chunks = _client().text_to_speech.convert(
        voice_id=os.environ["ELEVEN_VOICE_ID"], text=text,
        model_id="eleven_flash_v2_5", output_format="mp3_44100_128")
    return b"".join(chunks)


def speak(text: str) -> str | None:
    try:
        return _put(uuid.uuid4().hex[:8], _synth(text))
    except Exception as e:
        print("tts failed:", e)
        return None


def get(audio_id: str) -> bytes | None:
    return STORE.get(audio_id)
