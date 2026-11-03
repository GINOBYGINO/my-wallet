from __future__ import annotations

from typing import Any

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, Field

from . import changelog, economy, exams, loans
from .db import connect, ensure_working_db, rows_to_dicts
from .export_cmd import export_sync
from .paths import STATIC_DIR

app = FastAPI(title="MyWallet Desktop", version="0.1.0")


@app.on_event("startup")
def _startup() -> None:
    ensure_working_db()


class TransferBody(BaseModel):
    fromId: str
    toId: str
    yuan: float
    note: str = ""


class TaskIdBody(BaseModel):
    id: int


class CreateTaskBody(BaseModel):
    title: str
    category: str = "其他"
    ticketLevel: int = 2
    note: str = ""
    rewardYuan: int | None = None
    rewardTo: str = "living"
    penaltyYuan: int | None = None
    penaltyFrom: str = "living"
    repeatable: bool = False
    repeatFreq: str = "daily"
    repeatN: int | None = None


class FocusBody(BaseModel):
    minutes: int
    intent: str = "PC暫代"
    category: str = "其他"
    cashYuan: int | None = None
    grantTickets: bool = True


class TicketBody(BaseModel):
    kind: str = "SLOT"
    n: int = Field(ge=1, default=1)


class NoteBody(BaseModel):
    text: str


class ExamCourseBody(BaseModel):
    subject: str
    note: str = ""
    units: list[str] = Field(default_factory=list)


class ExamItemBody(BaseModel):
    id: int


class BorrowBody(BaseModel):
    lender: str
    borrower: str
    yuan: float
    dueThisMonth: bool = False


class RepayBody(BaseModel):
    id: int
    yuan: float | None = None
    full: bool = False


class LoanIdBody(BaseModel):
    id: int


class ConfirmPhysicalBody(BaseModel):
    # 元；省略＝全額確認淨額
    yuan: float | None = None


@app.get("/api/health")
def health() -> dict[str, Any]:
    try:
        info = ensure_working_db()
        return {"ok": True, **info}
    except Exception as e:
        raise HTTPException(500, str(e)) from e


@app.get("/api/overview")
def overview() -> dict[str, Any]:
    with connect() as conn:
        wallets = rows_to_dicts(
            conn.execute(
                "SELECT id, name, balanceCents, isCentral, sortOrder, archived "
                "FROM wallets WHERE archived=0 ORDER BY sortOrder, id"
            ).fetchall()
        )
        tickets_row = conn.execute("SELECT * FROM lottery_state WHERE id=1").fetchone()
        tickets = dict(tickets_row) if tickets_row else {}
        pending = economy.list_pending(conn)
        pending_net = economy.piggy_net_cents(pending)
        pass_row = conn.execute(
            "SELECT title, progress FROM pass_seasons WHERE active=1 "
            "ORDER BY id DESC LIMIT 1"
        ).fetchone()
        pass_info = dict(pass_row) if pass_row else None
        loans = rows_to_dicts(
            conn.execute(
                "SELECT id, lenderWalletId, borrowerWalletId, remainingCents, status "
                "FROM loans WHERE status='ACTIVE' ORDER BY id"
            ).fetchall()
        )
        holdings = rows_to_dicts(
            conn.execute(
                "SELECT symbol, name, sharesMilli, costCents, lastPriceCents "
                "FROM stock_holdings WHERE sharesMilli > 0 ORDER BY symbol"
            ).fetchall()
        ) if _table_exists(conn, "stock_holdings") else []
    return {
        "wallets": wallets,
        "tickets": tickets,
        "pending": pending,
        "pendingNetCents": pending_net,
        "pass": pass_info,
        "loans": loans,
        "holdings": holdings,
        "changelogCount": len(changelog.read_all()),
    }


def _table_exists(conn, name: str) -> bool:
    row = conn.execute(
        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (name,)
    ).fetchone()
    return row is not None


@app.get("/api/tasks")
def list_tasks(status: str = "OPEN") -> dict[str, Any]:
    with connect() as conn:
        if status.upper() == "ALL":
            rows = conn.execute(
                "SELECT * FROM tasks ORDER BY "
                "CASE status WHEN 'OPEN' THEN 0 ELSE 1 END, id DESC"
            ).fetchall()
        else:
            rows = conn.execute(
                "SELECT * FROM tasks WHERE status=? ORDER BY id DESC",
                (status.upper(),),
            ).fetchall()
    return {"tasks": rows_to_dicts(rows)}


@app.get("/api/transactions")
def list_transactions(limit: int = 40) -> dict[str, Any]:
    limit = max(1, min(200, limit))
    with connect() as conn:
        rows = conn.execute(
            "SELECT * FROM transactions ORDER BY createdAt DESC, id DESC LIMIT ?",
            (limit,),
        ).fetchall()
    return {"transactions": rows_to_dicts(rows)}


@app.get("/api/courses")
def list_courses() -> dict[str, Any]:
    with connect() as conn:
        if not _table_exists(conn, "courses"):
            return {"courses": []}
        rows = conn.execute(
            "SELECT * FROM courses ORDER BY weekday, periodStart"
        ).fetchall()
    return {"courses": rows_to_dicts(rows)}


@app.get("/api/events")
def list_events(limit: int = 30) -> dict[str, Any]:
    with connect() as conn:
        if not _table_exists(conn, "calendar_events"):
            return {"events": []}
        rows = conn.execute(
            "SELECT * FROM calendar_events ORDER BY dateYmd DESC, timeMinutes LIMIT ?",
            (limit,),
        ).fetchall()
    return {"events": rows_to_dicts(rows)}


@app.get("/api/changelog")
def get_changelog() -> dict[str, Any]:
    return {"entries": changelog.read_all()}


@app.post("/api/transfer")
def api_transfer(body: TransferBody) -> dict[str, Any]:
    try:
        with connect() as conn:
            return economy.transfer(
                conn,
                body.fromId,
                body.toId,
                economy.yuan_to_cents(body.yuan),
                body.note,
            )
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/physical/confirm")
def api_confirm_physical(body: ConfirmPhysicalBody = ConfirmPhysicalBody()) -> dict[str, Any]:
    try:
        with connect() as conn:
            cents = None if body.yuan is None else economy.yuan_to_cents(body.yuan)
            return economy.confirm_physical_transfers(conn, cents)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/tasks/complete")
def api_complete(body: TaskIdBody) -> dict[str, Any]:
    try:
        with connect() as conn:
            return economy.complete_task(conn, body.id)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/tasks/fail")
def api_fail(body: TaskIdBody) -> dict[str, Any]:
    try:
        with connect() as conn:
            return economy.fail_task(conn, body.id)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/tasks/create")
def api_create_task(body: CreateTaskBody) -> dict[str, Any]:
    try:
        with connect() as conn:
            return economy.create_task(
                conn,
                title=body.title,
                category=body.category,
                ticket_level=body.ticketLevel,
                note=body.note,
                reward_yuan=body.rewardYuan,
                reward_to=body.rewardTo,
                penalty_yuan=body.penaltyYuan,
                penalty_from=body.penaltyFrom,
                repeatable=body.repeatable,
                repeat_freq=body.repeatFreq,
                repeat_n=body.repeatN,
            )
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/focus/settle")
def api_focus(body: FocusBody) -> dict[str, Any]:
    try:
        with connect() as conn:
            return economy.settle_focus(
                conn,
                minutes=body.minutes,
                intent=body.intent,
                category=body.category,
                cash_yuan=body.cashYuan,
                grant_tickets=body.grantTickets,
            )
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/tickets/add")
def api_tickets(body: TicketBody) -> dict[str, Any]:
    try:
        with connect() as conn:
            return economy.add_tickets_op(conn, body.kind, body.n)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/note")
def api_note(body: NoteBody) -> dict[str, Any]:
    text = body.text.strip()
    if not text:
        raise HTTPException(400, "備註不可空白")
    entry = changelog.append("note", {"text": text}, text)
    return {"message": "已記備註", "changelog": entry}


@app.post("/api/export")
def api_export() -> dict[str, Any]:
    try:
        return export_sync()
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.get("/api/exams")
def api_exams() -> dict[str, Any]:
    with connect() as conn:
        return exams.snapshot(conn)


@app.post("/api/exams/course")
def api_exam_course(body: ExamCourseBody) -> dict[str, Any]:
    try:
        with connect() as conn:
            return exams.save_course(conn, body.subject, body.note, body.units)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/exams/complete")
def api_exam_complete(body: ExamItemBody) -> dict[str, Any]:
    try:
        with connect() as conn:
            return exams.complete_item(conn, body.id)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/exams/enqueue")
def api_exam_enqueue(body: ExamItemBody) -> dict[str, Any]:
    try:
        with connect() as conn:
            return exams.enqueue_item(conn, body.id)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/exams/dequeue")
def api_exam_dequeue(body: ExamItemBody) -> dict[str, Any]:
    try:
        with connect() as conn:
            return exams.dequeue_item(conn, body.id)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/exams/delete")
def api_exam_delete(body: ExamItemBody) -> dict[str, Any]:
    try:
        with connect() as conn:
            return exams.delete_item(conn, body.id)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.get("/api/loans")
def api_loans() -> dict[str, Any]:
    with connect() as conn:
        return loans.list_loans(conn)


@app.post("/api/loans/borrow")
def api_borrow(body: BorrowBody) -> dict[str, Any]:
    try:
        with connect() as conn:
            return loans.borrow(
                conn,
                body.lender,
                body.borrower,
                economy.yuan_to_cents(body.yuan),
                body.dueThisMonth,
            )
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/loans/repay")
def api_repay(body: RepayBody) -> dict[str, Any]:
    try:
        with connect() as conn:
            if body.full:
                row = conn.execute(
                    "SELECT remainingCents FROM loans WHERE id=?", (body.id,)
                ).fetchone()
                if row is None:
                    raise ValueError("借據不存在")
                cents = int(row["remainingCents"])
            else:
                if body.yuan is None or body.yuan <= 0:
                    raise ValueError("請輸入還款金額")
                cents = economy.yuan_to_cents(body.yuan)
            return loans.repay(conn, body.id, cents)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.post("/api/loans/extend")
def api_extend(body: LoanIdBody) -> dict[str, Any]:
    try:
        with connect() as conn:
            return loans.extend_loan(conn, body.id)
    except ValueError as e:
        raise HTTPException(400, str(e)) from e


@app.get("/")
def index() -> FileResponse:
    return FileResponse(STATIC_DIR / "index.html")


app.mount("/static", StaticFiles(directory=str(STATIC_DIR)), name="static")
