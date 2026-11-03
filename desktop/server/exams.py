from __future__ import annotations

import json
import sqlite3
import time
from typing import Any

from . import changelog, economy

REVIEW_DELAY_MS = 24 * 60 * 60 * 1000
OPEN = "OPEN"
COOLDOWN = "COOLDOWN"
REVIEWABLE = "REVIEWABLE"
DONE = "DONE"


def now_ms() -> int:
    return int(time.time() * 1000)


def display_label(subject: str, title: str) -> str:
    s = (subject or "").strip()
    t = (title or "").strip()
    if s and t:
        return f"{s} {t}"
    return t or s


def ensure_active_exam(conn: sqlite3.Connection) -> dict[str, Any]:
    row = conn.execute(
        "SELECT * FROM exams WHERE archived=0 ORDER BY createdAt DESC LIMIT 1"
    ).fetchone()
    if row:
        return dict(row)
    cur = conn.execute(
        "INSERT INTO exams (title, dueAt, archived, createdAt) VALUES ('考試', NULL, 0, ?)",
        (now_ms(),),
    )
    exam_id = int(cur.lastrowid)
    return dict(conn.execute("SELECT * FROM exams WHERE id=?", (exam_id,)).fetchone())


def promote_due_reviews(conn: sqlite3.Connection, exam_id: int) -> int:
    t = now_ms()
    rows = conn.execute(
        """
        SELECT id FROM exam_items
        WHERE examId=? AND status=? AND reviewAvailableAt IS NOT NULL AND reviewAvailableAt<=?
        """,
        (exam_id, COOLDOWN, t),
    ).fetchall()
    for r in rows:
        conn.execute(
            "UPDATE exam_items SET status=? WHERE id=?",
            (REVIEWABLE, r["id"]),
        )
    return len(rows)


def normalize_queue(conn: sqlite3.Connection, exam_id: int) -> None:
    rows = conn.execute(
        """
        SELECT id FROM exam_items
        WHERE examId=? AND queueOrder IS NOT NULL
        ORDER BY queueOrder ASC, id ASC
        """,
        (exam_id,),
    ).fetchall()
    for i, r in enumerate(rows):
        conn.execute("UPDATE exam_items SET queueOrder=? WHERE id=?", (i, r["id"]))


def snapshot(conn: sqlite3.Connection) -> dict[str, Any]:
    exam = ensure_active_exam(conn)
    promote_due_reviews(conn, exam["id"])
    items = [
        dict(r)
        for r in conn.execute(
            "SELECT * FROM exam_items WHERE examId=? ORDER BY createdAt ASC, id ASC",
            (exam["id"],),
        ).fetchall()
    ]
    subjects = [
        dict(r)
        for r in conn.execute(
            "SELECT * FROM exam_subjects WHERE examId=? ORDER BY subject ASC",
            (exam["id"],),
        ).fetchall()
    ]
    total = len(items)
    first_done = sum(1 for i in items if i["status"] != OPEN)
    review_done = sum(1 for i in items if i["status"] == DONE)
    queue = sorted(
        [i for i in items if i["queueOrder"] is not None],
        key=lambda x: (x["queueOrder"], x["id"]),
    )
    visible = [i for i in items if i["status"] != DONE]
    return {
        "exam": exam,
        "subjects": subjects,
        "items": visible,
        "allItems": items,
        "queue": queue,
        "progress": {
            "total": total,
            "firstDone": first_done,
            "reviewDone": review_done,
        },
    }


def save_course(
    conn: sqlite3.Connection,
    subject: str,
    note: str = "",
    units: list[str] | None = None,
) -> dict[str, Any]:
    subj = subject.strip()
    if not subj:
        raise ValueError("請輸入課程名稱")
    exam = ensure_active_exam(conn)
    existing = conn.execute(
        "SELECT * FROM exam_subjects WHERE examId=? AND subject=?",
        (exam["id"], subj),
    ).fetchone()
    if existing:
        conn.execute(
            "UPDATE exam_subjects SET note=? WHERE id=?",
            (note.strip(), existing["id"]),
        )
    else:
        conn.execute(
            "INSERT INTO exam_subjects (examId, subject, note) VALUES (?, ?, ?)",
            (exam["id"], subj, note.strip()),
        )
    cleaned = [u.strip() for u in (units or []) if u and u.strip()]
    for unit in cleaned:
        conn.execute(
            """
            INSERT INTO exam_items
              (examId, subject, title, note, status, queueOrder, createdAt)
            VALUES (?, ?, ?, '', ?, NULL, ?)
            """,
            (exam["id"], subj, unit, OPEN, now_ms()),
        )
    payload = {"subject": subj, "note": note.strip(), "units": cleaned}
    msg = "已儲存" if not cleaned else f"已儲存（+{len(cleaned)} 單元）"
    entry = changelog.append("exam.save_course", payload, msg)
    return {"message": msg, "added": len(cleaned), "changelog": entry}


def complete_item(conn: sqlite3.Connection, item_id: int) -> dict[str, Any]:
    exam = ensure_active_exam(conn)
    promote_due_reviews(conn, exam["id"])
    item = conn.execute("SELECT * FROM exam_items WHERE id=?", (item_id,)).fetchone()
    if item is None:
        raise ValueError("範圍不存在")
    t = now_ms()
    status = item["status"]
    if status == OPEN:
        conn.execute(
            """
            UPDATE exam_items
            SET status=?, firstCompletedAt=?, reviewAvailableAt=?, queueOrder=NULL
            WHERE id=?
            """,
            (COOLDOWN, t, t + REVIEW_DELAY_MS, item_id),
        )
        phase = "初次完成"
        task_title = display_label(item["subject"], item["title"])
        new_status = COOLDOWN
    elif status == REVIEWABLE:
        conn.execute(
            """
            UPDATE exam_items
            SET status=?, reviewCompletedAt=?, queueOrder=NULL
            WHERE id=?
            """,
            (DONE, t, item_id),
        )
        phase = "複習完成"
        task_title = f"複習 · {display_label(item['subject'], item['title'])}"
        new_status = DONE
    elif status == COOLDOWN:
        raise ValueError("倒數中，尚未可複習")
    elif status == DONE:
        raise ValueError("已完成複習")
    else:
        raise ValueError(f"未知狀態：{status}")

    normalize_queue(conn, item["examId"])

    # 對齊 App：建立 Lv4 學業任務並 completeTask
    rewards = json.dumps([{"kind": "TICKET_LEVEL", "level": 4}], ensure_ascii=False)
    cur = conn.execute(
        """
        INSERT INTO tasks
          (title, note, category, repeatable, repeatRuleJson, status,
           rewardJson, penaltyJson, createdAt, resolvedAt, dueAt, appearAt, planId)
        VALUES (?, '考試', '學業', 0, '', 'OPEN', ?, '[]', ?, NULL, NULL, NULL, NULL)
        """,
        (task_title, rewards, t),
    )
    task_id = int(cur.lastrowid)
    task_result = economy.complete_task(conn, task_id, record_changelog=False)

    payload = {
        "id": item_id,
        "subject": item["subject"],
        "title": item["title"],
        "phase": phase,
    }
    msg = f"{phase} · {task_title} · Lv.4 券包"
    if task_result.get("pass"):
        msg += f" · 通行證 {task_result['pass'].get('tierIndex')}"
    # complete_task 已寫 task.complete；再記 exam.complete 供匯回時走考試流程
    entry = changelog.append("exam.complete", payload, msg)
    return {
        "message": msg,
        "status": new_status,
        "taskId": task_id,
        "changelog": entry,
    }


def enqueue_item(conn: sqlite3.Connection, item_id: int) -> dict[str, Any]:
    exam = ensure_active_exam(conn)
    promote_due_reviews(conn, exam["id"])
    item = conn.execute("SELECT * FROM exam_items WHERE id=?", (item_id,)).fetchone()
    if item is None:
        raise ValueError("範圍不存在")
    if item["status"] not in (OPEN, REVIEWABLE):
        raise ValueError("倒數中或已複習的範圍不能加入串列")
    if item["queueOrder"] is not None:
        return {"message": "已在串列中"}
    n = conn.execute(
        "SELECT COUNT(*) FROM exam_items WHERE examId=? AND queueOrder IS NOT NULL",
        (item["examId"],),
    ).fetchone()[0]
    conn.execute("UPDATE exam_items SET queueOrder=? WHERE id=?", (n, item_id))
    entry = changelog.append(
        "exam.enqueue",
        {"id": item_id, "subject": item["subject"], "title": item["title"]},
        "已加入串列",
    )
    return {"message": "已加入串列", "changelog": entry}


def dequeue_item(conn: sqlite3.Connection, item_id: int) -> dict[str, Any]:
    item = conn.execute("SELECT * FROM exam_items WHERE id=?", (item_id,)).fetchone()
    if item is None:
        raise ValueError("範圍不存在")
    if item["queueOrder"] is None:
        return {"message": "不在串列中"}
    conn.execute("UPDATE exam_items SET queueOrder=NULL WHERE id=?", (item_id,))
    normalize_queue(conn, item["examId"])
    entry = changelog.append(
        "exam.dequeue",
        {"id": item_id, "subject": item["subject"], "title": item["title"]},
        "已移出串列",
    )
    return {"message": "已移出串列", "changelog": entry}


def delete_item(conn: sqlite3.Connection, item_id: int) -> dict[str, Any]:
    item = conn.execute("SELECT * FROM exam_items WHERE id=?", (item_id,)).fetchone()
    if item is None:
        raise ValueError("範圍不存在")
    conn.execute("DELETE FROM exam_items WHERE id=?", (item_id,))
    normalize_queue(conn, item["examId"])
    entry = changelog.append(
        "exam.delete_item",
        {"id": item_id, "subject": item["subject"], "title": item["title"]},
        "已刪除範圍",
    )
    return {"message": "已刪除範圍", "changelog": entry}
