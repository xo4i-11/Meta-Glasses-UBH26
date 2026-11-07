import os

from google import genai
from google.genai import errors, types
from pydantic import BaseModel


class Vision(BaseModel):
    hazard: bool
    say: str
    objects: list[str]
    caption: str


class GeminiError(Exception):
    pass


OBJECTS = (" Always list visible objects in `objects` as short 'item on/near place' strings"
           " (e.g. 'keys on kitchen table'). `caption` is a plain one-line scene summary.")
PROMPTS = {
    "describe": "Describe this scene for a blind person in `say`: one short sentence, most useful things first."
                " Set hazard=false." + OBJECTS,
    "read_text": "Read the visible text in this image aloud in `say`, most important text first."
                 " If there is no readable text, say \"I don't see any text.\" Set hazard=false." + OBJECTS,
    "hazard": "You are a mobility aid for a blind person walking forward. hazard=true ONLY if an object is at the"
              " person's head or chest height in their walking path within about 2 meters (e.g. branch, sign,"
              " open cabinet door, hanging object). Floor and low obstacles such as chairs, tables, steps and bags"
              " are NOT hazards. If hazard, `say` is under 6 words and includes 'duck' when they can pass under it."
              " Otherwise `say` is empty." + OBJECTS,
}

_key_idx = 0


def _keys() -> list[str]:
    return [k.strip() for k in os.environ.get("GEMINI_API_KEYS", "").split(",") if k.strip()]


def _generate(contents, schema=None):
    """Call Gemini, rotating to the next key on 429. Raises GeminiError on any failure."""
    global _key_idx
    keys = _keys()
    if not keys:
        raise GeminiError("no GEMINI_API_KEYS")
    model = os.environ.get("GEMINI_MODEL", "gemini-2.5-flash-lite")
    cfg = types.GenerateContentConfig(response_mime_type="application/json", response_schema=schema) if schema else None
    for _ in range(len(keys)):
        client = genai.Client(api_key=keys[_key_idx % len(keys)], http_options=types.HttpOptions(timeout=8000))
        try:
            return client.models.generate_content(model=model, contents=contents, config=cfg)
        except errors.APIError as e:
            if e.code == 429:
                _key_idx += 1
                continue
            raise GeminiError(str(e)) from e
        except Exception as e:  # timeouts, network
            raise GeminiError(str(e)) from e
    raise GeminiError("all keys rate-limited")


def analyze(jpeg: bytes, mode: str) -> Vision:
    resp = _generate([types.Part.from_bytes(data=jpeg, mime_type="image/jpeg"), PROMPTS[mode]], Vision)
    if not isinstance(resp.parsed, Vision):
        raise GeminiError("unparseable response")
    return resp.parsed


def answer(question: str, rows: list[dict]) -> str:
    """Answer a memory question from logged observations (newest first). Raises GeminiError."""
    from datetime import datetime, timezone
    now = datetime.now(timezone.utc)
    lines = [f"{int((now - datetime.fromisoformat(r['ts'])).total_seconds() // 60)} min ago: {', '.join(r['objects'])}"
             for r in rows]
    prompt = ("You help a blind person remember where things are. Below are things their camera saw, newest first.\n"
              + "\n".join(lines)
              + f"\n\nQuestion: {question}\nAnswer in one short sentence with the place and how long ago "
              "(e.g. 'Your keys are on the table near the door, 4 minutes ago'). "
              "If it was not seen, say you haven't seen it.")
    resp = _generate([prompt])
    text = (resp.text or "").strip()
    if not text:
        raise GeminiError("empty answer")
    return text
