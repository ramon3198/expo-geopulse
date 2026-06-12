"""Standalone idempotency tests for the companion backend's SQLite layer.

Proves the P0-1 contract: a re-sent batch (same per-point `uuid`) is stored once,
so a lost `2xx` response that makes the SDK retry can't create duplicate points.

Run from the `server/` directory (stdlib only, no pip install):

    python test_db.py

Mirrors the C++/Kotlin host tests: prints PASS/FAIL per check and exits non-zero
on any failure so CI can gate on it.
"""
from __future__ import annotations

import os
import tempfile

# Point db at a throwaway file BEFORE importing it (db connects at import time).
_tmp = tempfile.mkdtemp(prefix="geopulse-test-")
os.environ["GEOPULSE_DB"] = os.path.join(_tmp, "test.db")

import db  # noqa: E402  (import after GEOPULSE_DB is set)

failures = 0


def check(name: str, cond: bool) -> None:
    global failures
    print(("  [PASS] " if cond else "  [FAIL] ") + name)
    if not cond:
        failures += 1


def main() -> int:
    print("== insert_location idempotency tests ==")
    db.init_db()
    point = {"uuid": "p1", "timestamp": 1, "coords": {"latitude": 1.0, "longitude": 2.0}}

    check("first insert is fresh", db.insert_location("devA", point) is True)
    check("re-sending the same point is a no-op", db.insert_location("devA", point) is False)
    check("stored exactly once", len(db.get_locations("devA")) == 1)

    # A whole batch re-sent (the lost-response retry case) stores nothing new.
    again = [db.insert_location("devA", point) for _ in range(5)]
    check("retrying a batch 5x inserts nothing", not any(again))
    check("still stored exactly once", len(db.get_locations("devA")) == 1)

    # A different uuid is a different point.
    check(
        "a different uuid is a new point",
        db.insert_location("devA", {**point, "uuid": "p2"}) is True,
    )
    check("now two points", len(db.get_locations("devA")) == 2)

    # Dedup is per-device: the same uuid under another device is distinct.
    check("same uuid, other device is distinct", db.insert_location("devB", point) is True)

    # NULL uuid -> always inserted (can't dedup what has no id).
    nouuid = {"timestamp": 9, "coords": {"latitude": 3.0, "longitude": 4.0}}
    check("null-uuid point inserts", db.insert_location("devC", nouuid) is True)
    check("null-uuid point inserts again", db.insert_location("devC", nouuid) is True)
    check("two null-uuid points kept", len(db.get_locations("devC")) == 2)

    # Batch API (one transaction): returns only the freshly-inserted subset.
    batch = [
        {"uuid": "b1", "timestamp": 10, "coords": {"latitude": 1.0, "longitude": 2.0}},
        {"uuid": "b2", "timestamp": 11, "coords": {"latitude": 1.0, "longitude": 2.0}},
        dict(point),  # devA already has uuid p1 -> must be skipped
    ]
    fresh = db.insert_locations("devA", batch)
    check("batch: only new points returned", [p["uuid"] for p in fresh] == ["b1", "b2"])
    check("batch: mixed batch stored the new ones", len(db.get_locations("devA")) == 4)
    check("batch: re-sending the whole batch is a no-op", db.insert_locations("devA", batch) == [])
    check("batch: still 4 points", len(db.get_locations("devA")) == 4)
    check("batch: empty input -> empty", db.insert_locations("devA", []) == [])

    # Sessions: points group per tracking run; pre-session points land in "legacy".
    db.insert_locations(
        "devS",
        [
            {"uuid": "old-a", "timestamp": 0, "coords": {"latitude": 0.0, "longitude": 0.0}},  # no sessionId
            {"uuid": "s1-a", "sessionId": "run-1", "timestamp": 1, "coords": {"latitude": 1.0, "longitude": 1.0}},
            {"uuid": "s1-b", "sessionId": "run-1", "timestamp": 2, "coords": {"latitude": 1.1, "longitude": 1.0}},
            {"uuid": "s2-a", "sessionId": "run-2", "timestamp": 10, "coords": {"latitude": 2.0, "longitude": 1.0}},
        ],
    )
    sess = db.get_sessions("devS")
    check("sessions: grouped per run + legacy", {s["session"] for s in sess} == {"run-1", "run-2", "legacy"})
    check("sessions: newest run first", sess[0]["session"] == "run-2")
    run1 = next(s for s in sess if s["session"] == "run-1")
    check("sessions: per-run point count", run1["points"] == 2)
    check("sessions: time range", run1["start_ts"] == 1 and run1["end_ts"] == 2)
    check(
        "sessions: filter returns only that run",
        [p["uuid"] for p in db.get_locations("devS", session="run-1")] == ["s1-a", "s1-b"],
    )
    check(
        "sessions: 'legacy' selects pre-session points",
        [p["uuid"] for p in db.get_locations("devS", session="legacy")] == ["old-a"],
    )
    check("sessions: no filter returns everything", len(db.get_locations("devS")) == 4)

    print()
    if failures:
        print(f"FAILED ({failures} check(s))")
        return 1
    print("All checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
