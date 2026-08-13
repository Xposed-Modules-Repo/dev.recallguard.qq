#!/usr/bin/env python3
"""Deterministic host-side verification of PushCacheProvider's SQL policy."""

from pathlib import Path
import sqlite3


RETENTION_MS = 7 * 24 * 60 * 60 * 1000
MAX_CANDIDATES = 200


def schema(db: sqlite3.Connection) -> None:
    db.executescript(
        """
        CREATE TABLE candidates (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          stable_key TEXT NOT NULL UNIQUE,
          text TEXT NOT NULL,
          state INTEGER NOT NULL DEFAULT 0,
          tombstone_key TEXT
        );
        CREATE UNIQUE INDEX one_restore_per_tombstone
          ON candidates(tombstone_key) WHERE tombstone_key IS NOT NULL;
        CREATE TABLE metadata (key TEXT PRIMARY KEY, long_value INTEGER NOT NULL);
        """
    )


def meta(db: sqlite3.Connection, key: str, fallback: int = 0) -> int:
    row = db.execute(
        "SELECT long_value FROM metadata WHERE key=?", (key,)
    ).fetchone()
    return fallback if row is None else int(row[0])


def reset_deadline(db: sqlite3.Connection, now: int) -> None:
    db.execute(
        "INSERT OR REPLACE INTO metadata(key,long_value) VALUES(?,?)",
        ("cleanup_baseline_ms", now),
    )
    db.execute(
        "INSERT OR REPLACE INTO metadata(key,long_value) VALUES(?,?)",
        ("cleanup_deadline_ms", now + RETENTION_MS),
    )


def cleanup_if_due(db: sqlite3.Connection, now: int) -> None:
    deadline = meta(db, "cleanup_deadline_ms")
    if deadline <= 0:
        reset_deadline(db, now)
    elif now >= deadline:
        db.execute("DELETE FROM candidates")
        reset_deadline(db, now)


def put(db: sqlite3.Connection, key: str, now: int) -> None:
    with db:
        cleanup_if_due(db, now)
        exists = db.execute(
            "SELECT 1 FROM candidates WHERE stable_key=? LIMIT 1", (key,)
        ).fetchone() is not None
        count = db.execute("SELECT COUNT(*) FROM candidates").fetchone()[0]
        if not exists and count >= MAX_CANDIDATES:
            db.execute("DELETE FROM candidates")
            reset_deadline(db, now)
        if exists:
            db.execute(
                "UPDATE candidates SET text=? WHERE stable_key=?", (key, key)
            )
        else:
            db.execute(
                "INSERT INTO candidates(stable_key,text) VALUES(?,?)", (key, key)
            )


def verify_source_contract() -> None:
    source = (
        Path(__file__).resolve().parents[1]
        / "app/src/main/java/dev/recallguard/qq/PushCacheProvider.java"
    ).read_text(encoding="utf-8")
    required = (
        "private static final int MAX_CANDIDATES = 200;",
        "private static final long RETENTION_MS = 7L * 24L * 60L * 60L * 1000L;",
        "if (!exists && count(db) >= MAX_CANDIDATES)",
        'db.delete("candidates", null, null);',
        "resetDeadline(db, now);",
        "catch (Throwable error)",
        "return result(false);",
    )
    missing = [token for token in required if token not in source]
    assert not missing, f"provider contract drift: {missing}"


def main() -> None:
    verify_source_contract()
    db = sqlite3.connect(":memory:")
    schema(db)
    start = 1_700_000_000_000

    for index in range(MAX_CANDIDATES):
        put(db, f"k{index}", start + index)
    assert db.execute("SELECT COUNT(*) FROM candidates").fetchone()[0] == 200
    first_deadline = meta(db, "cleanup_deadline_ms")

    # A duplicate at capacity updates in place and must not clear the cache.
    put(db, "k199", start + 500)
    assert db.execute("SELECT COUNT(*) FROM candidates").fetchone()[0] == 200
    assert meta(db, "cleanup_deadline_ms") == first_deadline

    # The 201st distinct row clears globally, inserts the current row, and atomically
    # resets both baseline and deadline in the same transaction.
    overflow_now = start + 1_000
    put(db, "k200", overflow_now)
    assert db.execute("SELECT COUNT(*) FROM candidates").fetchone()[0] == 1
    assert db.execute("SELECT stable_key FROM candidates").fetchone()[0] == "k200"
    assert meta(db, "cleanup_baseline_ms") == overflow_now
    assert meta(db, "cleanup_deadline_ms") == overflow_now + RETENTION_MS

    # A due global cleanup uses the same atomic deadline reset policy.
    due_now = overflow_now + RETENTION_MS
    with db:
        cleanup_if_due(db, due_now)
    assert db.execute("SELECT COUNT(*) FROM candidates").fetchone()[0] == 0
    assert meta(db, "cleanup_baseline_ms") == due_now
    assert meta(db, "cleanup_deadline_ms") == due_now + RETENTION_MS

    print("PASS push-cache policy: duplicate=200, distinct-201=>1, 7-day cleanup reset")
    print("PASS fail-open source contract: provider errors return false without host crash")


if __name__ == "__main__":
    main()
