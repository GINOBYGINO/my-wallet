# -*- coding: utf-8 -*-
"""Generate mywallet-cmd JSON for 115-1 study plan."""
import json
from pathlib import Path

def when(date, time=None):
    w = {"date": date}
    if time:
        w["time"] = time
    return w

def task(title, date, due_time, note="", level=5, category="學業", appear_time=None):
    item = {
        "title": title,
        "category": category,
        "ticketLevel": level,
        "appear": when(date, appear_time),
        "due": when(date, due_time),
    }
    if note:
        item["note"] = note
    return item

def event(title, date, time=None, note="", location=""):
    e = {"op": "event.create", "title": title, "date": date}
    if time:
        e["time"] = time
    if note:
        e["note"] = note
    if location:
        e["location"] = location
    return e

papers_a = [
    ("2026-09-17", "11.1 Sequences", "數列與收斂"),
    ("2026-09-24", "2.3 Limit Laws", "極限"),
    ("2026-10-01", "2.5 Continuity", "連續與極值"),
    ("2026-10-08", "2.4 ε-δ", "中間值定理"),
    ("2026-10-15", "2.8 The Derivative as a Function", "微分"),
    ("2026-10-22", "3.3 Trigonometric", "微分技巧"),
    ("2026-11-05", "3.5 Implicit", "微分技巧"),
    ("2026-11-12", "3.9 Related Rates", "微分與極值"),
    ("2026-11-19", "4.5 Curve Sketching", "微分與極值"),
    ("2026-11-26", "4.2 Mean Value Theorem", "均值定理"),
    ("2026-12-03", "5.1 Areas and Distances", "積分"),
    ("2026-12-10", "5.3 FTC", "微積分基本定理"),
    ("2026-12-17", "7.1 Integration by Parts", "積分技巧"),
]
papers_b = [
    ("2026-09-21", "11.2 Series", "數列與收斂；若還沒教級數可改期"),
    ("2026-09-28", "2.6 Limits at Infinity", "極限"),
    ("2026-10-05", "4.1 Maximum and Minimum Values", "連續與極值"),
    ("2026-10-12", "2.5 再練 IVT", "中間值定理"),
    ("2026-10-19", "3.1 Polynomials and Exponential", "微分"),
    ("2026-10-26", "3.4 Chain Rule", "微分技巧"),
    ("2026-11-09", "3.6 Logarithmic", "微分技巧"),
    ("2026-11-16", "4.3 Shape of a Graph", "微分與極值"),
    ("2026-11-23", "4.7 Optimization", "微分與極值"),
    ("2026-11-30", "4.4 L'Hospital", "均值定理"),
    ("2026-12-07", "5.2 Definite Integral", "積分"),
    ("2026-12-14", "5.5 Substitution", "微積分基本定理"),
    ("2026-12-21", "7.2 Trigonometric Integrals", "積分技巧"),
]
cpe = [
    ("2026-09-11", "10035、10783、11332", "進位／奇數和／數字和"),
    ("2026-09-15", "10041、299、10008", "中位數、交換排序、字頻"),
    ("2026-09-18", "272、10929、10038", "字串、11 的倍數、Jolly"),
    ("2026-09-22", "10189、118、11150", "模擬"),
    ("2026-09-29", "572、439、10004", "DFS／BFS 起手；10/6 前至少做完 572、439"),
    ("2026-10-02", "459、532、336", "圖論"),
    ("2026-10-09", "10653、469", "BFS／DFS"),
    ("2026-10-13", "11953、11518", "DFS"),
    ("2026-10-16", "429、10420", "BFS／字串"),
    ("2026-10-20", "10226、10062", "排序、map"),
    ("2026-10-23", "11417、10235", "數論、質數"),
    ("2026-10-27", "10908、10242", "幾何"),
    ("2026-10-30", "12019、10101", "日期、進位字串"),
    ("2026-11-03", "11005、10931", "進位"),
    ("2026-11-06", "10050、10193", "週期、GCD"),
    ("2026-11-10", "10252、490", "字串"),
    ("2026-11-13", "10222、11349", "字串、矩陣"),
    ("2026-11-17", "10170、10268", "數學"),
    ("2026-11-20", "10812、11461", "數學"),
    ("2026-11-24", "11063、10093", "數列、進位"),
    ("2026-11-27", "948、10019", "進位、位元"),
    ("2026-12-01", "10190、10922", "數學"),
    ("2026-12-04", "10221、10642", "幾何"),
    ("2026-12-11", "10057、10409", "中位數、模擬"),
    ("2026-12-15", "10415、10056", "模擬、機率"),
]
hw_fridays = [
    "2026-09-11", "2026-09-18", "2026-09-25", "2026-10-02", "2026-10-09",
    "2026-10-16", "2026-10-23", "2026-10-30", "2026-11-06", "2026-11-13",
    "2026-11-20", "2026-11-27", "2026-12-04", "2026-12-11", "2026-12-18",
]
projects = [
    "2026-09-16", "2026-09-23", "2026-09-30", "2026-10-07", "2026-10-14",
    "2026-10-21", "2026-11-04", "2026-11-11", "2026-11-18", "2026-11-25",
    "2026-12-02", "2026-12-09", "2026-12-16",
]

items = []

items.append(task(
    "預習數列與收斂（不刷外校卷）",
    "2026-09-10", "21:30",
    note="第一張外校卷 9/17 才開始；今晚只預習",
    level=3,
))

for d, ch, unit in papers_a:
    items.append(task(
        f"外校卷 A：{ch}",
        d, "21:30",
        note=f"當週單元「{unit}」· 周四晚限時對答案 · 陽明交大分章",
        level=5,
    ))
for d, ch, unit in papers_b:
    items.append(task(
        f"外校卷 B：{ch}",
        d, "17:00",
        note=f"同一單元「{unit}」· 週一午 · 共 26 張之一",
        level=5,
        appear_time="14:00",
    ))
    items.append(task(
        f"微積分錯題整理：{ch}",
        d, "21:30",
        note="把 A／B 錯因寫進 templates/calculus-error-log.md",
        level=4,
    ))

for d in hw_fridays:
    note = "蔡老師課內作業優先，外校卷不能排擠"
    if d == "2026-09-25":
        note += " · 中秋可改期"
    items.append(task(
        "課內作業（GPA 優先）",
        d, "17:00",
        note=note,
        level=6,
        appear_time="14:00",
    ))

for d in projects:
    items.append(task(
        "專案時段",
        d, "21:30",
        note="先上完當天課再排",
        level=4,
        category="專案",
    ))

for d, uva, theme in cpe:
    items.append(task(
        f"CPE：UVa {uva}",
        d, "21:30",
        note=f"{theme} · C++ · 瘋狂程設／vjudge／UVa；做不完拖下一格，不跳題",
        level=5,
    ))

items.append(task(
    "CPE 10/6 報名",
    "2026-10-02", "23:59",
    note="官網註冊後報名，大專生免費；報名後無故缺席會取消下一場。窗口 9/22–10/02",
    level=7,
    appear_time=None,
))
items[-1]["appear"] = when("2026-09-22")

items.append(task(
    "CPE 12/8 報名",
    "2026-12-04", "23:59",
    note="本學期主戰場報名窗口 11/24–12/04；報名後無故缺席會取消下一場",
    level=7,
))
items[-1]["appear"] = when("2026-11-24")

plan = {
    "schema": "mywallet-cmd/v1",
    "mode": "apply",
    "note": "115-1 學習計畫：GPA／外校卷 26 張／CPE。課內作業優先；10 月前不深挖 DP。",
    "commands": [
        {"op": "semester.set", "week1Monday": "2026-09-07", "weeks": 18},
        {
            "op": "course.replace_all",
            "courses": [
                {"title": "程式設計（一）", "weekday": 1, "periodStart": 3, "periodEnd": 4,
                 "location": "水湳 卓越大樓 4 樓 404 電腦教室"},
                {"title": "普通心理學", "weekday": 1, "periodStart": 5, "periodEnd": 6,
                 "location": "水湳 關懷大樓 2 樓 203"},
                {"title": "AI 在輔助醫學上的應用實作", "weekday": 1, "periodStart": 7, "periodEnd": 8,
                 "location": "水湳 卓越大樓 4 樓 404"},
                {"title": "體育必修：網球", "weekday": 2, "periodStart": 3, "periodEnd": 4,
                 "location": "水湳網球場"},
                {"title": "微積分", "weekday": 2, "periodStart": 5, "periodEnd": 6,
                 "location": "英才 互助大樓 5B02"},
                {"title": "醫療場域沉浸式實習（一）", "weekday": 2, "periodStart": 7, "periodEnd": 8,
                 "location": "英才 互助大樓 5B03"},
                {"title": "計算機概論", "weekday": 3, "periodStart": 5, "periodEnd": 6,
                 "location": "英才 互助大樓 5B02"},
                {"title": "英文（B）：閱讀與字彙（一）", "weekday": 7, "periodStart": 3, "periodEnd": 4,
                 "location": "水湳 關懷大樓 2 樓 204"},
            ],
        },
        {
            "op": "plan.import",
            "id": "115-1",
            "title": "115-1 學習計畫",
            "note": "三層分開：課內 GPA、微積分打底（26 張外校卷）、CPE 上分。練習軸是蔡老師每周三單元。",
            "items": items,
        },
        event("CPE 10/6", "2026-10-06", "18:40",
              note="穩定 2 題、挑戰 3 題；報到約 17:30；當日一般程式時段取消"),
        event("微積分期中", "2026-10-28",
              note="GPA；當日專案暫停"),
        event("CPE 12/8", "2026-12-08", "18:40",
              note="本學期主戰場：3–4 題；當日一般程式時段取消"),
        event("微積分期末", "2026-12-23",
              note="GPA；鐘點未公布前先佔下午與晚上"),
        event("中秋（練習可改期）", "2026-09-25",
              note="課內作業可改期"),
        event("CPE 10/6 報名開始", "2026-09-22",
              note="大專生免費；10/02 截止"),
        event("CPE 12/8 報名開始", "2026-11-24",
              note="12/04 截止"),
    ],
}

out = Path(r"d:\coding\my-wallet\plan-115-1.mywallet-cmd.json")
out.write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding="utf-8")
print(f"items={len(items)} commands={len(plan['commands'])} bytes={out.stat().st_size}")
print(out)
