"""SQLite persistence for the GeoPulse companion backend.

Uses only the Python standard library (no ORM). One file, four tables:
locations, trips, visits and driving events. Each row keeps the raw JSON the
SDK emitted plus a few indexed columns for querying.
"""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any

DB_PATH = Path(__file__).parent / "geopulse.db"

# A single connection guarded by a lock keeps this dependency-free and safe for
# the modest write rate of a tracking demo.
_lock = threading.Lock()
_conn = sqlite3.connect(DB_PATH, check_same_thread=False)
_conn.row_factory = sqlite3.Row


def init_db() -> None:
    with _lock:
        _conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS locations (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device TEXT NOT NULL,
                uuid TEXT,
                timestamp INTEGER,
                latitude REAL,
                longitude REAL,
                accuracy REAL,
                speed REAL,
                provider TEXT,
                is_moving INTEGER,
                confidence INTEGER,
                received_at INTEGER,
                json TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_loc_device ON locations(device, id);
            CREATE INDEX IF NOT EXISTS idx_loc_device_uuid ON locations(device, uuid);

            CREATE TABLE IF NOT EXISTS trips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device TEXT NOT NULL,
                trip_uuid TEXT,
                action TEXT,
                distance_meters REAL,
                received_at INTEGER,
                json TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS visits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device TEXT NOT NULL,
                visit_uuid TEXT,
                action TEXT,
                latitude REAL,
                longitude REAL,
                received_at INTEGER,
                json TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS driving_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                device TEXT NOT NULL,
                type TEXT,
                severity TEXT,
                magnitude REAL,
                received_at INTEGER,
                json TEXT NOT NULL
            );
            """
        )
        _conn.commit()


def _now_ms() -> int:
    return int(time.time() * 1000)


def insert_location(device: str, loc: dict[str, Any]) -> bool:
    """Insert a location, idempotent by (device, uuid).

    Returns True if a new row was inserted, False if it was a duplicate (so the
    SDK can safely retry an upload — e.g. after a slow response trips its read
    timeout — without duplicating points). The existence check + insert run under
    the same lock, so concurrent requests can't both insert the same uuid.
    """
    coords = loc.get("coords") or {}
    uuid = loc.get("uuid")
    with _lock:
        if uuid is not None:
            existing = _conn.execute(
                "SELECT 1 FROM locations WHERE device = ? AND uuid = ? LIMIT 1",
                (device, uuid),
            ).fetchone()
            if existing is not None:
                return False
        _conn.execute(
            """INSERT INTO locations
               (device, uuid, timestamp, latitude, longitude, accuracy, speed,
                provider, is_moving, confidence, received_at, json)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                device,
                uuid,
                loc.get("timestamp"),
                coords.get("latitude"),
                coords.get("longitude"),
                coords.get("accuracy"),
                coords.get("speed"),
                loc.get("provider"),
                1 if loc.get("isMoving") else 0,
                loc.get("confidence"),
                _now_ms(),
                json.dumps(loc),
            ),
        )
        _conn.commit()
        return True


def insert_trip(device: str, event: dict[str, Any]) -> None:
    trip = event.get("trip") or {}
    with _lock:
        _conn.execute(
            """INSERT INTO trips (device, trip_uuid, action, distance_meters, received_at, json)
               VALUES (?,?,?,?,?,?)""",
            (device, trip.get("uuid"), event.get("action"),
             trip.get("distanceMeters"), _now_ms(), json.dumps(event)),
        )
        _conn.commit()


def insert_visit(device: str, event: dict[str, Any]) -> None:
    visit = event.get("visit") or {}
    with _lock:
        _conn.execute(
            """INSERT INTO visits (device, visit_uuid, action, latitude, longitude, received_at, json)
               VALUES (?,?,?,?,?,?,?)""",
            (device, visit.get("uuid"), event.get("action"),
             visit.get("latitude"), visit.get("longitude"), _now_ms(), json.dumps(event)),
        )
        _conn.commit()


def insert_driving_event(device: str, event: dict[str, Any]) -> None:
    with _lock:
        _conn.execute(
            """INSERT INTO driving_events (device, type, severity, magnitude, received_at, json)
               VALUES (?,?,?,?,?,?)""",
            (device, event.get("type"), event.get("severity"),
             event.get("magnitude"), _now_ms(), json.dumps(event)),
        )
        _conn.commit()


def list_devices() -> list[dict[str, Any]]:
    with _lock:
        rows = _conn.execute(
            """SELECT device,
                      COUNT(*) AS points,
                      MAX(received_at) AS last_seen
               FROM locations GROUP BY device ORDER BY last_seen DESC"""
        ).fetchall()
    return [dict(r) for r in rows]


def get_locations(device: str, limit: int = 1000) -> list[dict[str, Any]]:
    with _lock:
        rows = _conn.execute(
            """SELECT json FROM locations WHERE device = ?
               ORDER BY id DESC LIMIT ?""",
            (device, limit),
        ).fetchall()
    # Return chronological order (oldest first) for drawing the path.
    return [json.loads(r["json"]) for r in reversed(rows)]


def get_recent_events(device: str, limit: int = 50) -> dict[str, list]:
    with _lock:
        trips = _conn.execute(
            "SELECT json FROM trips WHERE device=? ORDER BY id DESC LIMIT ?",
            (device, limit),
        ).fetchall()
        visits = _conn.execute(
            "SELECT json FROM visits WHERE device=? ORDER BY id DESC LIMIT ?",
            (device, limit),
        ).fetchall()
        driving = _conn.execute(
            "SELECT json FROM driving_events WHERE device=? ORDER BY id DESC LIMIT ?",
            (device, limit),
        ).fetchall()
    return {
        "trips": [json.loads(r["json"]) for r in trips],
        "visits": [json.loads(r["json"]) for r in visits],
        "driving": [json.loads(r["json"]) for r in driving],
    }
