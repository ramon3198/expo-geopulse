"""GeoPulse companion backend — FastAPI + WebSocket.

Receives the batches `expo-geopulse` POSTs to its configured `url`, stores them
in SQLite, and broadcasts every new location/event to connected dashboards over
a WebSocket so the map updates live.

Run:
    pip install -r requirements.txt
    uvicorn main:app --host 0.0.0.0 --port 8787 --reload
"""
from __future__ import annotations

import asyncio
import json
from typing import Any

from pathlib import Path

from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse

import db

STATIC_DIR = Path(__file__).parent / "static"

app = FastAPI(title="GeoPulse Backend", version="0.1.0")

# The dashboard runs on a different origin (Vite dev server), so allow CORS.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


class ConnectionManager:
    """Tracks connected dashboard WebSockets and fans out messages to them."""

    def __init__(self) -> None:
        self._clients: set[WebSocket] = set()
        self._lock = asyncio.Lock()

    async def connect(self, ws: WebSocket) -> None:
        await ws.accept()
        async with self._lock:
            self._clients.add(ws)

    async def disconnect(self, ws: WebSocket) -> None:
        async with self._lock:
            self._clients.discard(ws)

    async def broadcast(self, message: dict[str, Any]) -> None:
        data = json.dumps(message)
        async with self._lock:
            dead = []
            for ws in self._clients:
                try:
                    await ws.send_text(data)
                except Exception:
                    dead.append(ws)
            for ws in dead:
                self._clients.discard(ws)


manager = ConnectionManager()


@app.on_event("startup")
def _startup() -> None:
    db.init_db()


@app.get("/health")
def health() -> dict[str, Any]:
    return {"ok": True, "service": "geopulse-backend"}


@app.get("/app.apk")
def download_apk() -> FileResponse:
    """Serve the release APK so a phone can install it from its browser (no cable)."""
    apk = STATIC_DIR / "geopulse.apk"
    return FileResponse(
        apk,
        media_type="application/vnd.android.package-archive",
        filename="geopulse.apk",
    )


@app.get("/install", response_class=HTMLResponse)
def install_page() -> str:
    """A tiny landing page with a download button for the APK."""
    return """<!doctype html><html><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Install GeoPulse</title>
    <style>
      body{font-family:system-ui;background:#0b1220;color:#e6edf3;
           display:flex;min-height:100vh;align-items:center;justify-content:center;margin:0}
      .card{text-align:center;padding:32px}
      h1{font-size:28px;margin:0 0 8px}
      p{color:#9fb3c8}
      a.btn{display:inline-block;margin-top:20px;background:#3b82f6;color:#fff;
            text-decoration:none;padding:14px 28px;border-radius:12px;font-weight:700;font-size:18px}
    </style></head><body><div class="card">
      <h1>GeoPulse</h1>
      <p>Tap to download and install the app.<br>You may need to allow installs from this browser.</p>
      <a class="btn" href="/app.apk">Download APK</a>
    </div></body></html>"""


@app.post("/locations")
async def ingest_locations(request: Request) -> JSONResponse:
    """Ingest endpoint the SDK posts to.

    Accepts either a bare array of locations (what GeoPulse sends) or an object
    of the form `{ "device": "...", "location": {...} }` (what the simulate
    script / single-event posters send). The device id falls back to a header or
    "default".
    """
    body = await request.json()
    device = request.headers.get("x-device-id", "default")

    locations: list[dict[str, Any]] = []
    if isinstance(body, list):
        locations = body
    elif isinstance(body, dict):
        device = body.get("device", device)
        if "location" in body:
            locations = [body["location"]]
        elif "locations" in body:
            locations = body["locations"]
        elif "coords" in body:
            locations = [body]

    for loc in locations:
        db.insert_location(device, loc)
        await manager.broadcast({"type": "location", "device": device, "location": loc})

    return JSONResponse({"received": len(locations)})


@app.post("/events/{kind}")
async def ingest_event(kind: str, request: Request) -> JSONResponse:
    """Optional endpoint for pushing trip / visit / driving events.

    kind is one of: trip, visit, driving.
    """
    body = await request.json()
    device = request.headers.get("x-device-id", body.get("device", "default"))

    if kind == "trip":
        db.insert_trip(device, body)
    elif kind == "visit":
        db.insert_visit(device, body)
    elif kind == "driving":
        db.insert_driving_event(device, body)
    else:
        return JSONResponse({"error": f"unknown kind {kind}"}, status_code=400)

    await manager.broadcast({"type": kind, "device": device, "event": body})
    return JSONResponse({"ok": True})


@app.get("/devices")
def devices() -> list[dict[str, Any]]:
    return db.list_devices()


@app.get("/locations/{device}")
def locations(device: str, limit: int = 1000) -> list[dict[str, Any]]:
    return db.get_locations(device, limit)


@app.get("/events/{device}")
def events(device: str) -> dict[str, list]:
    return db.get_recent_events(device)


@app.websocket("/ws")
async def websocket_endpoint(ws: WebSocket) -> None:
    await manager.connect(ws)
    try:
        while True:
            # We don't expect inbound messages; this keeps the socket open.
            await ws.receive_text()
    except WebSocketDisconnect:
        await manager.disconnect(ws)
    except Exception:
        await manager.disconnect(ws)
