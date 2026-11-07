from google.genai import errors

from app import gemini


class FakeModels:
    def __init__(self, calls):
        self.calls = calls

    def generate_content(self, model, contents, config):
        self.calls.append(model)
        if model == "busy-model":
            raise errors.ServerError(503, {"error": {"code": 503, "message": "high demand", "status": "UNAVAILABLE"}})
        return "ok"


def test_overloaded_model_falls_back_to_next(monkeypatch):
    calls = []
    monkeypatch.setenv("GEMINI_API_KEYS", "k1")
    monkeypatch.setenv("GEMINI_MODEL", "busy-model, backup-model")
    monkeypatch.setattr(gemini.genai, "Client", lambda **kw: type("C", (), {"models": FakeModels(calls)})())
    assert gemini._generate(["x"]) == "ok"
    assert calls == ["busy-model", "backup-model"]
