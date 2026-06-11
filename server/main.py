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
import gzip
import json
from typing import Any

from pathlib import Path

from fastapi import BackgroundTasks, FastAPI, Request, WebSocket, WebSocketDisconnect
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
        # Snapshot the clients under the lock, then send OUTSIDE it and in
        # parallel: holding the lock across `await send_text` lets one slow client
        # stall ingestion (/locations awaits this) and connect/disconnect.
        async with self._lock:
            clients = list(self._clients)
        if not clients:
            return
        results = await asyncio.gather(
            *(ws.send_text(data) for ws in clients),
            return_exceptions=True,
        )
        dead = [ws for ws, r in zip(clients, results) if isinstance(r, Exception)]
        if dead:
            async with self._lock:
                for ws in dead:
                    self._clients.discard(ws)


manager = ConnectionManager()


async def _broadcast_locations(device: str, locations: list[dict[str, Any]]) -> None:
    """Push locations to dashboards. Runs as a background task after the response."""
    for loc in locations:
        await manager.broadcast({"type": "location", "device": device, "location": loc})


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
async def ingest_locations(request: Request, background_tasks: BackgroundTasks) -> JSONResponse:
    """Ingest endpoint the SDK posts to.

    Accepts either a bare array of locations (what GeoPulse sends) or an object
    of the form `{ "device": "...", "location": {...} }` (what the simulate
    script / single-event posters send). The device id falls back to a header or
    "default".
    """
    # Read the raw body so we can transparently decode gzip (the SDK gzips
    # batches above ~256 bytes to save data/battery).
    raw = await request.body()
    if "gzip" in request.headers.get("content-encoding", "").lower():
        raw = gzip.decompress(raw)
    body = json.loads(raw or b"null")
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

    # Persist everything first (durable, fast, idempotent by uuid), then broadcast
    # AFTER responding (FastAPI background task). The SDK gets its 200 immediately,
    # so a slow dashboard can't push the response past the uploader's read timeout
    # and trigger a retry — and even if it does retry, insert_locations dedupes by
    # (device, uuid). Only newly-inserted points are broadcast. The whole batch is
    # one transaction (one fsync), not one commit per point.
    fresh = db.insert_locations(device, locations)
    if fresh:
        background_tasks.add_task(_broadcast_locations, device, fresh)

    return JSONResponse({"received": len(locations), "stored": len(fresh)})


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
