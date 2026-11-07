from collections import deque

RECENT: deque = deque(maxlen=200)


def log_event(ev: dict) -> None:
    RECENT.append(ev)
