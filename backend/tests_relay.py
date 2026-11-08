"""Tests the cane <-> phone relay against a REAL server (TestClient can't relay between sockets).

    .venv/Scripts/python -m uvicorn app.main:app --port 8765     (in another terminal)
    .venv/Scripts/python tests_relay.py [http://localhost:8765]
"""
import asyncio
import json
import sys

import httpx
import websockets

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8765").rstrip("/")
WS = BASE.replace("http", "ws", 1)


async def recv_until(ws, pred, timeout=3):
    while True:
        msg = json.loads(await asyncio.wait_for(ws.recv(), timeout))
        if pred(msg):
            return msg


async def main():
    async with websockets.connect(f"{WS}/ws") as dash, websockets.connect(f"{WS}/ws/device?role=phone") as phone, websockets.connect(f"{WS}/ws/device?role=cane") as cane:
        await recv_until(phone, lambda m: m.get("t") == "status" and m.get("cane"))
        print("phone sees cane connected")
        await phone.send(json.dumps({"t": "loc", "lat": 43.47, "lon": -80.54}))
        await cane.send(json.dumps({"t": "tele", "d": 60, "hole": False, "auto": True}))
        print("dashboard live:", (await recv_until(dash, lambda m: m.get("type") == "live" and "distance_cm" in m["data"]))["data"])
        await cane.send(json.dumps({"t": "btn", "b": "describe"}))
        print("phone got:", await recv_until(phone, lambda m: m.get("t") == "btn"))
        await cane.send(json.dumps({"t": "sos", "reason": "fall"}))
        print("phone got:", await recv_until(phone, lambda m: m.get("t") == "sos"))
        print("dashboard got:", (await recv_until(dash, lambda m: m.get("type") == "event" and m["data"]["kind"] == "sos"))["data"]["label"])
        await phone.send(json.dumps({"t": "find"}))
        print("cane got:", await recv_until(cane, lambda m: True))
    async with httpx.AsyncClient() as http:
        print("sighting:", (await http.post(f"{BASE}/api/sightings", json={"description": "backpack on your left", "objects": [{"label": "backpack", "position": "left"}]})).json())
        r = (await http.post(f"{BASE}/api/memory/ask", json={"question": "where is my backpack"})).json()
        print("memory matches:", r["matches"])
        kinds = [e["kind"] for e in (await http.get(f"{BASE}/api/events")).json()]
        print("events:", kinds)


asyncio.run(main())
