"""Standalone test for the LocationStore schema migration (P1-4).

Mirrors LocationStore.onUpgrade / migrateTo2 in SQLite (the Kotlin store needs an
Android runtime): v1 -> v2 de-dupes by uuid and adds a UNIQUE(uuid) index while
PRESERVING buffered rows, tracks the version via PRAGMA user_version, and — when a
migration fails — rolls back instead of wiping data (SQLiteOpenHelper wraps
onUpgrade in a transaction; we model that here).

Run (stdlib only): python migration_sql_test.py
"""
from __future__ import annotations

import sqlite3
import sys

failures = 0


def check(name: str, cond: bool) -> None:
    global failures
    print(("  [PASS] " if cond else "  [FAIL] ") + name)
    if not cond:
        failures += 1


def v1_db() -> sqlite3.Connection:
    c = sqlite3.connect(":memory:")
    c.isolation_level = None  # manage transactions explicitly (BEGIN/COMMIT/ROLLBACK)
    c.executescript(
        "CREATE TABLE locations (id INTEGER PRIMARY KEY AUTOINCREMENT, "
        "uuid TEXT, timestamp INTEGER, json TEXT NOT NULL);"
        "CREATE INDEX idx_locations_id ON locations(id);"
        "PRAGMA user_version = 1;"
    )
    return c


def insert(c: sqlite3.Connection, uuid: str, ts: int) -> None:
    c.execute("INSERT INTO locations (uuid, timestamp, json) VALUES (?,?,?)", (uuid, ts, "{}"))


# mirrors LocationStore.migrateTo2
DEDUP = (
    "DELETE FROM locations WHERE uuid IS NOT NULL AND uuid <> '' AND id NOT IN "
    "(SELECT MAX(id) FROM locations WHERE uuid IS NOT NULL AND uuid <> '' GROUP BY uuid)"
)
CREATE_UNIQUE = "CREATE UNIQUE INDEX IF NOT EXISTS idx_locations_uuid ON locations(uuid)"


def migrate_to_2(c: sqlite3.Connection) -> None:
    c.execute("BEGIN")
    try:
        c.execute(DEDUP)
        c.execute(CREATE_UNIQUE)
        c.execute("PRAGMA user_version = 2")
        c.execute("COMMIT")
    except Exception:
        c.execute("ROLLBACK")
        raise


def user_version(c: sqlite3.Connection) -> int:
    return c.execute("PRAGMA user_version").fetchone()[0]


def count(c: sqlite3.Connection) -> int:
    return c.execute("SELECT COUNT(*) FROM locations").fetchone()[0]


def has_unique_uuid_index(c: sqlite3.Connection) -> bool:
    row = c.execute(
        "SELECT sql FROM sqlite_master WHERE type='index' AND name='idx_locations_uuid'"
    ).fetchone()
    return bool(row and "UNIQUE" in row[0].upper())


def main() -> int:
    print("== schema migration tests ==")

    # Happy path: v1 with 3 distinct + 1 duplicate uuid -> v2 keeps newest per uuid.
    c = v1_db()
    insert(c, "a", 1)
    insert(c, "b", 2)
    insert(c, "a", 3)  # 'a' duplicated
    insert(c, "c", 4)
    check("starts at user_version 1", user_version(c) == 1)
    before = count(c)
    migrate_to_2(c)
    check("migrated to user_version 2", user_version(c) == 2)
    check("buffered rows preserved (dup collapsed)", before == 4 and count(c) == 3)
    check("unique uuid index created", has_unique_uuid_index(c))
    kept_a_ts = c.execute("SELECT timestamp FROM locations WHERE uuid='a'").fetchone()[0]
    check("kept the NEWEST duplicate (ts 3)", kept_a_ts == 3)
    check("rows still selectable (syncable)", len(c.execute("SELECT json FROM locations").fetchall()) == 3)

    # Idempotent insert after migration (INSERT OR IGNORE on uuid).
    c.execute("INSERT OR IGNORE INTO locations (uuid, timestamp, json) VALUES ('a', 9, '{}')")
    check("re-inserting an existing uuid is ignored", count(c) == 3)
    c.execute("INSERT OR IGNORE INTO locations (uuid, timestamp, json) VALUES ('d', 9, '{}')")
    check("a new uuid still inserts", count(c) == 4)

    # Failure path: a migration that fails (unique index over un-deduped dups) must
    # ROLL BACK — data intact, version unchanged (never silently wiped).
    c = v1_db()
    insert(c, "x", 1)
    insert(c, "x", 2)  # duplicate the (here-skipped) dedup would have removed
    failed = False
    try:
        c.execute("BEGIN")
        c.execute(CREATE_UNIQUE)  # raises: duplicate 'x'
        c.execute("PRAGMA user_version = 2")
        c.execute("COMMIT")
    except Exception:
        c.execute("ROLLBACK")
        failed = True
    check("failing migration raised", failed)
    check("failed migration did NOT wipe data", count(c) == 2)
    check("failed migration left version at 1", user_version(c) == 1)

    print()
    if failures:
        print(f"FAILED ({failures} check(s))")
        return 1
    print("All checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
