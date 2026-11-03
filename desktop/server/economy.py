from __future__ import annotations

import json
import random
import sqlite3
import time
from typing import Any

from . import changelog

# 對齊 WalletCodes
CENTRAL = "central"
CENTRAL_FUTURE = "central_future"
LIVING = "living"
CARD = "card"
INVEST = "invest"
REAL = "real"

PIGGY_EXCLUDE = {LIVING, CARD, INVEST, REAL, CENTRAL, CENTRAL_FUTURE}

TICKET_COLS = {
    "SLOT": "tickets",
    "BJ": "bjTickets",
    "SCRATCH": "scratchTickets",
    "DRAW": "drawTickets",
    "LOTTO": "lottoTickets",
    "MATCH": "matchTickets",
    "MATH": "mathTickets",
}

EV_YUAN = {
    "SLOT": 10,
    "BJ": 5,
    "SCRATCH": 20,
    "DRAW": 10,
    "LOTTO": 10,
    "MATCH": 10,
    "MATH": 12,
}

MIX_WEIGHT = {
    "SLOT": 2,
    "BJ": 5,
    "SCRATCH": 3,
    "DRAW": 3,
    "LOTTO": 2,
    "MATCH": 2,
    "MATH": 2,
}

# FocusRules
START_CASH_YUAN = -50
FIXED_CASH_UNTIL_MINUTES = 30
CASH_EVERY_MINUTES = 4
CASH_PER_STEP_FIXED = 8
CASH_PER_STEP_MIN = 10
CASH_PER_STEP_MAX = 12
MAX_REWARD_MINUTES = 120
TICKET_EVERY_MINUTES = 30
EV_PER_LEVEL_YUAN = 10
PASS_LENGTH = 100


def is_piggy(wallet_id: str) -> bool:
    return wallet_id not in PIGGY_EXCLUDE


def should_defer(from_id: str, to_id: str) -> bool:
    return (is_piggy(from_id) and to_id == LIVING) or (
        from_id == LIVING and is_piggy(to_id)
    )


def now_ms() -> int:
    return int(time.time() * 1000)


def yuan_to_cents(yuan: float | int) -> int:
    return int(round(float(yuan) * 100))


def expand_ev_budget(yuan: int, rng: random.Random | None = None) -> dict[str, int]:
    rng = rng or random.Random()
    remaining = max(0, int(yuan))
    out = {k: 0 for k in EV_YUAN}
    min_ev = min(EV_YUAN.values())
    while remaining >= min_ev:
        candidates = [k for k, ev in EV_YUAN.items() if ev <= remaining]
        if not candidates:
            break
        total_w = sum(MIX_WEIGHT[k] for k in candidates)
        r = rng.randrange(total_w)
        chosen = candidates[0]
        for k in candidates:
            r -= MIX_WEIGHT[k]
            if r < 0:
                chosen = k
                break
        out[chosen] += 1
        remaining -= EV_YUAN[chosen]
    return {k: v for k, v in out.items() if v > 0}


def merge_ticket_maps(*maps: dict[str, int]) -> dict[str, int]:
    out: dict[str, int] = {}
    for m in maps:
        for k, n in m.items():
            if n > 0:
                out[k] = out.get(k, 0) + n
    return {k: v for k, v in out.items() if v > 0}


def reward_minutes(effective_ms: int) -> int:
    return max(0, min(MAX_REWARD_MINUTES, int(effective_ms // 60_000)))


def cash_step_count(effective_ms: int) -> int:
    return reward_minutes(effective_ms) // CASH_EVERY_MINUTES


def roll_cash_step(step_index: int, rng: random.Random | None = None) -> int:
    rng = rng or random.Random()
    minute = (step_index + 1) * CASH_EVERY_MINUTES
    if minute <= FIXED_CASH_UNTIL_MINUTES:
        return CASH_PER_STEP_FIXED
    return rng.randint(CASH_PER_STEP_MIN, CASH_PER_STEP_MAX)


def long_cash_yuan(effective_ms: int, rng: random.Random | None = None) -> int:
    rng = rng or random.Random()
    steps = [roll_cash_step(i, rng) for i in range(cash_step_count(effective_ms))]
    return START_CASH_YUAN + sum(steps)


def ticket_units(effective_ms: int) -> int:
    return reward_minutes(effective_ms) // TICKET_EVERY_MINUTES


def guaranteed_tickets(effective_ms: int) -> dict[str, int]:
    m = reward_minutes(effective_ms)
    out: dict[str, int] = {}
    bj = m // TICKET_EVERY_MINUTES
    if bj > 0:
        out["BJ"] = bj
    if m >= 60:
        out["DRAW"] = 1
    if m >= 90:
        out["SCRATCH"] = out.get("SCRATCH", 0) + 1
    if m >= 120:
        out["SCRATCH"] = out.get("SCRATCH", 0) + 1
    return out


def ensure_lottery_row(conn: sqlite3.Connection) -> None:
    row = conn.execute("SELECT id FROM lottery_state WHERE id=1").fetchone()
    if row is None:
        conn.execute("INSERT INTO lottery_state (id, tickets) VALUES (1, 0)")


def add_tickets(conn: sqlite3.Connection, kind: str, n: int) -> None:
    if n <= 0:
        return
    kind = kind.upper()
    col = TICKET_COLS.get(kind)
    if col is None:
        raise ValueError(f"未知券種：{kind}")
    ensure_lottery_row(conn)
    conn.execute(
        f"UPDATE lottery_state SET [{col}] = [{col}] + ? WHERE id=1",
        (n,),
    )


def grant_ticket_map(conn: sqlite3.Connection, pack: dict[str, int]) -> str:
    parts = []
    symbols = {
        "SLOT": "🎰",
        "BJ": "🃏",
        "SCRATCH": "🎫",
        "DRAW": "🎴",
        "LOTTO": "🎟",
        "MATCH": "🍬",
        "MATH": "🧮",
    }
    for kind, n in pack.items():
        add_tickets(conn, kind, n)
        parts.append(f"{symbols.get(kind, kind)}×{n}")
    return " ".join(parts)


def adjust_balance(conn: sqlite3.Connection, wallet_id: str, delta: int) -> None:
    conn.execute(
        "UPDATE wallets SET balanceCents = balanceCents + ? WHERE id=?",
        (delta, wallet_id),
    )


def get_wallet(conn: sqlite3.Connection, wallet_id: str) -> sqlite3.Row:
    row = conn.execute("SELECT * FROM wallets WHERE id=?", (wallet_id,)).fetchone()
    if row is None:
        raise ValueError(f"找不到錢包：{wallet_id}")
    return row


def insert_tx(
    conn: sqlite3.Connection,
    type_: str,
    amount_cents: int,
    from_id: str | None,
    to_id: str | None,
    note: str,
) -> None:
    conn.execute(
        """
        INSERT INTO transactions (type, amountCents, fromWalletId, toWalletId, note, createdAt)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        (type_, amount_cents, from_id, to_id, note, now_ms()),
    )


def record_physical(
    conn: sqlite3.Connection,
    from_id: str,
    to_id: str,
    amount_cents: int,
    title: str,
) -> bool:
    if amount_cents <= 0:
        return False
    conn.execute(
        """
        INSERT INTO pending_physical_transfers
          (amountCents, fromWalletId, toWalletId, taskTitle, createdAt, confirmedAt)
        VALUES (?, ?, ?, ?, ?, NULL)
        """,
        (amount_cents, from_id, to_id, title or "待轉移", now_ms()),
    )
    return True


def pay_from_central(
    conn: sqlite3.Connection,
    to_id: str,
    amount_cents: int,
    note: str,
    tx_type: str,
) -> None:
    if amount_cents <= 0:
        return
    if to_id == LIVING:
        record_physical(conn, CENTRAL, LIVING, amount_cents, note)
        return
    central = get_wallet(conn, CENTRAL)
    adjust_balance(conn, CENTRAL, -amount_cents)
    adjust_balance(conn, to_id, amount_cents)
    hole_note = note
    if central["balanceCents"] - amount_cents < 0:
        hole_note = f"{note}（透支）"
    insert_tx(conn, tx_type, amount_cents, CENTRAL, to_id, hole_note)


def parse_rewards(raw: str) -> list[dict[str, Any]]:
    if not raw or not raw.strip():
        return []
    try:
        data = json.loads(raw)
        return data if isinstance(data, list) else []
    except json.JSONDecodeError:
        return []


def apply_rewards(
    conn: sqlite3.Connection,
    items: list[dict[str, Any]],
    allowed_central_out: bool = True,
) -> None:
    for item in items:
        kind = item.get("kind") or ""
        if kind == "TRANSFER":
            amount = item.get("amountCents")
            if amount is None:
                continue
            amount = int(amount)
            from_id = item.get("fromWalletId") or CENTRAL
            to_id = item.get("toWalletId")
            if not to_id:
                continue
            if from_id == CENTRAL and not allowed_central_out:
                raise ValueError("此獎懲不可從中央轉出")
            if from_id == CENTRAL:
                pay_from_central(conn, to_id, amount, "獎懲轉帳", "REWARD_TRANSFER")
            elif should_defer(from_id, to_id):
                record_physical(conn, from_id, to_id, amount, "獎懲轉帳")
            else:
                src = get_wallet(conn, from_id)
                if src["balanceCents"] < amount:
                    continue
                adjust_balance(conn, from_id, -amount)
                adjust_balance(conn, to_id, amount)
                insert_tx(conn, "REWARD_TRANSFER", amount, from_id, to_id, "獎懲轉帳")
        elif kind == "PRIZE":
            amount = item.get("amountCents")
            if amount is None:
                continue
            if not allowed_central_out:
                raise ValueError("此獎懲不可從中央轉出")
            # 暫代：動態獎金先入現金待轉移
            pay_from_central(conn, LIVING, int(amount), "動態獎金", "REWARD_TRANSFER")
        elif kind == "LOTTERY_TICKET":
            add_tickets(conn, "SLOT", int(item.get("tickets") or 1))
        elif kind.startswith("TICKET_") and kind != "TICKET_LEVEL" and kind != "TICKET_BUDGET":
            ticket_kind = kind.replace("TICKET_", "")
            add_tickets(conn, ticket_kind, int(item.get("tickets") or 1))
        elif kind == "TICKET_BUDGET":
            yuan = max(0, int((item.get("amountCents") or 0) // 100))
            grant_ticket_map(conn, expand_ev_budget(yuan))
        elif kind == "TICKET_LEVEL":
            level = max(1, min(9, int(item.get("level") or 1)))
            grant_ticket_map(conn, expand_ev_budget(level * EV_PER_LEVEL_YUAN))
        elif kind == "TAX_RELIEF":
            p = item.get("reliefPercent")
            if p is None:
                continue
            row = conn.execute(
                "SELECT value FROM app_meta WHERE key='charity_relief_percent'"
            ).fetchone()
            cur = int(row["value"]) if row else 0
            conn.execute(
                """
                INSERT INTO app_meta (key, value) VALUES ('charity_relief_percent', ?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value
                """,
                (str(cur + int(p)),),
            )


def advance_pass(conn: sqlite3.Connection) -> dict[str, Any] | None:
    season = conn.execute(
        "SELECT * FROM pass_seasons WHERE active=1 ORDER BY id DESC LIMIT 1"
    ).fetchone()
    if season is None:
        return None
    progress = int(season["progress"])
    if progress >= PASS_LENGTH:
        return None
    next_index = progress + 1
    conn.execute(
        "UPDATE pass_seasons SET progress=? WHERE id=?",
        (min(next_index, PASS_LENGTH), season["id"]),
    )
    tier = conn.execute(
        "SELECT * FROM pass_tiers WHERE seasonId=? AND tierIndex=?",
        (season["id"], next_index),
    ).fetchone()
    label = tier["label"] if tier else "—"
    return {"tierIndex": next_index, "label": label}


def parse_repeat_rule(raw: str) -> dict[str, Any]:
    if not raw:
        return {"freq": "daily", "n": None, "nextAt": 0}
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return {"freq": "daily", "n": None, "nextAt": 0}


def next_repeat_at(rule: dict[str, Any], from_ms: int) -> int:
    from datetime import datetime, timedelta
    from zoneinfo import ZoneInfo

    tz = ZoneInfo("Asia/Taipei")
    base = datetime.fromtimestamp(from_ms / 1000, tz)
    freq = rule.get("freq") or "daily"
    if freq == "weekly":
        nxt = base + timedelta(weeks=1)
    elif freq == "every_n":
        nxt = base + timedelta(days=max(1, int(rule.get("n") or 1)))
    else:
        nxt = base + timedelta(days=1)
    return int(nxt.timestamp() * 1000)


def transfer(
    conn: sqlite3.Connection,
    from_id: str,
    to_id: str,
    amount_cents: int,
    note: str = "",
) -> dict[str, Any]:
    if amount_cents <= 0 or from_id == to_id:
        raise ValueError("金額須 > 0 且來源／目標不同")
    if from_id == CENTRAL_FUTURE or to_id == CENTRAL_FUTURE:
        raise ValueError("未來中央帳戶僅供延後入帳")
    src = get_wallet(conn, from_id)
    if src["isCentral"]:
        raise ValueError("中央帳戶不可提款／一般轉出")
    if src["balanceCents"] < amount_cents:
        raise ValueError("餘額不足")
    deferred = False
    if should_defer(from_id, to_id):
        record_physical(conn, from_id, to_id, amount_cents, note or "轉帳")
        deferred = True
    else:
        adjust_balance(conn, from_id, -amount_cents)
        adjust_balance(conn, to_id, amount_cents)
        insert_tx(conn, "TRANSFER", amount_cents, from_id, to_id, note)
    msg = "已加入待轉移" if deferred else "轉帳完成"
    entry = changelog.append(
        "wallet.transfer",
        {
            "from": from_id,
            "to": to_id,
            "yuan": amount_cents / 100,
            "note": note,
        },
        msg,
    )
    return {"deferred": deferred, "message": msg, "changelog": entry}


def _get_open_task(conn: sqlite3.Connection, task_id: int) -> sqlite3.Row:
    task = conn.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()
    if task is None:
        raise ValueError("任務不存在")
    if task["status"] != "OPEN":
        raise ValueError("任務不是進行中")
    t = now_ms()
    if task["appearAt"] is not None and task["appearAt"] > t:
        raise ValueError("任務尚未到出現時間")
    if task["repeatable"]:
        rule = parse_repeat_rule(task["repeatRuleJson"] or "")
        if int(rule.get("nextAt") or 0) > t:
            raise ValueError("下次可執行前不可再領取")
    return task


def _resolve_task_after(conn: sqlite3.Connection, task: sqlite3.Row, status: str) -> None:
    t = now_ms()
    if task["repeatable"]:
        rule = parse_repeat_rule(task["repeatRuleJson"] or "")
        rule["nextAt"] = next_repeat_at(rule, t)
        conn.execute(
            """
            UPDATE tasks SET status='OPEN', resolvedAt=?, repeatRuleJson=?, repeatable=1
            WHERE id=?
            """,
            (t, json.dumps(rule, ensure_ascii=False), task["id"]),
        )
    else:
        conn.execute(
            "UPDATE tasks SET status=?, resolvedAt=? WHERE id=?",
            (status, t, task["id"]),
        )


def complete_task(
    conn: sqlite3.Connection,
    task_id: int,
    *,
    record_changelog: bool = True,
) -> dict[str, Any]:
    task = _get_open_task(conn, task_id)
    rewards = parse_rewards(task["rewardJson"] or "[]")
    has_ticket = any(
        str(r.get("kind", "")).startswith("TICKET") or r.get("kind") == "LOTTERY_TICKET"
        for r in rewards
    )
    if not has_ticket:
        add_tickets(conn, "SLOT", 1)
    pass_result = advance_pass(conn)
    apply_rewards(conn, rewards, allowed_central_out=True)
    _resolve_task_after(conn, task, "DONE")
    entry = None
    if record_changelog:
        entry = changelog.append(
            "task.complete",
            {"id": task_id, "title": task["title"]},
            f"完成「{task['title']}」",
        )
    return {
        "title": task["title"],
        "pass": pass_result,
        "message": f"已完成「{task['title']}」",
        "changelog": entry,
    }


def fail_task(conn: sqlite3.Connection, task_id: int) -> dict[str, Any]:
    task = _get_open_task(conn, task_id)
    apply_rewards(conn, parse_rewards(task["penaltyJson"] or "[]"), allowed_central_out=False)
    _resolve_task_after(conn, task, "FAILED")
    entry = changelog.append(
        "task.fail",
        {"id": task_id, "title": task["title"]},
        f"失敗「{task['title']}」",
    )
    return {
        "title": task["title"],
        "message": f"已標記失敗「{task['title']}」",
        "changelog": entry,
    }


def create_task(
    conn: sqlite3.Connection,
    title: str,
    category: str = "其他",
    ticket_level: int = 2,
    note: str = "",
    reward_yuan: int | None = None,
    reward_to: str = LIVING,
    penalty_yuan: int | None = None,
    penalty_from: str = LIVING,
    repeatable: bool = False,
    repeat_freq: str = "daily",
    repeat_n: int | None = None,
) -> dict[str, Any]:
    title = title.strip()
    if not title:
        raise ValueError("標題不可空白")
    level = max(1, min(9, int(ticket_level)))
    rewards: list[dict[str, Any]] = [{"kind": "TICKET_LEVEL", "level": level}]
    if reward_yuan and reward_yuan > 0:
        rewards.append(
            {
                "kind": "TRANSFER",
                "amountCents": yuan_to_cents(reward_yuan),
                "fromWalletId": CENTRAL,
                "toWalletId": reward_to,
            }
        )
    penalties: list[dict[str, Any]] = []
    if penalty_yuan and penalty_yuan > 0:
        penalties.append(
            {
                "kind": "TRANSFER",
                "amountCents": yuan_to_cents(penalty_yuan),
                "fromWalletId": penalty_from,
                "toWalletId": CENTRAL,
            }
        )
    rule_json = ""
    if repeatable:
        rule_json = json.dumps(
            {"freq": repeat_freq, "n": repeat_n, "nextAt": 0}, ensure_ascii=False
        )
    cur = conn.execute(
        """
        INSERT INTO tasks
          (title, note, category, repeatable, repeatRuleJson, status,
           rewardJson, penaltyJson, createdAt, resolvedAt, dueAt, appearAt, planId)
        VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, NULL, NULL, NULL, NULL)
        """,
        (
            title,
            note,
            category,
            1 if repeatable else 0,
            rule_json,
            json.dumps(rewards, ensure_ascii=False),
            json.dumps(penalties, ensure_ascii=False),
            now_ms(),
        ),
    )
    task_id = int(cur.lastrowid)
    payload: dict[str, Any] = {
        "title": title,
        "category": category,
        "ticketLevel": level,
        "note": note,
    }
    if reward_yuan:
        payload["reward"] = {"yuan": reward_yuan, "to": reward_to}
    if penalty_yuan:
        payload["penalty"] = {"yuan": penalty_yuan, "from": penalty_from}
    if repeatable:
        payload["repeatable"] = True
        payload["repeat"] = {"freq": repeat_freq, "n": repeat_n}
    entry = changelog.append("task.create", payload, f"建立「{title}」")
    return {"id": task_id, "title": title, "message": "已建立", "changelog": entry}


def settle_focus(
    conn: sqlite3.Connection,
    minutes: int,
    intent: str = "PC暫代",
    category: str = "其他",
    cash_yuan: int | None = None,
    grant_tickets: bool = True,
) -> dict[str, Any]:
    minutes = max(0, int(minutes))
    effective_ms = minutes * 60_000
    if cash_yuan is None:
        cash_yuan = long_cash_yuan(effective_ms)
    cents = int(cash_yuan) * 100
    deferred = False
    if cents > 0:
        deferred = record_physical(
            conn, CENTRAL, LIVING, cents, f"專注結算 +{cash_yuan} 元"
        )
    elif cents < 0:
        deferred = record_physical(
            conn, LIVING, CENTRAL, -cents, f"專注結算 {cash_yuan} 元"
        )
    pack: dict[str, int] = {}
    ticket_text = "無券"
    if grant_tickets:
        units = ticket_units(effective_ms)
        mix = expand_ev_budget(units * EV_PER_LEVEL_YUAN)
        pack = merge_ticket_maps(mix, guaranteed_tickets(effective_ms))
        ticket_text = grant_ticket_map(conn, pack) or "無券"
    ended = now_ms()
    started = ended - effective_ms
    conn.execute(
        """
        INSERT INTO focus_sessions
          (intent, category, linkedTaskIdsJson, startedAt, endedAt, effectiveMs, cashYuan)
        VALUES (?, ?, '[]', ?, ?, ?, ?)
        """,
        (intent.strip() or "PC暫代", category, started, ended, effective_ms, int(cash_yuan)),
    )
    payload = {
        "minutes": minutes,
        "cashYuan": int(cash_yuan),
        "grantTickets": grant_tickets,
        "intent": intent.strip() or "PC暫代",
        "category": category,
    }
    cash_text = f"+{cash_yuan} 元" if cash_yuan >= 0 else f"{cash_yuan} 元"
    msg = (
        f"專注結算：{cash_text} 已加入待轉移，{ticket_text}"
        if deferred
        else f"專注結算：{cash_text}，{ticket_text}"
    )
    entry = changelog.append("focus.settle", payload, msg)
    return {
        "cashYuan": int(cash_yuan),
        "tickets": pack,
        "deferred": deferred,
        "message": msg,
        "changelog": entry,
    }


def add_tickets_op(conn: sqlite3.Connection, kind: str, n: int) -> dict[str, Any]:
    add_tickets(conn, kind, n)
    entry = changelog.append(
        "ticket.add",
        {"kind": kind.upper(), "n": int(n)},
        f"加券 {kind.upper()}×{n}",
    )
    return {"message": f"已加 {kind.upper()}×{n}", "changelog": entry}


def living_net_cents(item: dict[str, Any]) -> int:
    """正＝進入現金；負＝離開現金。"""
    amount = int(item["amountCents"])
    frm, to = item["fromWalletId"], item["toWalletId"]
    if to == LIVING:
        return amount
    if frm == LIVING and (
        is_piggy(to) or to in (CENTRAL, CENTRAL_FUTURE)
    ):
        return -amount
    return 0


def piggy_net_cents(items: list[dict[str, Any]]) -> int:
    return sum(living_net_cents(i) for i in items)


def _parse_loan_id(title: str, prefix: str) -> int | None:
    import re

    m = re.fullmatch(rf"{prefix} #(\d+)", title or "")
    return int(m.group(1)) if m else None


def _apply_loan_repay_immediate(conn: sqlite3.Connection, loan_id: int, principal_paid: int) -> None:
    loan = conn.execute("SELECT * FROM loans WHERE id=?", (loan_id,)).fetchone()
    if loan is None:
        raise ValueError("借據不存在")
    loan = dict(loan)
    borrower = get_wallet(conn, loan["borrowerWalletId"])
    if borrower["balanceCents"] < principal_paid:
        raise ValueError("借方餘額不足")
    adjust_balance(conn, loan["borrowerWalletId"], -principal_paid)
    adjust_balance(conn, loan["lenderWalletId"], principal_paid)
    new_remaining = int(loan["remainingCents"]) - principal_paid
    new_status = "REPAID" if new_remaining <= 0 else loan["status"]
    conn.execute(
        """
        UPDATE loans SET remainingCents=?, status=?,
          penaltyRateBp=CASE WHEN ?='REPAID' THEN 0 ELSE penaltyRateBp END
        WHERE id=?
        """,
        (max(0, new_remaining), new_status, new_status, loan_id),
    )
    if new_status == "REPAID":
        conn.execute(
            "UPDATE wallets SET canReceiveIncome=1 WHERE id=?",
            (loan["borrowerWalletId"],),
        )
    insert_tx(
        conn,
        "LOAN_REPAY",
        principal_paid,
        loan["borrowerWalletId"],
        loan["lenderWalletId"],
        f"還款 #{loan_id}",
    )


def _disburse_loan_immediate(
    conn: sqlite3.Connection,
    loan_id: int,
    lender_id: str,
    borrower_id: str,
    amount_cents: int,
    due_this_month: bool,
) -> None:
    src = get_wallet(conn, lender_id)
    if src["balanceCents"] < amount_cents:
        raise ValueError(f"{src['name']} 餘額不足")
    adjust_balance(conn, lender_id, -amount_cents)
    adjust_balance(conn, borrower_id, amount_cents)
    insert_tx(
        conn,
        "LOAN_DISBURSE",
        amount_cents,
        lender_id,
        borrower_id,
        f"借款 #{loan_id}（還債={'本月' if due_this_month else '下月'}）",
    )


def _pay_from_central_immediate(
    conn: sqlite3.Connection,
    to_id: str,
    amount_cents: int,
    note: str,
    tx_type: str,
) -> None:
    if amount_cents <= 0:
        return
    central = get_wallet(conn, CENTRAL)
    adjust_balance(conn, CENTRAL, -amount_cents)
    adjust_balance(conn, to_id, amount_cents)
    hole_note = note
    if central["balanceCents"] - amount_cents < 0:
        hole_note = f"{note}（透支）"
    insert_tx(conn, tx_type, amount_cents, CENTRAL, to_id, hole_note)


def _settle_generic(conn: sqlite3.Connection, item: dict[str, Any], amount_cents: int) -> None:
    src = get_wallet(conn, item["fromWalletId"])
    if src["balanceCents"] < amount_cents:
        raise ValueError(f"{src['name']} 餘額不足")
    adjust_balance(conn, item["fromWalletId"], -amount_cents)
    adjust_balance(conn, item["toWalletId"], amount_cents)
    insert_tx(
        conn,
        "PHYSICAL_TRANSFER",
        amount_cents,
        item["fromWalletId"],
        item["toWalletId"],
        item.get("taskTitle") or "待轉移",
    )


def _is_loan_due_this_month(due_at: int) -> bool:
    from datetime import datetime
    from zoneinfo import ZoneInfo

    tz = ZoneInfo("Asia/Taipei")
    due = datetime.fromtimestamp(due_at / 1000, tz)
    now = datetime.now(tz)
    return (due.year, due.month) <= (now.year, now.month)


def _settle_item(conn: sqlite3.Connection, item: dict[str, Any], settle_cents: int) -> None:
    amount = int(item["amountCents"])
    if settle_cents <= 0 or settle_cents > amount:
        raise ValueError("結算金額無效")
    title = item.get("taskTitle") or ""
    repay_id = _parse_loan_id(title, "還款")
    borrow_id = _parse_loan_id(title, "借款")
    if repay_id is not None:
        _apply_loan_repay_immediate(conn, repay_id, settle_cents)
    elif borrow_id is not None:
        loan = conn.execute("SELECT * FROM loans WHERE id=?", (borrow_id,)).fetchone()
        if loan is None:
            raise ValueError("借據不存在")
        _disburse_loan_immediate(
            conn,
            borrow_id,
            item["fromWalletId"],
            item["toWalletId"],
            settle_cents,
            _is_loan_due_this_month(int(loan["dueAt"])),
        )
    elif item["fromWalletId"] == CENTRAL:
        _pay_from_central_immediate(
            conn,
            item["toWalletId"],
            settle_cents,
            title or "待轉移",
            "PHYSICAL_TRANSFER",
        )
    else:
        _settle_generic(conn, {**item, "amountCents": settle_cents}, settle_cents)

    if settle_cents >= amount:
        conn.execute(
            "UPDATE pending_physical_transfers SET confirmedAt=? WHERE id=?",
            (now_ms(), item["id"]),
        )
    else:
        conn.execute(
            "UPDATE pending_physical_transfers SET amountCents=? WHERE id=?",
            (amount - settle_cents, item["id"]),
        )


def list_pending(conn: sqlite3.Connection) -> list[dict[str, Any]]:
    return [
        dict(r)
        for r in conn.execute(
            "SELECT * FROM pending_physical_transfers "
            "WHERE confirmedAt IS NULL ORDER BY createdAt ASC"
        ).fetchall()
    ]


def confirm_physical_transfers(
    conn: sqlite3.Connection,
    amount_cents: int | None = None,
) -> dict[str, Any]:
    """確認待實體轉移。amount_cents=None 表示全額（淨額絕對值）。"""
    pending = list_pending(conn)
    if not pending:
        return {"message": "沒有待轉移", "settledYuan": 0}
    net = piggy_net_cents(pending)
    if net == 0:
        # 先入現金再扣回
        ordered = sorted(
            pending,
            key=lambda it: (
                0 if living_net_cents(it) > 0 else (1 if living_net_cents(it) < 0 else 2),
                it["createdAt"],
                it["id"],
            ),
        )
        for item in ordered:
            _settle_item(conn, item, int(item["amountCents"]))
        entry = changelog.append(
            "wallet.confirm_physical",
            {"yuan": 0, "netZero": True},
            "已確認待轉移（收支相抵）",
        )
        return {"message": "已確認待轉移（收支相抵）", "settledYuan": 0, "changelog": entry}

    max_abs = abs(net)
    settle = max_abs if amount_cents is None else int(amount_cents)
    if settle <= 0:
        raise ValueError("請輸入要轉移的金額")
    if settle > max_abs:
        raise ValueError(f"不可超過上限 {max_abs / 100:g} 元")
    want_positive = net > 0
    left = settle
    for item in pending:
        if left <= 0:
            break
        contrib = living_net_cents(item)
        if contrib == 0:
            continue
        if want_positive and contrib <= 0:
            continue
        if not want_positive and contrib >= 0:
            continue
        take = min(abs(contrib), left)
        _settle_item(conn, item, take)
        left -= take
    if left != 0:
        raise ValueError("可結算金額不足")
    entry = changelog.append(
        "wallet.confirm_physical",
        {"yuan": settle / 100},
        f"已確認實體轉移 {settle / 100:g} 元",
    )
    return {
        "message": f"已確認實體轉移 {settle / 100:g} 元",
        "settledYuan": settle / 100,
        "changelog": entry,
    }
