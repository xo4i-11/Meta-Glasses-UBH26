"""Scene understanding with Gemini.

Every call returns one short sentence (fast to speak) plus structured objects for the memory log.
With no GEMINI_API_KEY set, returns canned answers so the rest of the system can be built.
"""

import asyncio
import json
import time
from datetime import datetime, timezone

from pydantic import BaseModel, Field

from . import config


class SceneObject(BaseModel):
    label: str = Field(description="Short noun, e.g. 'keys', 'chair', 'door'")
    position: str = Field(description="left, ahead, or right, from the wearer's point of view")
    distance: str = Field(description="near (under 1.5 m), medium (1.5-4 m), or far")
    height: str = Field(description="ground, waist, or head")
    location_hint: str = Field(description="What it is on or next to, e.g. 'on the kitchen counter'")


class SceneResult(BaseModel):
    sentence: str = Field(description="What to say out loud. One short sentence, max 20 words.")
    hazard: str = Field(
        description="Empty string unless something is in the walking path within about 2 m or at head height. "
        "Then a 3-6 word warning with a direction, e.g. 'Low branch ahead, duck'.",
    )
    objects: list[SceneObject]


STYLE = (
    "You are Smart Eyes, a calm assistant speaking through glasses to a blind person. "
    "The photo is from a camera at their eye level, facing where they look. "
    "Use 'left', 'right' and 'ahead' from their point of view. Use plain words. "
    "Never describe or identify people's faces; just say 'a person'. "
    "If unsure, say 'I think'. "
)

PROMPTS = {
    "describe": STYLE
    + "Describe the scene in ONE short sentence (max 20 words), most important thing first, "
    "mentioning anything in their path. List up to 8 notable objects.",
    "scan": STYLE
    + "This is an automatic background check. Keep the sentence under 12 words. "
    "Fill 'hazard' ONLY if there is an obstacle in the walking path within ~2 m, "
    "something at head height (branch, sign, open cabinet door, shelf), stairs, or a drop. "
    "List up to 10 objects, including small personal items (keys, phone, wallet, glasses, bag) and what they are on.",
    "read": STYLE
    + "Read the main text in the image out loud in 'sentence' (it may be longer than 20 words, up to 60). "
    "For a menu or label, read the most useful part first. If there is no readable text, say so.",
    "ask": STYLE + "Answer the user's question about the image in ONE or two short sentences. Question: ",
}

_client = None


def _get_client():
    global _client
    if _client is None:
        from google import genai

        from google.genai import types

        _client = genai.Client(api_key=config.GEMINI_API_KEY, http_options=types.HttpOptions(timeout=10000))
    return _client


def _gen_config(schema=None, thinking=True):
    from google.genai import types

    kwargs = {"temperature": 0.2}
    if schema is not None:
        kwargs["response_mime_type"] = "application/json"
        kwargs["response_schema"] = schema
    if thinking and config.GEMINI_THINKING_BUDGET >= 0:
        kwargs["thinking_config"] = types.ThinkingConfig(thinking_budget=config.GEMINI_THINKING_BUDGET)
    return types.GenerateContentConfig(**kwargs)


_no_thinking: set[str] = set()  # models that reject the thinking setting (400)
last_model = config.GEMINI_MODEL  # which model answered most recently (shown in /health and responses)


MODEL_TIMEOUT_S = 7.0
TOTAL_BUDGET_S = 13.0


def _retryable(exc: Exception) -> bool:
    if isinstance(exc, (asyncio.TimeoutError, TimeoutError)):
        return True
    msg = str(exc)
    return any(code in msg for code in ("503", "UNAVAILABLE", "429", "RESOURCE_EXHAUSTED", "404", "NOT_FOUND", "504", "DEADLINE_EXCEEDED", "INVALID_ARGUMENT", "500", "INTERNAL", "timed out", "Timeout", "overloaded"))


async def _generate(contents, schema=None):
    """Calls Gemini, falling through GEMINI_FALLBACK_MODELS if a model is busy or gone.
    Also drops the 'thinking off' setting once if a model rejects it."""
    global last_model
    models = [config.GEMINI_MODEL] + [m for m in config.GEMINI_FALLBACK_MODELS if m != config.GEMINI_MODEL]
    last_exc = None
    deadline = time.perf_counter() + TOTAL_BUDGET_S  # the phone gives up after 15 s
    for model in models:
        left = deadline - time.perf_counter()
        if left < 1.5:
            break
        for _ in range(2):  # second pass only happens after dropping the thinking setting
            try:
                resp = await asyncio.wait_for(  # our own cut-off: a stuck model hands off after 7 s
                    _get_client().aio.models.generate_content(
                        model=model, contents=contents, config=_gen_config(schema, thinking=model not in _no_thinking)
                    ),
                    timeout=min(MODEL_TIMEOUT_S, left),
                )
                last_model = model
                return resp
            except Exception as exc:
                last_exc = exc
                msg = str(exc)
                if model not in _no_thinking and ("thinking" in msg.lower() or "INVALID_ARGUMENT" in msg):
                    _no_thinking.add(model)  # e.g. flash-lite models reject thinking_budget
                    continue
                break
        why = f"{type(last_exc).__name__}: {str(last_exc)[:160]}"
        if last_exc is not None and not _retryable(last_exc):
            print(f"[gemini] {model} failed, not retrying ({why})")
            raise last_exc
        print(f"[gemini] {model} failed ({why}), trying next model")
    raise last_exc or TimeoutError("Gemini took too long")


async def analyze_image(image: bytes, mode: str = "describe", question: str = "", mime_type: str = "image/jpeg") -> tuple[SceneResult, float]:
    """Returns (result, latency_ms)."""
    started = time.perf_counter()
    mode = mode if mode in PROMPTS else "describe"
    if not config.GEMINI_API_KEY:
        return _mock(mode, question), (time.perf_counter() - started) * 1000

    from google.genai import types

    prompt = PROMPTS[mode] + (question if mode == "ask" else "")
    contents = [types.Part.from_bytes(data=image, mime_type=mime_type), prompt]
    resp = await _generate(contents, SceneResult)
    result = resp.parsed if isinstance(resp.parsed, SceneResult) else SceneResult.model_validate_json(resp.text)
    return result, (time.perf_counter() - started) * 1000


async def answer_from_memory(question: str, sightings: list[dict]) -> str:
    """'Where are my keys?' answered from what the camera logged earlier."""
    if not sightings:
        return "I haven't seen that yet. Try looking around slowly so I can spot it."
    now = datetime.now(timezone.utc)
    lines = []
    for s in sightings[:60]:
        try:
            ts = datetime.fromisoformat(str(s["ts"]))
            mins = int((now - ts).total_seconds() // 60)
            ago = "just now" if mins < 1 else f"{mins} min ago"
        except ValueError:
            ago = str(s["ts"])
        objs = s.get("objects") or []
        obj_txt = "; ".join(
            f"{o.get('label')} {o.get('location_hint') or ''} ({o.get('position')}, {o.get('distance')})".strip()
            for o in objs
            if isinstance(o, dict)
        )
        lines.append(f"[{ago}] {s.get('description', '')} | objects: {obj_txt}")

    if not config.GEMINI_API_KEY:
        return f"Mock memory answer. Most relevant log: {lines[0]}"

    prompt = (
        "You are Smart Eyes, helping a blind user find things. Below is a log of what their camera saw, newest first. "
        "Answer their question in ONE or two short sentences: where the thing was and how long ago. "
        "If the log does not mention it, say you have not seen it.\n\nLOG:\n"
        + "\n".join(lines)
        + f"\n\nQUESTION: {question}"
    )
    resp = await _generate(prompt)
    return (resp.text or "").strip()


def _mock(mode: str, question: str) -> SceneResult:
    if mode == "read":
        return SceneResult(sentence="Mock reading: Today's special, tomato soup, four dollars.", hazard="", objects=[])
    if mode == "ask":
        return SceneResult(sentence=f"Mock answer to: {question}", hazard="", objects=[])
    return SceneResult(
        sentence="A red backpack on a table ahead, a door to your right.",
        hazard="Chair ahead, step left" if mode == "scan" else "",
        objects=[
            SceneObject(label="backpack", position="ahead", distance="medium", height="waist", location_hint="on the table"),
            SceneObject(label="keys", position="left", distance="near", height="waist", location_hint="on the kitchen counter"),
            SceneObject(label="door", position="right", distance="medium", height="head", location_hint=""),
            SceneObject(label="chair", position="ahead", distance="near", height="ground", location_hint=""),
        ],
    )


def objects_as_dicts(result: SceneResult) -> list[dict]:
    return [json.loads(o.model_dump_json()) for o in result.objects]
