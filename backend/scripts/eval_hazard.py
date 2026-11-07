"""Run a folder of stage photos through /frame mode=hazard to tune the hazard prompt.

Name files so the expected answer is obvious, e.g. branch_01.jpg (expect hazard) and chair_01.jpg (expect none).

    python scripts/eval_hazard.py photos/ --url http://127.0.0.1:8000 --secret $SHARED_SECRET
"""
import argparse
import pathlib
import time

import httpx

p = argparse.ArgumentParser()
p.add_argument("folder")
p.add_argument("--url", default="http://127.0.0.1:8000")
p.add_argument("--secret", required=True)
a = p.parse_args()

for f in sorted(pathlib.Path(a.folder).glob("*.jp*g")):
    t0 = time.monotonic()
    r = httpx.post(f"{a.url}/frame", headers={"X-Secret": a.secret},
                   files={"image": (f.name, f.read_bytes(), "image/jpeg")}, data={"mode": "hazard"}, timeout=20)
    j = r.json()
    print(f"{f.name:30} hazard={str(j['hazard']):5} say={j['say']!r:28} {int((time.monotonic() - t0) * 1000)} ms")
    time.sleep(4.5)  # stay under free-tier RPM; also note: 10 s cooldown silences repeats of the same clip
