"""Standalone test for the LocationStore buffer-cap SQL (P0-3).

The Kotlin `LocationStore` needs an Android runtime, but its cap logic is plain
SQLite, so we validate the exact statements here against the same engine:

  drop-oldest trim:
    DELETE FROM locations
     WHERE id NOT IN (SELECT id FROM locations ORDER BY id DESC LIMIT :max)
  drop-newest decision:
    skip the insert when COUNT(*) >= :max

Run (stdlib only): python buffer_sql_test.py
Prints PASS/FAIL per check and exits non-zero on failure, like the other host tests.
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


def fresh_db() -> sqlite3.Connection:
    c = sqlite3.connect(":memory:")
    c.execute(
        "CREATE TABLE locations (id INTEGER PRIMARY KEY AUTOINCREMENT, "
        "uuid TEXT, timestamp INTEGER, json TEXT NOT NULL)"
    )
    return c


def insert(c: sqlite3.Connection, i: int) -> None:
    c.execute("INSERT INTO locations (uuid, timestamp, json) VALUES (?,?,?)", (f"u{i}", i, "{}"))


def trim(c: sqlite3.Connection, maxn: int) -> int:
    """Mirror LocationStore's drop-oldest trim; return rows deleted (like db.delete)."""
    cur = c.execute(
        "DELETE FROM locations WHERE id NOT IN "
        "(SELECT id FROM locations ORDER BY id DESC LIMIT ?)",
        (maxn,),
    )
    return cur.rowcount


def count(c: sqlite3.Connection) -> int:
    return c.execute("SELECT COUNT(*) FROM locations").fetchone()[0]


def main() -> int:
    print("== buffer cap SQL tests ==")
    cap = 100

    # drop-oldest: insert 150, trim to 100 -> newest 100 kept, oldest 50 dropped.
    c = fresh_db()
    for i in range(150):
        insert(c, i)
    dropped = trim(c, cap)
    survivors = [r[0] for r in c.execute("SELECT timestamp FROM locations ORDER BY timestamp ASC")]
    check("dropOldest: trimmed to cap", count(c) == cap)
    check("dropOldest: dropped count = 50", dropped == 50)
    check("dropOldest: kept the NEWEST (ts 50..149)", survivors[0] == 50 and survivors[-1] == 149)

    # drop-oldest under cap -> nothing dropped.
    c = fresh_db()
    for i in range(40):
        insert(c, i)
    check("dropOldest: under cap drops 0", trim(c, cap) == 0)
    check("dropOldest: all kept", count(c) == 40)

    # drop-newest: at cap, skip the incoming fix -> oldest kept, newest rejected.
    c = fresh_db()
    for i in range(cap):
        insert(c, i)
    rejected = 0
    for i in range(cap, cap + 20):
        if count(c) >= cap:
            rejected += 1
        else:
            insert(c, i)
    span = c.execute("SELECT MIN(timestamp), MAX(timestamp) FROM locations").fetchone()
    check("dropNewest: stays at cap", count(c) == cap)
    check("dropNewest: rejected 20 incoming", rejected == 20)
    check("dropNewest: kept the OLDEST (ts 0..99)", span == (0, 99))

    print()
    if failures:
        print(f"FAILED ({failures} check(s))")
        return 1
    print("All checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
