const yuan = (cents) => (Number(cents || 0) / 100).toLocaleString("zh-TW", {
  minimumFractionDigits: 0,
  maximumFractionDigits: 2,
});

const TICKET_LABELS = [
  ["tickets", "🎰"],
  ["bjTickets", "🃏"],
  ["scratchTickets", "🎫"],
  ["drawTickets", "🎴"],
  ["lottoTickets", "🎟"],
  ["matchTickets", "🍬"],
  ["mathTickets", "🧮"],
];

/** 對齊手機底欄 + 子頁層級 */
const MAIN_TABS = ["checkin", "tasks", "exam", "wallet", "fun"];
const PAGE_META = {
  checkin: { title: "打卡", parent: null },
  tasks: { title: "任務", parent: null },
  exam: { title: "考試", parent: null },
  wallet: { title: "錢包", parent: null },
  fun: { title: "娛樂", parent: null },
  pass: { title: "本月通行證", parent: "checkin" },
  focus: { title: "專注", parent: "tasks" },
  market: { title: "借貸市場", parent: "wallet" },
  invest: { title: "投資", parent: "wallet" },
  settings: { title: "設定", parent: "wallet" },
};

let currentPage = "checkin";

function showPage(name) {
  if (!PAGE_META[name]) return;
  currentPage = name;
  document.querySelectorAll(".page").forEach((el) => {
    el.hidden = el.dataset.page !== name;
  });
  const meta = PAGE_META[name];
  document.getElementById("pageTitle").textContent = meta.title;
  const back = document.getElementById("btnBack");
  back.hidden = !meta.parent;
  const tabbar = document.querySelector(".tabbar");
  const isMain = MAIN_TABS.includes(name);
  tabbar.hidden = !isMain;
  document.querySelectorAll(".tab").forEach((tab) => {
    tab.classList.toggle("active", tab.dataset.tab === name);
  });
  if (meta.parent) {
    document.querySelectorAll(".tab").forEach((tab) => {
      tab.classList.toggle("active", tab.dataset.tab === meta.parent);
    });
  }
}

async function api(path, opts = {}) {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(opts.headers || {}) },
    ...opts,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.detail || data.message || res.statusText || "請求失敗");
  }
  return data;
}

function toast(msg, isErr = false) {
  const el = document.getElementById("toast");
  el.hidden = false;
  el.textContent = msg;
  el.classList.toggle("err", !!isErr);
  clearTimeout(toast._t);
  toast._t = setTimeout(() => {
    el.hidden = true;
  }, 4200);
}

function fillWalletSelects(wallets) {
  const usable = wallets.filter((w) => !w.isCentral && w.id !== "central_future");
  const ids = ["tfFrom", "tfTo", "loanLender", "loanBorrower"];
  for (const id of ids) {
    const sel = document.getElementById(id);
    if (!sel) continue;
    const prev = sel.value;
    sel.innerHTML = usable
      .map((w) => `<option value="${w.id}">${w.name}（${yuan(w.balanceCents)}）</option>`)
      .join("");
    if ([...sel.options].some((o) => o.value === prev)) sel.value = prev;
  }
  if (!loanLender.value && usable.length) loanLender.value = usable[0].id;
  if (!loanBorrower.value && usable.length > 1) {
    loanBorrower.value = usable.find((w) => w.id !== loanLender.value)?.id || usable[0].id;
  }
}

function renderOverview(o) {
  const list = document.getElementById("walletList");
  list.innerHTML = o.wallets
    .map(
      (w) =>
        `<div class="row"><span>${w.name} <span class="muted">${w.id}</span></span><strong>${yuan(
          w.balanceCents
        )} 元</strong></div>`
    )
    .join("");
  fillWalletSelects(o.wallets);

  const pending = document.getElementById("pendingList");
  const net = Number(o.pendingNetCents || 0);
  const maxAbs = Math.abs(net);
  const netEl = document.getElementById("pendingNet");
  if (!o.pending.length) {
    pending.textContent = "無";
    netEl.textContent = "目前沒有待確認項目";
    document.getElementById("pendingYuan").value = "";
  } else {
    netEl.textContent =
      net === 0
        ? "收支相抵，確認後結算帳目"
        : net > 0
          ? `從撲滿拿取（上限）${yuan(maxAbs)} 元`
          : `放回撲滿（上限）${yuan(maxAbs)} 元`;
    document.getElementById("pendingYuan").value = net === 0 ? "" : String(maxAbs / 100);
    pending.innerHTML = o.pending
      .map(
        (p) =>
          `<div class="row"><span>${p.fromWalletId} → ${p.toWalletId} · ${escapeHtml(
            p.taskTitle || ""
          )}</span><strong>${yuan(p.amountCents)}</strong></div>`
      )
      .join("");
  }

  const ticketHtml = TICKET_LABELS.map(([k, sym]) => {
    const n = o.tickets?.[k] ?? 0;
    return `<span class="chip">${sym} ${n}</span>`;
  }).join("");
  document.getElementById("ticketList").innerHTML = ticketHtml;
  document.getElementById("ticketListFun").innerHTML = ticketHtml;

  const passText = o.pass
    ? `${o.pass.progress}/100`
    : "—";
  document.getElementById("passLine").textContent = passText;
  document.getElementById("passDetail").textContent = o.pass
    ? `${o.pass.title} · 進度 ${o.pass.progress}/100 · changelog ${o.changelogCount} 筆`
    : `尚無通行證 · changelog ${o.changelogCount} 筆`;

  document.getElementById("holdList").innerHTML = o.holdings.length
    ? o.holdings
        .map((h) => {
          const shares = (Number(h.sharesMilli || 0) / 1000).toLocaleString("zh-TW", {
            maximumFractionDigits: 3,
          });
          return `<div>${h.symbol} ×${shares}（成本 ${yuan(h.costCents)}）</div>`;
        })
        .join("")
    : "<div>無</div>";
}

function renderTasks(tasks) {
  const el = document.getElementById("taskList");
  const open = tasks.filter((t) => t.status === "OPEN");
  if (!open.length) {
    el.innerHTML = '<div class="muted">沒有進行中任務</div>';
    return;
  }
  el.innerHTML = open
    .map((t) => {
      const cooldown =
        t.repeatable && t.repeatRuleJson && /"nextAt"\s*:\s*(\d+)/.test(t.repeatRuleJson)
          ? Number(RegExp.$1) > Date.now()
          : false;
      const appearBlocked = t.appearAt && t.appearAt > Date.now();
      const disabled = cooldown || appearBlocked;
      const hint = cooldown ? "冷卻中" : appearBlocked ? "尚未出現" : "";
      return `<div class="row">
        <div>
          <strong>${escapeHtml(t.title)}</strong>
          <div class="muted">#${t.id} · ${t.category}${hint ? " · " + hint : ""}</div>
        </div>
        <div class="actions">
          <button type="button" data-complete="${t.id}" ${disabled ? "disabled" : ""}>完成</button>
          <button type="button" class="danger" data-fail="${t.id}" ${disabled ? "disabled" : ""}>失敗</button>
        </div>
      </div>`;
    })
    .join("");
}

function renderTx(rows) {
  document.getElementById("txList").innerHTML = rows.length
    ? rows
        .map(
          (t) =>
            `<div class="row"><span>${t.type} ${t.fromWalletId || "—"}→${
              t.toWalletId || "—"
            } ${escapeHtml(t.note || "")}</span><strong>${yuan(t.amountCents)}</strong></div>`
        )
        .join("")
    : "<div class='muted'>無</div>";
}

function renderChangelog(entries) {
  const recent = entries.slice(-20).reverse();
  document.getElementById("changelogList").innerHTML = recent.length
    ? recent
        .map(
          (e) =>
            `<div class="row"><span>${e.op}</span><span class="muted">${escapeHtml(
              e.message || ""
            )}</span></div>`
        )
        .join("")
    : "<div class='muted'>尚無異動</div>";
}

function examLabel(item) {
  const s = (item.subject || "").trim();
  const t = (item.title || "").trim();
  if (s && t) return `${s} ${t}`;
  return t || s || `#${item.id}`;
}

function statusLabel(st) {
  return (
    { OPEN: "未完成", COOLDOWN: "冷卻", REVIEWABLE: "可複習", DONE: "完成" }[st] || st
  );
}

function renderExam(data) {
  const p = data.progress || {};
  document.getElementById("examProgress").textContent =
    `初次 ${p.firstDone || 0}/${p.total || 0} · 複習 ${p.reviewDone || 0}/${p.total || 0}`;

  const queue = data.queue || [];
  document.getElementById("examQueue").innerHTML = queue.length
    ? queue
        .map(
          (it) =>
            `<div class="row">
          <div><strong>${escapeHtml(examLabel(it))}</strong>
          <div class="muted">#${it.id} · ${statusLabel(it.status)}</div></div>
          <div class="actions">
            <button type="button" data-exam-complete="${it.id}">完成</button>
            <button type="button" data-exam-dequeue="${it.id}">移出</button>
          </div>
        </div>`
        )
        .join("")
    : '<div class="muted">串列空</div>';

  const items = data.items || [];
  document.getElementById("examItems").innerHTML = items.length
    ? items
        .map((it) => {
          const canDo = it.status === "OPEN" || it.status === "REVIEWABLE";
          const inQ = it.queueOrder != null;
          const cool =
            it.status === "COOLDOWN" && it.reviewAvailableAt
              ? ` · ${Math.max(0, Math.ceil((it.reviewAvailableAt - Date.now()) / 3600000))}h`
              : "";
          return `<div class="row">
          <div><strong>${escapeHtml(examLabel(it))}</strong>
          <div class="muted">#${it.id} · ${statusLabel(it.status)}${cool}</div></div>
          <div class="actions">
            ${
              canDo && !inQ
                ? `<button type="button" data-exam-enqueue="${it.id}">加入串列</button>`
                : ""
            }
            ${
              canDo
                ? `<button type="button" data-exam-complete="${it.id}">完成</button>`
                : ""
            }
            <button type="button" class="danger" data-exam-delete="${it.id}">刪</button>
          </div>
        </div>`;
        })
        .join("")
    : '<div class="muted">無進行中範圍</div>';
}

function renderLoans(data) {
  const list = data.loans || [];
  document.getElementById("loanList").innerHTML = list.length
    ? list
        .map((l) => {
          const due = l.dueAt
            ? new Date(l.dueAt).toLocaleDateString("zh-TW", {
                month: "numeric",
                day: "numeric",
              })
            : "—";
          const rate = ((l.monthlyRateBp || 0) / 100).toFixed(2);
          return `<div class="row">
          <div>
            <strong>#${l.id}</strong> ${l.lenderWalletId}→${l.borrowerWalletId}
            <div class="muted">餘 ${yuan(l.remainingCents)} · ${rate}%/月 · 到期 ${due} · ${l.status}</div>
          </div>
          <div class="actions">
            <button type="button" data-loan-repay="${l.id}">還清</button>
            <button type="button" data-loan-extend="${l.id}">續借</button>
          </div>
        </div>`;
        })
        .join("")
    : '<div class="muted">無進行中借據</div>';

  const rates = data.rates || [];
  document.getElementById("loanRates").textContent = rates.length
    ? "利率：" +
      rates
        .map((r) => `${r.walletId} ${(r.monthlyRateBp / 100).toFixed(2)}%`)
        .join(" · ")
    : "";
}

function renderCal(courses, events) {
  const bits = [];
  if (courses.length) {
    bits.push(
      ...courses.slice(0, 8).map(
        (c) =>
          `<div>週${c.weekday} ${c.periodStart}-${c.periodEnd} ${escapeHtml(c.title)}</div>`
      )
    );
  }
  if (events.length) {
    bits.push(
      ...events
        .slice(0, 8)
        .map((e) => `<div>${e.dateYmd} ${escapeHtml(e.title)}</div>`)
    );
  }
  document.getElementById("calList").innerHTML = bits.length
    ? bits.join("")
    : "<div>無</div>";
}

function escapeHtml(s) {
  return String(s)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;");
}

async function refresh() {
  const [health, overview, tasks, tx, log, courses, events, exam, loanData] =
    await Promise.all([
      api("/api/health"),
      api("/api/overview"),
      api("/api/tasks?status=OPEN"),
      api("/api/transactions?limit=30"),
      api("/api/changelog"),
      api("/api/courses"),
      api("/api/events"),
      api("/api/exams"),
      api("/api/loans"),
    ]);
  document.getElementById("healthLine").textContent =
    `PC 暫代 · v${health.user_version} · OPEN ${health.open_tasks}`;
  renderOverview(overview);
  renderTasks(tasks.tasks);
  renderTx(tx.transactions);
  renderChangelog(log.entries);
  renderCal(courses.courses || [], events.events || []);
  renderExam(exam);
  renderLoans(loanData);
}

async function doExport() {
  const data = await api("/api/export", { method: "POST", body: "{}" });
  const pre = document.getElementById("exportPreview");
  pre.hidden = false;
  pre.textContent = data.fenced;
  await navigator.clipboard.writeText(data.fenced).catch(() => {});
  toast(`已匯出 ${data.count} 筆（已嘗試複製）`);
  showPage("settings");
}

document.getElementById("btnRefresh").onclick = () =>
  refresh().catch((e) => toast(e.message, true));

document.getElementById("btnExport").onclick = () =>
  doExport().catch((e) => toast(e.message, true));
document.getElementById("btnExport2").onclick = () =>
  doExport().catch((e) => toast(e.message, true));

document.getElementById("btnBack").onclick = () => {
  const parent = PAGE_META[currentPage]?.parent;
  if (parent) showPage(parent);
};

document.querySelectorAll(".tab").forEach((tab) => {
  tab.onclick = () => showPage(tab.dataset.tab);
});

document.querySelectorAll("[data-goto]").forEach((btn) => {
  btn.onclick = () => showPage(btn.dataset.goto);
});

document.getElementById("btnConfirmPhysical").onclick = async () => {
  try {
    const raw = pendingYuan.value.trim();
    const body = raw === "" ? {} : { yuan: Number(raw) };
    const r = await api("/api/physical/confirm", {
      method: "POST",
      body: JSON.stringify(body),
    });
    toast(r.message);
    await refresh();
  } catch (e) {
    toast(e.message, true);
  }
};

document.getElementById("formTransfer").onsubmit = async (ev) => {
  ev.preventDefault();
  try {
    const body = {
      fromId: tfFrom.value,
      toId: tfTo.value,
      yuan: Number(tfYuan.value),
      note: tfNote.value,
    };
    const r = await api("/api/transfer", { method: "POST", body: JSON.stringify(body) });
    toast(r.message);
    ev.target.reset();
    await refresh();
  } catch (e) {
    toast(e.message, true);
  }
};

document.getElementById("taskList").onclick = async (ev) => {
  const complete = ev.target.getAttribute("data-complete");
  const fail = ev.target.getAttribute("data-fail");
  if (!complete && !fail) return;
  try {
    const id = Number(complete || fail);
    const path = complete ? "/api/tasks/complete" : "/api/tasks/fail";
    const r = await api(path, { method: "POST", body: JSON.stringify({ id }) });
    toast(r.message);
    await refresh();
  } catch (e) {
    toast(e.message, true);
  }
};

document.getElementById("formTask").onsubmit = async (ev) => {
  ev.preventDefault();
  try {
    const r = await api("/api/tasks/create", {
      method: "POST",
      body: JSON.stringify({
        title: taskTitle.value,
        category: taskCat.value,
        ticketLevel: Number(taskLevel.value),
      }),
    });
    toast(r.message);
    ev.target.reset();
    taskLevel.value = 2;
    taskCat.value = "學業";
    await refresh();
  } catch (e) {
    toast(e.message, true);
  }
};

document.getElementById("formFocus").onsubmit = async (ev) => {
  ev.preventDefault();
  try {
    const r = await api("/api/focus/settle", {
      method: "POST",
      body: JSON.stringify({
        minutes: Number(focusMin.value),
        intent: focusIntent.value,
        category: focusCat.value,
        grantTickets: focusTickets.checked,
      }),
    });
    toast(r.message);
    await refresh();
  } catch (e) {
    toast(e.message, true);
  }
};

document.getElementById("formTicket").onsubmit = async (ev) => {
  ev.preventDefault();
  try {
    const r = await api("/api/tickets/add", {
      method: "POST",
      body: JSON.stringify({ kind: tkKind.value, n: Number(tkN.value) }),
    });
    toast(r.message);
    await refresh();
  } catch (e) {
    toast(e.message, true);
  }
};

document.getElementById("formNote").onsubmit = async (ev) => {
  ev.preventDefault();
  try {
    const r = await api("/api/note", {
      method: "POST",
      body: JSON.stringify({ text: noteText.value }),
    });
    toast(r.message);
    noteText.value = "";
    await refresh();
  } catch (e) {
    toast(e.message, true);
  }
};

document.getElementById("formExam").onsubmit = async (ev) => {
  ev.preventDefault();
  try {
    const units = examUnits.value
      .split(/[,，、\s]+/)
      .map((s) => s.trim())
      .filter(Boolean);
    const r = await api("/api/exams/course", {
      method: "POST",
      body: JSON.stringify({
        subject: examSubject.value,
        note: examNote.value,
        units,
      }),
    });
    toast(r.message);
    examUnits.value = "";
    await refresh();
  } catch (e) {
    toast(e.message, true);
  }
};

document.querySelector('[data-page="exam"]').onclick = async (ev) => {
  const complete = ev.target.getAttribute("data-exam-complete");
  const enqueue = ev.target.getAttribute("data-exam-enqueue");
  const dequeue = ev.target.getAttribute("data-exam-dequeue");
  const del = ev.target.getAttribute("data-exam-delete");
  if (!complete && !enqueue && !dequeue && !del) return;
  try {
    const id = Number(complete || enqueue || dequeue || del);
    let path = "/api/exams/complete";
    if (enqueue) path = "/api/exams/enqueue";
    if (dequeue) path = "/api/exams/dequeue";
    if (del) path = "/api/exams/delete";
    const r = await api(path, { method: "POST", body: JSON.stringify({ id }) });
    toast(r.message);
    await refresh();
  } catch (e) {
    toast(e.message, true);
  }
};

document.getElementById("formBorrow").onsubmit = async (ev) => {
  ev.preventDefault();
  try {
    const r = await api("/api/loans/borrow", {
      method: "POST",
      body: JSON.stringify({
        lender: loanLender.value,
        borrower: loanBorrower.value,
        yuan: Number(loanYuan.value),
        dueThisMonth: loanDueThisMonth.checked,
      }),
    });
    toast(r.message);
    loanYuan.value = "";
    await refresh();
  } catch (e) {
    toast(e.message, true);
  }
};

document.querySelector('[data-page="market"]').onclick = async (ev) => {
  const repay = ev.target.getAttribute("data-loan-repay");
  const extend = ev.target.getAttribute("data-loan-extend");
  if (!repay && !extend) return;
  try {
    if (repay) {
      const r = await api("/api/loans/repay", {
        method: "POST",
        body: JSON.stringify({ id: Number(repay), full: true }),
      });
      toast(r.message);
    } else {
      const r = await api("/api/loans/extend", {
        method: "POST",
        body: JSON.stringify({ id: Number(extend) }),
      });
      toast(r.message);
    }
    await refresh();
  } catch (e) {
    toast(e.message, true);
  }
};

showPage("checkin");
refresh().catch((e) => toast(e.message, true));
