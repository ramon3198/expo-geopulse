"""Inject a simulated route into the running backend so the dashboard map moves
without needing a phone. Mirrors the shape the SDK posts to `/locations`.

Usage:
    python simulate.py                # default Home -> Work route
    python simulate.py --device car1  # custom device id
"""
from __future__ import annotations

import argparse
import json
import time
import urllib.request

BASE = "http://127.0.0.1:8787"
DEG_PER_M = 1 / 111_320  # ~metres to degrees latitude


def post(path: str, payload: dict, device: str) -> None:
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        f"{BASE}{path}",
        data=data,
        headers={"Content-Type": "application/json", "x-device-id": device},
        method="POST",
    )
    with urllib.request.urlopen(req) as resp:
        resp.read()


def loc(lat: float, lng: float, speed: float, ts: int) -> dict:
    return {
        "uuid": f"sim-{ts}",
        "timestamp": ts,
        "coords": {"latitude": lat, "longitude": lng, "accuracy": 6.0, "speed": speed},
        "isMoving": speed > 0.5,
        "provider": "simulated",
        "confidence": 90,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--device", default="phone-sim")
    ap.add_argument("--delay", type=float, default=0.8, help="seconds between fixes")
    args = ap.parse_args()
    device = args.device

    home = (13.6929, -89.2182)   # San Salvador
    work = (13.7050, -89.1950)   # ~3 km away
    base = int(time.time() * 1000)

    print(f"Simulating route for device '{device}' -> {BASE}")

    # Dwell at home (a visit), then travel to work, then dwell at work.
    steps: list[tuple[float, float, float]] = []
    for i in range(4):
        steps.append((home[0] + i * 2 * DEG_PER_M, home[1] + i * 2 * DEG_PER_M, 0.0))
    for i in range(1, 21):
        f = i / 20
        steps.append((home[0] + (work[0] - home[0]) * f,
                      home[1] + (work[1] - home[1]) * f, 12.0))
    for i in range(4):
        steps.append((work[0] + i * 2 * DEG_PER_M, work[1] + i * 2 * DEG_PER_M, 0.0))

    for idx, (lat, lng, speed) in enumerate(steps):
        ts = base + idx * 3000
        post("/locations", loc(lat, lng, speed, ts), device)
        print(f"  fix {idx + 1}/{len(steps)}  {lat:.5f},{lng:.5f}  {speed} m/s")

        # Emit visit/trip events at the right moments, mirroring what the SDK does.
        if idx == 3:  # finished dwelling at home -> a visit, then depart + trip start
            post("/events/visit", {
                "action": "arrive",
                "visit": {"uuid": "v-home", "latitude": home[0], "longitude": home[1],
                          "arrivalTime": base, "departureTime": None, "dwellMs": None},
            }, device)
            post("/events/visit", {
                "action": "depart",
                "visit": {"uuid": "v-home", "latitude": home[0], "longitude": home[1],
                          "dwellMs": 1_800_000},
            }, device)
            post("/events/trip", {
                "action": "start",
                "trip": {"uuid": "t1", "distanceMeters": 0},
            }, device)
        if idx == 14:  # harsh braking mid-route
            post("/events/driving", {
                "type": "harsh_braking", "severity": "alert", "magnitude": 5.2, "speed": 9.0,
            }, device)
        if idx == len(steps) - 1:  # arrived at work -> trip end + visit
            post("/events/trip", {
                "action": "end",
                "trip": {"uuid": "t1", "distanceMeters": 2950},
            }, device)
            post("/events/visit", {
                "action": "arrive",
                "visit": {"uuid": "v-work", "latitude": work[0], "longitude": work[1],
                          "arrivalTime": ts, "departureTime": None, "dwellMs": None},
            }, device)

        time.sleep(args.delay)

    print("Done.")


if __name__ == "__main__":
    main()
