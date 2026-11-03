from __future__ import annotations

import json
import time
from typing import Any

from .paths import CHANGELOG_PATH


def append(op: str, payload: dict[str, Any], message: str = "") -> dict[str, Any]:
    CHANGELOG_PATH.parent.mkdir(parents=True, exist_ok=True)
    entry = {
        "ts": int(time.time() * 1000),
        "op": op,
        "payload": payload,
        "message": message,
    }
    with CHANGELOG_PATH.open("a", encoding="utf-8") as f:
        f.write(json.dumps(entry, ensure_ascii=False) + "\n")
    return entry


def read_all() -> list[dict[str, Any]]:
    if not CHANGELOG_PATH.exists():
        return []
    out: list[dict[str, Any]] = []
    with CHANGELOG_PATH.open("r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            out.append(json.loads(line))
    return out


def clear() -> None:
    if CHANGELOG_PATH.exists():
        CHANGELOG_PATH.write_text("", encoding="utf-8")
