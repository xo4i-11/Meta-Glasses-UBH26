class Cooldown:
    """Suppress the same key within `seconds`."""

    def __init__(self, seconds: float):
        self.seconds = seconds
        self.last: dict[str, float] = {}

    def allow(self, key: str, now: float) -> bool:
        if now - self.last.get(key, float("-inf")) < self.seconds:
            return False
        self.last[key] = now
        return True
