from __future__ import annotations

import sqlite3
import time
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

from . import changelog, economy

TZ = ZoneInfo("Asia/Taipei")
SAME_MONTH_MIN_RATE_BP = 100
CENTRAL = "central"
CENTRAL_FUTURE = "central_future"
ACTIVE = "ACTIVE"
DEFAULTED = "DEFAULTED"
REPAID = "REPAID"
# Room @ColumnInfo：monthlyRateBp → 實際欄位 annualRateBp
RATE_COL = "annualRateBp"


def now_ms() -> int:
    return int(time.time() * 1000)


def month_end_ms(year: int, month: int) -> int:
    if month == 12:
        nxt = datetime(year + 1, 1, 1, tzinfo=TZ)
    else:
        nxt = datetime(year, month + 1, 1, tzinfo=TZ)
    end = nxt.timestamp() - 1
    return int(end * 1000)


def effective_rate_bp(rate_bp: int, due_this_month: bool) -> int:
    if not due_this_month:
        return rate_bp
    return max(rate_bp // 2, SAME_MONTH_MIN_RATE_BP)


def month_interest_cents(remaining: int, monthly_rate_bp: int, penalty_bp: int = 0) -> int:
    if remaining <= 0:
        return 0
    rate = monthly_rate_bp + penalty_bp
    if rate <= 0:
        return 0
    return remaining * rate // 10_000


def plus_months_ms(due_at: int, months: int) -> int:
    dt = datetime.fromtimestamp(due_at / 1000, TZ)
    y, m = dt.year, dt.month + months
    while m > 12:
        y += 1
        m -= 12
    while m < 1:
        y -= 1
        m += 12
    try:
        nd = dt.replace(year=y, month=m)
    except ValueError:
        # 月底對齊
        if m == 12:
            nd = datetime(y + 1, 1, 1, tzinfo=TZ)
        else:
            nd = datetime(y, m + 1, 1, tzinfo=TZ)
        nd = nd.fromtimestamp(nd.timestamp() - 1, TZ)
    return int(nd.timestamp() * 1000)


def list_loans(conn: sqlite3.Connection) -> dict[str, Any]:
    loans = [
        dict(r)
        for r in conn.execute(
            f"""
            SELECT id, lenderWalletId, borrowerWalletId, principalCents, remainingCents,
                   {RATE_COL} AS monthlyRateBp, penaltyRateBp, startAt, dueAt, status,
                   offerId, interestCoveredUntil
            FROM loans
            WHERE status IN ('ACTIVE', 'DEFAULTED')
            ORDER BY id DESC
            """
        ).fetchall()
    ]
    rates = [
        dict(r)
        for r in conn.execute(
            "SELECT walletId, monthlyRateBp FROM wallet_rates ORDER BY walletId"
        ).fetchall()
    ]
    return {"loans": loans, "rates": rates}


def borrow(
    conn: sqlite3.Connection,
    lender_id: str,
    borrower_id: str,
    amount_cents: int,
    due_this_month: bool,
) -> dict[str, Any]:
    if amount_cents <= 0:
        raise ValueError("金額須 > 0")
    if lender_id == borrower_id:
        raise ValueError("出借／借入不可相同")
    if lender_id == CENTRAL_FUTURE or borrower_id == CENTRAL_FUTURE:
        raise ValueError("未來中央帳戶不可借貸")
    if borrower_id == CENTRAL:
        raise ValueError("中央透支請由系統自動產生借據")
    lender = economy.get_wallet(conn, lender_id)
    if lender["balanceCents"] < amount_cents:
        raise ValueError("出借方餘額不足")
    rate_row = conn.execute(
        "SELECT monthlyRateBp FROM wallet_rates WHERE walletId=?", (lender_id,)
    ).fetchone()
    if rate_row is None:
        raise ValueError("尚未設定該戶利率")
    rate_bp = int(rate_row["monthlyRateBp"])
    eff = effective_rate_bp(rate_bp, due_this_month)
    interest = amount_cents * eff // 10_000
    now = datetime.now(TZ)
    ym = (now.year, now.month)
    if due_this_month:
        due_ym = ym
    else:
        y, m = ym[0], ym[1] + 1
        if m > 12:
            y, m = y + 1, 1
        due_ym = (y, m)
    due = month_end_ms(*due_ym)
    remaining = amount_cents + interest if due_this_month else amount_cents
    covered = due if due_this_month else month_end_ms(*ym)
    t = now_ms()
    cur = conn.execute(
        f"""
        INSERT INTO loans
          (lenderWalletId, borrowerWalletId, principalCents, remainingCents,
           {RATE_COL}, penaltyRateBp, startAt, dueAt, status, offerId, interestCoveredUntil)
        VALUES (?, ?, ?, ?, ?, 0, ?, ?, 'ACTIVE', NULL, ?)
        """,
        (
            lender_id,
            borrower_id,
            amount_cents,
            remaining,
            rate_bp,
            t,
            due,
            covered,
        ),
    )
    loan_id = int(cur.lastrowid)
    if due_this_month and interest > 0:
        economy.insert_tx(
            conn,
            "LOAN_INTEREST",
            interest,
            borrower_id,
            lender_id,
            f"本月還最低息 借據#{loan_id}（{eff / 100}%）",
        )
    deferred = False
    if economy.should_defer(lender_id, borrower_id):
        economy.record_physical(
            conn, lender_id, borrower_id, amount_cents, f"借款 #{loan_id}"
        )
        deferred = True
    else:
        economy.adjust_balance(conn, lender_id, -amount_cents)
        economy.adjust_balance(conn, borrower_id, amount_cents)
        economy.insert_tx(
            conn,
            "LOAN_DISBURSE",
            amount_cents,
            lender_id,
            borrower_id,
            f"借款 #{loan_id}（還債={'本月' if due_this_month else '下月'}）",
        )
    payload = {
        "lender": lender_id,
        "borrower": borrower_id,
        "yuan": amount_cents / 100,
        "dueThisMonth": due_this_month,
        "loanId": loan_id,
    }
    msg = "借款已成立，待轉移確認後才入帳" if deferred else "借款成立"
    entry = changelog.append("loan.borrow", payload, msg)
    return {"loanId": loan_id, "deferred": deferred, "message": msg, "changelog": entry}


def repay(conn: sqlite3.Connection, loan_id: int, amount_cents: int) -> dict[str, Any]:
    loan = conn.execute("SELECT * FROM loans WHERE id=?", (loan_id,)).fetchone()
    if loan is None:
        raise ValueError("借據不存在")
    loan = dict(loan)
    if loan.get("offerId") == -1 and loan["borrowerWalletId"] == CENTRAL:
        raise ValueError("中央透支借據由收入分配自動償還")
    if loan["status"] not in (ACTIVE, DEFAULTED):
        raise ValueError("此借據不可還款")
    if amount_cents <= 0:
        raise ValueError("金額須 > 0")
    borrower = economy.get_wallet(conn, loan["borrowerWalletId"])
    if borrower["balanceCents"] < amount_cents:
        raise ValueError("借方餘額不足")
    principal_paid = min(amount_cents, loan["remainingCents"])
    if principal_paid <= 0:
        raise ValueError("此借據無需再還")
    deferred = False
    if economy.should_defer(loan["borrowerWalletId"], loan["lenderWalletId"]):
        title = f"還款 #{loan_id}"
        already = conn.execute(
            "SELECT 1 FROM pending_physical_transfers WHERE confirmedAt IS NULL AND taskTitle=?",
            (title,),
        ).fetchone()
        if already:
            raise ValueError("此借據已有待確認還款，請先確認轉移")
        economy.record_physical(
            conn,
            loan["borrowerWalletId"],
            loan["lenderWalletId"],
            principal_paid,
            title,
        )
        deferred = True
    else:
        economy.adjust_balance(conn, loan["borrowerWalletId"], -principal_paid)
        economy.adjust_balance(conn, loan["lenderWalletId"], principal_paid)
        new_remaining = loan["remainingCents"] - principal_paid
        new_status = REPAID if new_remaining <= 0 else loan["status"]
        conn.execute(
            f"""
            UPDATE loans SET remainingCents=?, status=?,
              penaltyRateBp=CASE WHEN ?='REPAID' THEN 0 ELSE penaltyRateBp END
            WHERE id=?
            """,
            (max(0, new_remaining), new_status, new_status, loan_id),
        )
        if new_status == REPAID:
            conn.execute(
                "UPDATE wallets SET canReceiveIncome=1 WHERE id=?",
                (loan["borrowerWalletId"],),
            )
        economy.insert_tx(
            conn,
            "LOAN_REPAY",
            principal_paid,
            loan["borrowerWalletId"],
            loan["lenderWalletId"],
            f"還款 #{loan_id}",
        )
    payload = {"id": loan_id, "yuan": principal_paid / 100}
    msg = "已加入待轉移，確認後才入帳" if deferred else "還款完成"
    entry = changelog.append("loan.repay", payload, msg)
    return {"deferred": deferred, "message": msg, "changelog": entry}


def extend_loan(conn: sqlite3.Connection, loan_id: int) -> dict[str, Any]:
    loan = conn.execute("SELECT * FROM loans WHERE id=?", (loan_id,)).fetchone()
    if loan is None:
        raise ValueError("借據不存在")
    loan = dict(loan)
    rate_bp = int(loan[RATE_COL])
    penalty = int(loan["penaltyRateBp"] or 0)
    if loan.get("offerId") == -1 and loan["borrowerWalletId"] == CENTRAL:
        raise ValueError("中央透支借據不可續借")
    if loan["status"] not in (ACTIVE, DEFAULTED):
        raise ValueError("此借據不可續借")
    if loan["remainingCents"] <= 0:
        raise ValueError("此借據無需續借")

    remaining = int(loan["remainingCents"])
    covered = int(loan["interestCoveredUntil"] or 0)
    due_at = int(loan["dueAt"])
    # 補本期月息
    if covered < due_at:
        interest = month_interest_cents(remaining, rate_bp, penalty)
        remaining += interest
        covered = due_at
        conn.execute(
            f"UPDATE loans SET remainingCents=?, interestCoveredUntil=? WHERE id=?",
            (remaining, covered, loan_id),
        )
        if interest > 0:
            economy.insert_tx(
                conn,
                "LOAN_INTEREST",
                interest,
                loan["borrowerWalletId"],
                loan["lenderWalletId"],
                f"月息結算 借據#{loan_id}",
            )

    interest2 = month_interest_cents(remaining, rate_bp, penalty)
    new_due = plus_months_ms(due_at, 1)
    remaining += interest2
    conn.execute(
        f"""
        UPDATE loans SET remainingCents=?, dueAt=?, interestCoveredUntil=? WHERE id=?
        """,
        (remaining, new_due, new_due, loan_id),
    )
    if interest2 > 0:
        economy.insert_tx(
            conn,
            "LOAN_INTEREST",
            interest2,
            loan["borrowerWalletId"],
            loan["lenderWalletId"],
            f"續借一個月 借據#{loan_id}",
        )
    due_dt = datetime.fromtimestamp(new_due / 1000, TZ)
    due_label = f"{due_dt.month}/{due_dt.day}"
    entry = changelog.append(
        "loan.extend",
        {"id": loan_id},
        f"已續借至 {due_label}",
    )
    return {"message": f"已續借至 {due_label}", "dueAt": new_due, "changelog": entry}
