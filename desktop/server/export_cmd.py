from __future__ import annotations

import json
from typing import Any

from .changelog import read_all
from .paths import SYNC_CMD_PATH


def entry_to_command(entry: dict[str, Any]) -> dict[str, Any] | None:
    op = entry.get("op")
    p = entry.get("payload") or {}
    if op == "wallet.transfer":
        return {
            "op": "wallet.transfer",
            "from": p["from"],
            "to": p["to"],
            "yuan": p["yuan"],
            "note": p.get("note") or "",
        }
    if op == "task.complete":
        cmd: dict[str, Any] = {"op": "task.complete"}
        if p.get("id") is not None:
            cmd["id"] = p["id"]
        if p.get("title"):
            cmd["title"] = p["title"]
        return cmd
    if op == "task.fail":
        cmd = {"op": "task.fail"}
        if p.get("id") is not None:
            cmd["id"] = p["id"]
        if p.get("title"):
            cmd["title"] = p["title"]
        return cmd
    if op == "task.create":
        cmd = {
            "op": "task.create",
            "title": p["title"],
            "category": p.get("category") or "其他",
            "ticketLevel": p.get("ticketLevel") or 2,
        }
        if p.get("note"):
            cmd["note"] = p["note"]
        if p.get("reward"):
            cmd["reward"] = p["reward"]
        if p.get("penalty"):
            cmd["penalty"] = p["penalty"]
        if p.get("repeatable"):
            cmd["repeatable"] = True
            if p.get("repeat"):
                cmd["repeat"] = p["repeat"]
        return cmd
    if op == "focus.settle":
        return {
            "op": "focus.settle",
            "minutes": p.get("minutes") or 0,
            "cashYuan": p.get("cashYuan"),
            "grantTickets": p.get("grantTickets", True),
            "intent": p.get("intent") or "PC暫代",
            "category": p.get("category") or "其他",
        }
    if op == "ticket.add":
        return {
            "op": "ticket.add",
            "kind": p.get("kind") or "SLOT",
            "n": p.get("n") or 1,
        }
    if op == "note":
        return {"op": "note", "text": p.get("text") or entry.get("message") or ""}
    if op == "exam.save_course":
        return {
            "op": "exam.save_course",
            "subject": p.get("subject"),
            "note": p.get("note") or "",
            "units": p.get("units") or [],
        }
    if op == "exam.complete":
        cmd = {"op": "exam.complete"}
        if p.get("id") is not None:
            cmd["id"] = p["id"]
        if p.get("subject"):
            cmd["subject"] = p["subject"]
        if p.get("title"):
            cmd["title"] = p["title"]
        return cmd
    if op == "exam.enqueue":
        cmd = {"op": "exam.enqueue"}
        if p.get("id") is not None:
            cmd["id"] = p["id"]
        if p.get("subject"):
            cmd["subject"] = p["subject"]
        if p.get("title"):
            cmd["title"] = p["title"]
        return cmd
    if op == "exam.dequeue":
        cmd = {"op": "exam.dequeue"}
        if p.get("id") is not None:
            cmd["id"] = p["id"]
        if p.get("subject"):
            cmd["subject"] = p["subject"]
        if p.get("title"):
            cmd["title"] = p["title"]
        return cmd
    if op == "exam.delete_item":
        cmd = {"op": "exam.delete_item"}
        if p.get("id") is not None:
            cmd["id"] = p["id"]
        if p.get("subject"):
            cmd["subject"] = p["subject"]
        if p.get("title"):
            cmd["title"] = p["title"]
        return cmd
    if op == "loan.borrow":
        return {
            "op": "loan.borrow",
            "lender": p["lender"],
            "borrower": p["borrower"],
            "yuan": p["yuan"],
            "dueThisMonth": bool(p.get("dueThisMonth")),
        }
    if op == "loan.repay":
        return {"op": "loan.repay", "id": p["id"], "yuan": p["yuan"]}
    if op == "loan.extend":
        return {"op": "loan.extend", "id": p["id"]}
    if op == "wallet.rate":
        return {
            "op": "wallet.rate",
            "walletId": p.get("walletId") or p.get("id"),
            "percent": p["percent"],
        }
    if op == "wallet.confirm_physical":
        cmd = {"op": "wallet.confirm_physical"}
        if p.get("netZero"):
            cmd["yuan"] = 0
        elif p.get("yuan") is not None:
            cmd["yuan"] = p["yuan"]
        return cmd
    return None


def build_sync_plan(note: str | None = None) -> dict[str, Any]:
    commands: list[dict[str, Any]] = []
    skipped = 0
    for entry in read_all():
        cmd = entry_to_command(entry)
        if cmd is None:
            skipped += 1
            continue
        commands.append(cmd)
    plan = {
        "schema": "mywallet-cmd/v1",
        "mode": "apply",
        "note": note
        or f"PC 暫代匯回（{len(commands)} 筆指令"
        + (f"，略過 {skipped}" if skipped else "")
        + "）",
        "commands": commands,
    }
    return plan


def export_sync(note: str | None = None) -> dict[str, Any]:
    plan = build_sync_plan(note)
    if not plan["commands"]:
        raise ValueError("changelog 是空的，沒有可匯出的指令")
    SYNC_CMD_PATH.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(plan, ensure_ascii=False, indent=2)
    SYNC_CMD_PATH.write_text(text, encoding="utf-8")
    fenced = f"```mywallet-cmd\n{text}\n```"
    return {
        "path": str(SYNC_CMD_PATH),
        "count": len(plan["commands"]),
        "plan": plan,
        "fenced": fenced,
    }
