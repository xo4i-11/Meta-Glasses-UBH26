"""Storage for the memory log and hazard log.

Uses Tiger Data (PostgreSQL + TimescaleDB) when DATABASE_URL is set, otherwise a local
SQLite file so everyone can run the backend on a laptop with zero setup.

Privacy rule: we store text descriptions and object labels only. Never images, never faces.
"""

import json
import sqlite3
import threading
from datetime import datetime, timezone
from typing import Any

from . import config

# Our own table names. The shared Tiger Data database also holds a teammate's "events" table
# with different columns, so never use bare "events" / "sightings".
EVENTS = f"{config.DB_TABLE_PREFIX}events"
SIGHTINGS = f"{config.DB_TABLE_PREFIX}sightings"

SCHEMA = [
    f"""CREATE TABLE IF NOT EXISTS {SIGHTINGS} (
        ts TIMESTAMPTZ NOT NULL,
        source TEXT,
        mode TEXT,
        description TEXT,
        objects TEXT,
        lat DOUBLE PRECISION,
        lon DOUBLE PRECISION
    )""",
    f"""CREATE TABLE IF NOT EXISTS {EVENTS} (
        ts TIMESTAMPTZ NOT NULL,
        kind TEXT NOT NULL,
        label TEXT,
        distance_cm DOUBLE PRECISION,
        direction TEXT,
        source TEXT,
        latency_ms DOUBLE PRECISION,
        lat DOUBLE PRECISION,
        lon DOUBLE PRECISION,
        detail TEXT
    )""",
]

SIGHTING_COLS = ["ts", "source", "mode", "description", "objects", "lat", "lon"]
EVENT_COLS = ["ts", "kind", "label", "distance_cm", "direction", "source", "latency_ms", "lat", "lon", "detail"]


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class Database:
    def __init__(self) -> None:
        self.is_postgres = bool(config.DATABASE_URL)
        self._lock = threading.Lock()
        if self.is_postgres:
            import psycopg

            try:
                self._conn = psycopg.connect(config.DATABASE_URL, autocommit=True, connect_timeout=8)
            except Exception as exc:  # never let a bad DB link take the demo down
                print(f"[db] Tiger Data connection failed, using local SQLite instead: {str(exc).splitlines()[0]}")
                self.is_postgres = False
        if not self.is_postgres:
            self._conn = sqlite3.connect(config.SQLITE_PATH, check_same_thread=False)
            self._conn.row_factory = sqlite3.Row
        self._init_schema()

    @property
    def backend_name(self) -> str:
        return "Tiger Data (PostgreSQL)" if self.is_postgres else f"SQLite ({config.SQLITE_PATH.name})"

    def _sql(self, query: str) -> str:
        return query.replace("?", "%s") if self.is_postgres else query

    def _reconnect(self) -> None:
        import psycopg

        try:
            self._conn.close()
        except Exception:
            pass
        self._conn = psycopg.connect(config.DATABASE_URL, autocommit=True, connect_timeout=8)
        print("[db] reconnected to Tiger Data")

    def _exec(self, query: str, params: tuple = ()) -> list[dict[str, Any]]:
        with self._lock:
            if self.is_postgres:
                # Wi-Fi changes (eduroam -> UB_Devices, hotspot...) silently kill the socket: reconnect once and retry
                try:
                    if self._conn.closed:
                        self._reconnect()
                    cur = self._conn.cursor()
                    cur.execute(self._sql(query), params)
                except Exception as exc:
                    if exc.__class__.__name__ not in ("OperationalError", "InterfaceError"):
                        raise
                    self._reconnect()
                    cur = self._conn.cursor()
                    cur.execute(self._sql(query), params)
            else:
                cur = self._conn.cursor()
                cur.execute(self._sql(query), params)
            if cur.description is None:
                if not self.is_postgres:
                    self._conn.commit()
                return []
            cols = [d[0] for d in cur.description]
            return [dict(zip(cols, row)) for row in cur.fetchall()]

    def _init_schema(self) -> None:
        for stmt in SCHEMA:
            self._exec(stmt)
        if self.is_postgres:
            # Turn both tables into Timescale hypertables (time-series). Safe to re-run.
            for table in (SIGHTINGS, EVENTS):
                try:
                    self._exec(f"SELECT create_hypertable('{table}', 'ts', if_not_exists => TRUE, migrate_data => TRUE)")
                except Exception as exc:  # plain Postgres without Timescale still works
                    print(f"[db] hypertable for {table} skipped: {exc}")

    # ---- writes -------------------------------------------------------------------------

    def add_sighting(self, source: str, mode: str, description: str, objects: list[dict], lat=None, lon=None) -> dict:
        row = {
            "ts": now_iso(),
            "source": source,
            "mode": mode,
            "description": description,
            "objects": json.dumps(objects),
            "lat": lat,
            "lon": lon,
        }
        self._exec(
            f"INSERT INTO {SIGHTINGS} ({','.join(SIGHTING_COLS)}) VALUES ({','.join('?' * len(SIGHTING_COLS))})",
            tuple(row[c] for c in SIGHTING_COLS),
        )
        return {**row, "objects": objects}

    def add_event(self, **fields) -> dict:
        row = {c: fields.get(c) for c in EVENT_COLS}
        row["ts"] = row["ts"] or now_iso()
        if isinstance(row["detail"], (dict, list)):
            row["detail"] = json.dumps(row["detail"])
        self._exec(
            f"INSERT INTO {EVENTS} ({','.join(EVENT_COLS)}) VALUES ({','.join('?' * len(EVENT_COLS))})",
            tuple(row[c] for c in EVENT_COLS),
        )
        return row

    # ---- reads --------------------------------------------------------------------------

    @staticmethod
    def _clean(rows: list[dict]) -> list[dict]:
        for r in rows:
            if isinstance(r.get("ts"), datetime):
                r["ts"] = r["ts"].isoformat()
            if isinstance(r.get("objects"), str):
                try:
                    r["objects"] = json.loads(r["objects"])
                except json.JSONDecodeError:
                    pass
        return rows

    def recent_events(self, limit: int = 100) -> list[dict]:
        return self._clean(self._exec(f"SELECT * FROM {EVENTS} ORDER BY ts DESC LIMIT ?", (limit,)))

    def recent_sightings(self, limit: int = 50) -> list[dict]:
        return self._clean(self._exec(f"SELECT * FROM {SIGHTINGS} ORDER BY ts DESC LIMIT ?", (limit,)))

    def search_sightings(self, words: list[str], limit: int = 30) -> list[dict]:
        if not words:
            return []
        like = "ILIKE" if self.is_postgres else "LIKE"  # SQLite LIKE is case-insensitive for ASCII
        clause = " OR ".join([f"description {like} ? OR objects {like} ?"] * len(words))
        params: list[Any] = []
        for w in words:
            params += [f"%{w}%", f"%{w}%"]
        params.append(limit)
        return self._clean(self._exec(f"SELECT * FROM {SIGHTINGS} WHERE {clause} ORDER BY ts DESC LIMIT ?", tuple(params)))

    def stats(self) -> dict:
        rows = self._exec(f"SELECT kind, COUNT(*) AS n FROM {EVENTS} GROUP BY kind")
        return {r["kind"]: r["n"] for r in rows}
