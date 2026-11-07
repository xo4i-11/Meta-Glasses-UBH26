import os
from collections import deque
from datetime import datetime, timedelta, timezone

RECENT: deque = deque(maxlen=200)  # always kept: dashboard + memory work even without Tiger Data
DB_OK = False

COLS = ("ts", "mode", "say", "objects", "caption", "hazard", "latency_ms")


def _conn():
    import psycopg
    return psycopg.connect(os.environ["DATABASE_URL"], connect_timeout=5, autocommit=True)


def init() -> None:
    """Create the hypertable if DATABASE_URL is set. Any failure -> run on the in-memory deque only."""
    global DB_OK
    DB_OK = False
    if not os.environ.get("DATABASE_URL"):
        return
    try:
        with _conn() as c:
            c.execute("""CREATE TABLE IF NOT EXISTS events (
                ts timestamptz NOT NULL, mode text, say text, objects text[],
                caption text, hazard bool, latency_ms int)""")
            c.execute("SELECT create_hypertable('events', by_range('ts'), if_not_exists => TRUE)")
        DB_OK = True
    except Exception as e:
        print("Tiger Data unavailable, using memory only:", e)


def log_event(ev: dict) -> None:
    RECENT.append(ev)
    if not DB_OK:
        return
    try:
        with _conn() as c:
            c.execute(f"INSERT INTO events ({','.join(COLS)}) VALUES ({','.join(['%s'] * len(COLS))})",
                      [ev[k] for k in COLS])
    except Exception as e:
        print("Tiger Data insert failed:", e)


def recent_events(limit: int = 100) -> list[dict]:
    return list(reversed(RECENT))[:limit]


def recent_observations(minutes: int = 60, limit: int = 40) -> list[dict]:
    """Rows with non-empty objects in the last `minutes`, newest first."""
    if DB_OK:
        try:
            with _conn() as c:
                cur = c.execute(
                    f"SELECT {','.join(COLS)} FROM events WHERE ts > now() - make_interval(mins => %s)"
                    " AND cardinality(objects) > 0 ORDER BY ts DESC LIMIT %s", [minutes, limit])
                return [{**dict(zip(COLS, r)), "ts": r[0].isoformat()} for r in cur.fetchall()]
        except Exception as e:
            print("Tiger Data query failed, using memory:", e)
    cutoff = datetime.now(timezone.utc) - timedelta(minutes=minutes)
    rows = [e for e in reversed(RECENT) if e["objects"] and datetime.fromisoformat(e["ts"]) > cutoff]
    return rows[:limit]
