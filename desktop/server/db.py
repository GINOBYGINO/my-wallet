from __future__ import annotations

import shutil
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

from .paths import DEFAULT_BACKUP, WORKING_DB


def ensure_working_db(backup: str | None = None) -> dict[str, Any]:
    WORKING_DB.parent.mkdir(parents=True, exist_ok=True)
    created = False
    if not WORKING_DB.exists():
        src = Path(backup) if backup else DEFAULT_BACKUP
        if not src.exists():
            raise FileNotFoundError(f"找不到備份：{src}")
        shutil.copy2(src, WORKING_DB)
        created = True
    with connect() as conn:
        version = conn.execute("PRAGMA user_version").fetchone()[0]
        wallets = conn.execute("SELECT COUNT(*) FROM wallets").fetchone()[0]
        open_tasks = conn.execute(
            "SELECT COUNT(*) FROM tasks WHERE status='OPEN'"
        ).fetchone()[0]
    return {
        "path": str(WORKING_DB),
        "created": created,
        "user_version": version,
        "wallets": wallets,
        "open_tasks": open_tasks,
    }


@contextmanager
def connect() -> Iterator[sqlite3.Connection]:
    if not WORKING_DB.exists():
        ensure_working_db()
    conn = sqlite3.connect(str(WORKING_DB), timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def rows_to_dicts(rows: list[sqlite3.Row]) -> list[dict[str, Any]]:
    return [dict(r) for r in rows]
