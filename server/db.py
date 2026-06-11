"""SQLite persistence for the GeoPulse companion backend.

Uses only the Python standard library (no ORM). One file, four tables:
locations, trips, visits and driving events. Each row keeps the raw JSON the
SDK emitted plus a few indexed columns for querying.
"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
from pathlib import Path
from typing import Any

# Defaults to a file next to this module; override with GEOPULSE_DB (used by the
# idempotency tests to point at a throwaway database).
DB_PATH = Path(os.environ.get("GEOPULSE_DB", str(Path(__file__).parent / "geopulse.db")))

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

            -- Idempotency: a re-sent batch (e.g. after a lost 2xx response makes
            -- the SDK retry an upload) must not duplicate points. Dedup by
            -- (device, uuid) with a UNIQUE index; insert_location relies on it
            -- via ON CONFLICT. De-dupe any pre-existing rows before adding the
            -- constraint (NULL uuids are kept — SQLite treats them as distinct),
            -- and upgrade the old non-unique index of the same name if present.
            DELETE FROM locations
             WHERE uuid IS NOT NULL
               AND id NOT IN (
                 SELECT MIN(id) FROM locations
                  WHERE uuid IS NOT NULL
                  GROUP BY device, uuid
               );
            DROP INDEX IF EXISTS idx_loc_device_uuid;
            CREATE UNIQUE INDEX IF NOT EXISTS idx_loc_device_uuid
                ON locations(device, uuid);
            """
        )
        _conn.commit()


def _now_ms() -> int:
    return int(time.time() * 1000)


def insert_locations(device: str, locs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Insert a batch of locations in ONE transaction, idempotent by (device, uuid).

    Returns the sub-list that was newly inserted (the caller broadcasts only those).
    The UNIQUE(device, uuid) index makes a re-sent batch a no-op via ON CONFLICT, so
    the SDK can safely retry an upload — e.g. after a slow response trips its read
    timeout — without duplicating points. Rows with a NULL uuid are always inserted
    (SQLite treats NULLs as distinct, so they can't be deduped).

    One commit per batch instead of per point: the per-point commit (a full journal
    fsync each) was the ingestion bottleneck for the SDK's batched uploads.
    """
    if not locs:
        return []
    fresh: list[dict[str, Any]] = []
    received_at = _now_ms()
    with _lock:
        try:
            for loc in locs:
                coords = loc.get("coords") or {}
                cur = _conn.execute(
                    """INSERT INTO locations
                       (device, uuid, timestamp, latitude, longitude, accuracy, speed,
                        provider, is_moving, confidence, received_at, json)
                       VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                       ON CONFLICT(device, uuid) DO NOTHING""",
                    (
                        device,
                        loc.get("uuid"),
                        loc.get("timestamp"),
                        coords.get("latitude"),
                        coords.get("longitude"),
                        coords.get("accuracy"),
                        coords.get("speed"),
                        loc.get("provider"),
                        1 if loc.get("isMoving") else 0,
                        loc.get("confidence"),
                        received_at,
                        json.dumps(loc),
                    ),
                )
                if cur.rowcount > 0:
                    fresh.append(loc)
            _conn.commit()
        except Exception:
            _conn.rollback()
            raise
    return fresh


def insert_location(device: str, loc: dict[str, Any]) -> bool:
    """Single-point convenience over [insert_locations]. True if newly inserted."""
    return bool(insert_locations(device, [loc]))


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
