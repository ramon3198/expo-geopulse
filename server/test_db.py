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

    print()
    if failures:
        print(f"FAILED ({failures} check(s))")
        return 1
    print("All checks passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
