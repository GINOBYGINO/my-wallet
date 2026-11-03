from __future__ import annotations

from pathlib import Path

DESKTOP_ROOT = Path(__file__).resolve().parent.parent
DATA_DIR = DESKTOP_ROOT / "data"
STATIC_DIR = DESKTOP_ROOT / "static"
WORKING_DB = DATA_DIR / "working.db"
CHANGELOG_PATH = DATA_DIR / "changelog.jsonl"
SYNC_CMD_PATH = DATA_DIR / "sync.mywallet-cmd.json"

DEFAULT_BACKUP = Path(
    r"C:\Users\Gino\Documents\mywallet-backup\20261015-212057\user10\my_wallet_merged.db"
)
