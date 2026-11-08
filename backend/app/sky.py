"""Sky Mode: tell a blind user what planet or star they are facing at night.

Input: where they are (lat/lon) and where the glasses point (compass azimuth + tilt above horizon).
Uses PyEphem (no downloads needed). ISS passes use a live TLE from CelesTrak, cached for 6 hours.
"""

import math
import time
from datetime import datetime, timezone

import ephem
import httpx

BODIES = {
    "the Moon": ephem.Moon,
    "Mercury": ephem.Mercury,
    "Venus": ephem.Venus,
    "Mars": ephem.Mars,
    "Jupiter": ephem.Jupiter,
    "Saturn": ephem.Saturn,
}
BRIGHT_STARS = ["Sirius", "Canopus", "Arcturus", "Vega", "Capella", "Rigel", "Procyon", "Betelgeuse", "Altair", "Aldebaran", "Antares", "Spica", "Pollux", "Deneb", "Regulus", "Polaris"]

_tle_cache: dict = {"ts": 0.0, "lines": None}


def _compass(az_deg: float) -> str:
    names = ["north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"]
    return names[int(((az_deg % 360) + 22.5) // 45) % 8]


def moon_phase_text(obs: ephem.Observer) -> str:
    moon = ephem.Moon(obs)
    lit = moon.moon_phase * 100
    nxt_new = ephem.next_new_moon(obs.date)
    nxt_full = ephem.next_full_moon(obs.date)
    waxing = nxt_full < nxt_new
    if lit < 3:
        name = "new moon"
    elif lit > 97:
        name = "full moon"
    elif 45 <= lit <= 55:
        name = "first quarter" if waxing else "last quarter"
    else:
        name = ("waxing " if waxing else "waning ") + ("crescent" if lit < 50 else "gibbous")
    return f"The Moon is a {name}, {lit:.0f} percent lit."


def _iss_tle() -> list[str] | None:
    if _tle_cache["lines"] and time.time() - _tle_cache["ts"] < 6 * 3600:
        return _tle_cache["lines"]
    try:
        r = httpx.get("https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE", timeout=5)
        lines = [l.strip() for l in r.text.strip().splitlines() if l.strip()]
        if len(lines) >= 3:
            _tle_cache.update(ts=time.time(), lines=lines[:3])
    except Exception:
        pass
    return _tle_cache["lines"]


def iss_next_pass_text(obs: ephem.Observer) -> str:
    tle = _iss_tle()
    if not tle:
        return ""
    try:
        iss = ephem.readtle(*tle)
        rise_t, rise_az, _max_t, max_alt, _set_t, _set_az = obs.next_pass(iss)
        mins = int((ephem.Date(rise_t).datetime().replace(tzinfo=timezone.utc) - datetime.now(timezone.utc)).total_seconds() // 60)
        when = f"in {mins} minutes" if mins < 120 else f"in about {mins // 60} hours"
        return f"The Space Station next rises {when} in the {_compass(math.degrees(rise_az))}, reaching {math.degrees(max_alt):.0f} degrees up."
    except Exception:
        return ""


def what_am_i_facing(lat: float, lon: float, azimuth_deg: float, altitude_deg: float, include_iss: bool = True) -> dict:
    obs = ephem.Observer()
    obs.lat, obs.lon = str(lat), str(lon)
    obs.date = ephem.now()

    candidates = []
    for name, cls in BODIES.items():
        candidates.append((name, cls(obs)))
    for star in BRIGHT_STARS:
        try:
            candidates.append((star, ephem.star(star, obs)))
        except KeyError:
            pass

    pointing = (math.radians(azimuth_deg), math.radians(altitude_deg))
    best = None
    for name, body in candidates:
        if body.alt <= 0:
            continue
        sep = math.degrees(ephem.separation(pointing, (body.az, body.alt)))
        is_planet = name in BODIES
        score = sep - (5 if is_planet else 0)  # prefer planets slightly when close
        if best is None or score < best[0]:
            best = (score, sep, name, body)

    parts = []
    if best and best[1] < 25:
        _, sep, name, body = best
        alt = math.degrees(body.alt)
        lead = "You're facing" if sep < 10 else "Close to where you're facing is"
        parts.append(f"{lead} {name}, {alt:.0f} degrees above the {_compass(math.degrees(body.az))} horizon.")
    else:
        visible = [n for n, b in candidates if n in BODIES and b.alt > 0]
        parts.append(
            "Nothing bright right where you're pointing."
            + (f" Visible now: {', '.join(visible)}." if visible else "")
        )
    parts.append(moon_phase_text(obs))
    if include_iss:
        iss = iss_next_pass_text(obs)
        if iss:
            parts.append(iss)
    return {
        "text": " ".join(parts),
        "target": best[2] if best and best[1] < 25 else None,
        "separation_deg": round(best[1], 1) if best else None,
    }
