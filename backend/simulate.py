"""Fake cane: speaks the same WebSocket protocol as firmware/smart_cane, so the dashboard and the
phone app can be tested with no hardware.

    .venv/Scripts/python simulate.py                      # against http://localhost:8000
    .venv/Scripts/python simulate.py https://your-domain

While it runs, type a key + Enter to act like the joystick:
    d = describe   r = read   a = ask   p = repeat   t = auto on/off
    h = hole       f = cane falls (countdown -> SOS after 10 s)   c = cancel   s = SOS now   q = quit
"""

import asyncio
import json
import math
import sys
import time

import websockets

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8000").rstrip("/")
URL = BASE.replace("http", "ws", 1) + "/ws/device?role=cane&token="

KEYS = {"d": "describe", "r": "read", "a": "ask", "p": "repeat", "t": "auto"}


async def main():
    async with websockets.connect(URL) as ws:
        print(f"fake cane connected to {URL}\nkeys: d r a p t | h f c s | q")
        state = {"hole": False, "countdown_at": None, "auto": True}

        async def telemetry():
            t0 = time.time()
            while True:
                d = int(120 + 100 * math.sin((time.time() - t0) / 3))
                await ws.send(json.dumps({"t": "tele", "d": d, "hole": state["hole"], "auto": state["auto"]}))
                if state["countdown_at"] and time.time() - state["countdown_at"] > 10:
                    state["countdown_at"] = None
                    await ws.send(json.dumps({"t": "sos", "reason": "fall: cane down, no response"}))
                    print("-> SOS sent (countdown expired)")
                await asyncio.sleep(0.25)

        async def incoming():
            async for raw in ws:
                msg = json.loads(raw)
                if msg.get("t") == "auto":
                    state["auto"] = msg.get("on", True)
                print("<- from phone:", msg)

        async def keyboard():
            loop = asyncio.get_running_loop()
            while True:
                k = (await loop.run_in_executor(None, sys.stdin.readline)).strip().lower()
                if k == "q":
                    return
                if k in KEYS:
                    await ws.send(json.dumps({"t": "btn", "b": KEYS[k]}))
                elif k == "h":
                    state["hole"] = not state["hole"]
                    await ws.send(json.dumps({"t": "hole", "on": state["hole"]}))
                elif k == "f":
                    state["countdown_at"] = time.time()
                    await ws.send(json.dumps({"t": "fall"}))
                elif k == "c":
                    state["countdown_at"] = None
                    await ws.send(json.dumps({"t": "cancel"}))
                elif k == "s":
                    await ws.send(json.dumps({"t": "sos", "reason": "SOS button held"}))
                print("-> sent", k)

        tasks = [asyncio.create_task(telemetry()), asyncio.create_task(incoming())]
        await keyboard()
        for t in tasks:
            t.cancel()


asyncio.run(main())
