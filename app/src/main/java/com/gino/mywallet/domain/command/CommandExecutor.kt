package com.gino.mywallet.domain.command

import com.gino.mywallet.data.dao.CalendarEventDao
import com.gino.mywallet.data.dao.CourseDao
import com.gino.mywallet.data.dao.ExamDao
import com.gino.mywallet.data.dao.PlanDao
import com.gino.mywallet.data.dao.TaskDao
import com.gino.mywallet.data.entity.CalendarEventEntity
import com.gino.mywallet.data.entity.CourseEntity
import com.gino.mywallet.data.entity.ExamEntity
import com.gino.mywallet.data.entity.ExamItemEntity
import com.gino.mywallet.data.entity.ExamItemStatus
import com.gino.mywallet.data.entity.PlanEntity
import com.gino.mywallet.data.entity.PlanIds
import com.gino.mywallet.data.entity.RepeatRule
import com.gino.mywallet.data.entity.RewardItem
import com.gino.mywallet.data.entity.TaskEntity
import com.gino.mywallet.data.entity.TaskStatus
import com.gino.mywallet.data.entity.WalletCodes
import com.gino.mywallet.data.entity.encodeCourseWeeks
import com.gino.mywallet.data.entity.parseTimeMinutes
import com.gino.mywallet.domain.AcademicCalendar
import com.gino.mywallet.domain.EconomyEngine
import com.gino.mywallet.domain.ExamRules
import com.gino.mywallet.domain.FocusRules
import com.gino.mywallet.domain.PeriodTimes
import com.gino.mywallet.domain.SemesterConfig
import com.gino.mywallet.domain.game.GamePrizes
import com.gino.mywallet.domain.game.TicketKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToLong

class CommandExecutor(
    private val engine: EconomyEngine,
    private val taskDao: TaskDao,
    private val courseDao: CourseDao,
    private val calendarEventDao: CalendarEventDao,
    private val planDao: PlanDao,
    private val examDao: ExamDao
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun run(plan: CommandPlan, dryRun: Boolean = plan.mode == "preview"): CommandRunResult {
        val results = mutableListOf<CommandResultItem>()
        var applied = 0
        var failed = 0

        plan.commands.forEachIndexed { index, cmd ->
            try {
                val item = executeOne(index, cmd, dryRun)
                results += item
                if (item.status == "error") failed++ else applied++
            } catch (e: Exception) {
                failed++
                results += CommandResultItem(
                    index = index,
                    op = opName(cmd),
                    status = "error",
                    message = e.message ?: e.toString(),
                    title = titleOf(cmd)
                )
            }
        }

        return CommandRunResult(
            ok = failed == 0,
            mode = if (dryRun) "preview" else "apply",
            applied = applied,
            failed = failed,
            results = results
        )
    }

    private suspend fun executeOne(
        index: Int,
        cmd: WalletCommand,
        dryRun: Boolean
    ): CommandResultItem {
        return when (cmd) {
            is WalletCommand.TaskCreate -> {
                if (dryRun) {
                    val extra = if (cmd.repeatable) " 可重複" else ""
                    val whenText = previewWhen(cmd)
                    CommandResultItem(
                        index, "task.create", "preview",
                        "將新增任務（${cmd.category} Lv.${cmd.ticketLevel}$extra$whenText）",
                        title = cmd.title
                    )
                } else {
                    val id = insertTask(cmd)
                    engine.bumpRevision()
                    CommandResultItem(index, "task.create", "ok", "已建立", id = id, title = cmd.title)
                }
            }
            is WalletCommand.CourseUpsert -> {
                val summary = "週${PeriodTimes.weekdayLabel(cmd.weekday)} ${cmd.periodStart}-${cmd.periodEnd}節"
                val weekText = if (cmd.weeks.isEmpty()) " 全學期" else " 第${cmd.weeks.joinToString(",")}週"
                if (dryRun) {
                    CommandResultItem(index, "course.upsert", "preview", "將寫入課表：$summary$weekText", title = cmd.title)
                } else {
                    val id = upsertCourse(cmd)
                    engine.bumpRevision()
                    CommandResultItem(index, "course.upsert", "ok", "已寫入 $summary$weekText", id = id, title = cmd.title)
                }
            }
            is WalletCommand.CourseReplaceAll -> {
                if (dryRun) {
                    CommandResultItem(
                        index, "course.replace_all", "preview",
                        "將清空課表並寫入 ${cmd.courses.size} 堂課",
                        title = "課表×${cmd.courses.size}"
                    )
                } else {
                    courseDao.clear()
                    cmd.courses.forEach { upsertCourse(it) }
                    engine.bumpRevision()
                    CommandResultItem(
                        index, "course.replace_all", "ok",
                        "已替換為 ${cmd.courses.size} 堂課",
                        title = "課表×${cmd.courses.size}"
                    )
                }
            }
            is WalletCommand.CourseDelete -> {
                val existing = courseDao.getById(cmd.id) ?: error("課表不存在 #${cmd.id}")
                if (dryRun) {
                    CommandResultItem(
                        index, "course.delete", "preview",
                        "將刪除課表 #${cmd.id}",
                        id = cmd.id, title = existing.title
                    )
                } else {
                    courseDao.deleteById(cmd.id)
                    engine.bumpRevision()
                    CommandResultItem(
                        index, "course.delete", "ok",
                        "已刪除",
                        id = cmd.id, title = existing.title
                    )
                }
            }
            is WalletCommand.EventCreate -> {
                val date = resolveEventDate(cmd)
                val timeLabel = cmd.time ?: "全日"
                if (dryRun) {
                    CommandResultItem(
                        index, "event.create", "preview",
                        "將新增行程 $date $timeLabel",
                        title = cmd.title
                    )
                } else {
                    val id = insertEvent(cmd, date)
                    engine.bumpRevision()
                    CommandResultItem(
                        index, "event.create", "ok",
                        "已建立 $date $timeLabel",
                        id = id, title = cmd.title
                    )
                }
            }
            is WalletCommand.EventUpdate -> {
                val existing = calendarEventDao.getById(cmd.id) ?: error("行程不存在 #${cmd.id}")
                val semester = engine.loadSemester()
                val newDate = when {
                    cmd.date != null -> LocalDate.parse(cmd.date)
                    cmd.week != null && cmd.weekday != null ->
                        resolveDate(cmd.date, cmd.week, cmd.weekday, semester)
                    else -> LocalDate.parse(existing.dateYmd)
                }
                val newMinutes = when {
                    cmd.clearTime -> -1
                    cmd.time != null -> parseTimeMinutes(cmd.time) ?: error("時間格式無效：${cmd.time}")
                    else -> existing.timeMinutes
                }
                val updated = existing.copy(
                    title = cmd.title?.trim()?.ifBlank { existing.title } ?: existing.title,
                    dateYmd = newDate.toString(),
                    timeMinutes = newMinutes,
                    location = cmd.location ?: existing.location,
                    note = cmd.note ?: existing.note
                )
                if (dryRun) {
                    CommandResultItem(
                        index, "event.update", "preview",
                        "將更新行程 #${cmd.id} → ${updated.dateYmd}",
                        id = cmd.id, title = updated.title
                    )
                } else {
                    calendarEventDao.upsert(updated)
                    engine.bumpRevision()
                    CommandResultItem(
                        index, "event.update", "ok",
                        "已更新 ${updated.dateYmd}",
                        id = cmd.id, title = updated.title
                    )
                }
            }
            is WalletCommand.EventDelete -> {
                val existing = calendarEventDao.getById(cmd.id) ?: error("行程不存在 #${cmd.id}")
                if (dryRun) {
                    CommandResultItem(
                        index, "event.delete", "preview",
                        "將刪除行程 #${cmd.id}",
                        id = cmd.id, title = existing.title
                    )
                } else {
                    calendarEventDao.deleteById(cmd.id)
                    engine.bumpRevision()
                    CommandResultItem(
                        index, "event.delete", "ok",
                        "已刪除",
                        id = cmd.id, title = existing.title
                    )
                }
            }
            is WalletCommand.TaskUpdate -> {
                val existing = resolveOpenTaskForUpdate(cmd)
                val updated = applyTaskUpdate(existing, cmd)
                if (dryRun) {
                    CommandResultItem(
                        index, "task.update", "preview",
                        "將更新任務 #${existing.id}",
                        id = existing.id, title = updated.title
                    )
                } else {
                    taskDao.update(updated)
                    engine.bumpRevision()
                    CommandResultItem(
                        index, "task.update", "ok",
                        "已更新",
                        id = existing.id, title = updated.title
                    )
                }
            }
            is WalletCommand.IncomeRecord -> {
                val cents = (cmd.yuan * 100.0).roundToLong()
                require(cents > 0) { "金額須 > 0" }
                if (dryRun) {
                    CommandResultItem(
                        index, "income.record", "preview",
                        "將記收入 ${cmd.yuan} 元（deferCentral=${cmd.deferCentral}）",
                        title = cmd.note.ifBlank { "收入" }
                    )
                } else {
                    engine.recordIncomeStandard(cents, cmd.note, cmd.deferCentral)
                    CommandResultItem(
                        index, "income.record", "ok",
                        "已記收入 ${cmd.yuan} 元",
                        title = cmd.note.ifBlank { "收入" }
                    )
                }
            }
            is WalletCommand.RewardGrant -> {
                if (dryRun) {
                    val parts = buildList {
                        if (cmd.yuan != null) add("${cmd.yuan} 元→${cmd.to}")
                        if (cmd.ticketKind != null) add("${cmd.ticketKind}×${cmd.ticketN}")
                    }
                    CommandResultItem(
                        index, "reward.grant", "preview",
                        "將發獎 ${parts.joinToString("、")}（nonce=${cmd.nonce}）",
                        title = cmd.note.ifBlank { "發獎" }
                    )
                } else {
                    val (fresh, msg) = engine.grantBotReward(
                        nonce = cmd.nonce,
                        yuan = cmd.yuan,
                        toWalletId = cmd.to,
                        ticketKind = cmd.ticketKind,
                        ticketN = cmd.ticketN,
                        note = cmd.note
                    )
                    CommandResultItem(
                        index, "reward.grant", "ok",
                        if (fresh) msg else "略過重複：$msg",
                        title = cmd.note.ifBlank { "發獎" }
                    )
                }
            }
            is WalletCommand.TaskDelete -> {
                val target = resolveTaskForDelete(cmd)
                if (dryRun) {
                    CommandResultItem(
                        index, "task.delete", "preview",
                        "將刪除任務 #${target.id}",
                        id = target.id, title = target.title
                    )
                } else {
                    taskDao.deleteByIds(listOf(target.id))
                    engine.bumpRevision()
                    CommandResultItem(
                        index, "task.delete", "ok",
                        "已刪除 #${target.id}",
                        id = target.id, title = target.title
                    )
                }
            }
            is WalletCommand.SemesterSet -> {
                val monday = AcademicCalendar.normalizeWeek1Monday(LocalDate.parse(cmd.week1Monday))
                if (dryRun) {
                    CommandResultItem(
                        index, "semester.set", "preview",
                        "將設定第1週週一 $monday、共 ${cmd.weeks} 週",
                        title = "學期"
                    )
                } else {
                    val saved = engine.saveSemester(monday, cmd.weeks)
                    CommandResultItem(
                        index, "semester.set", "ok",
                        "已設定第1週週一 ${saved.week1Monday}、共 ${saved.weeks} 週",
                        title = "學期"
                    )
                }
            }
            is WalletCommand.PlanUpsert -> {
                if (dryRun) {
                    CommandResultItem(index, "plan.upsert", "preview", "將寫入計畫", title = cmd.title)
                } else {
                    engine.upsertPlan(cmd.id, cmd.title, cmd.note)
                    CommandResultItem(index, "plan.upsert", "ok", "已寫入計畫", title = cmd.title)
                }
            }
            is WalletCommand.PlanImport -> {
                if (dryRun) {
                    CommandResultItem(
                        index, "plan.import", "preview",
                        "將匯入計畫「${cmd.title}」共 ${cmd.items.size} 項",
                        title = cmd.title
                    )
                } else {
                    engine.upsertPlan(cmd.id, cmd.title, cmd.note)
                    cmd.items.forEach { item ->
                        insertTask(item.copy(planId = cmd.id))
                    }
                    if (cmd.items.isNotEmpty()) engine.bumpRevision()
                    CommandResultItem(
                        index, "plan.import", "ok",
                        "已匯入 ${cmd.items.size} 項",
                        title = cmd.title
                    )
                }
            }
            is WalletCommand.WalletTransfer -> {
                val cents = (cmd.yuan * 100.0).roundToLong()
                require(cents > 0) { "金額須 > 0" }
                if (dryRun) {
                    CommandResultItem(
                        index, "wallet.transfer", "preview",
                        "將轉帳 ${cmd.yuan} 元 ${cmd.from}→${cmd.to}",
                        title = cmd.note.ifBlank { "${cmd.from}→${cmd.to}" }
                    )
                } else {
                    val deferred = engine.transfer(cmd.from, cmd.to, cents, cmd.note)
                    CommandResultItem(
                        index, "wallet.transfer", "ok",
                        if (deferred) "已加入待轉移 ${cmd.yuan} 元" else "已轉帳 ${cmd.yuan} 元",
                        title = cmd.note.ifBlank { "${cmd.from}→${cmd.to}" }
                    )
                }
            }
            is WalletCommand.TaskComplete -> {
                val task = resolveOpenTask(cmd.id, cmd.title)
                if (dryRun) {
                    CommandResultItem(
                        index, "task.complete", "preview",
                        "將完成任務 #${task.id}",
                        id = task.id, title = task.title
                    )
                } else {
                    engine.completeTask(task.id)
                    CommandResultItem(
                        index, "task.complete", "ok",
                        "已完成",
                        id = task.id, title = task.title
                    )
                }
            }
            is WalletCommand.TaskFail -> {
                val task = resolveOpenTask(cmd.id, cmd.title)
                if (dryRun) {
                    CommandResultItem(
                        index, "task.fail", "preview",
                        "將標記失敗 #${task.id}",
                        id = task.id, title = task.title
                    )
                } else {
                    engine.failTask(task.id)
                    CommandResultItem(
                        index, "task.fail", "ok",
                        "已標記失敗",
                        id = task.id, title = task.title
                    )
                }
            }
            is WalletCommand.FocusSettle -> {
                val ms = cmd.minutes.coerceAtLeast(0) * 60_000L
                val cash = cmd.cashYuan ?: FocusRules.longCashYuan(
                    FocusRules.ensureCashSteps(emptyList(), FocusRules.cashStepCount(ms))
                )
                if (dryRun) {
                    CommandResultItem(
                        index, "focus.settle", "preview",
                        "將結算專注 ${cmd.minutes} 分（現金 $cash 元${if (cmd.grantTickets) "＋券" else ""}）",
                        title = cmd.intent
                    )
                } else {
                    val (pack, deferred) = engine.settleFocusAndRecord(
                        cashYuan = cash,
                        effectiveMs = ms,
                        grantTickets = cmd.grantTickets,
                        intent = cmd.intent,
                        category = cmd.category
                    )
                    val ticketText = GamePrizes.formatTicketMap(pack).ifBlank { "無券" }
                    val cashText = if (cash >= 0) "+$cash 元" else "$cash 元"
                    CommandResultItem(
                        index, "focus.settle", "ok",
                        if (deferred) "專注結算：$cashText 已加入待轉移，$ticketText"
                        else "專注結算：$cashText，$ticketText",
                        title = cmd.intent
                    )
                }
            }
            is WalletCommand.TicketAdd -> {
                val kind = parseTicketKind(cmd.kind)
                if (dryRun) {
                    CommandResultItem(
                        index, "ticket.add", "preview",
                        "將加券 ${kind.symbol}×${cmd.n}",
                        title = kind.label
                    )
                } else {
                    engine.addTickets(kind, cmd.n)
                    CommandResultItem(
                        index, "ticket.add", "ok",
                        "已加 ${kind.symbol}×${cmd.n}",
                        title = kind.label
                    )
                }
            }
            is WalletCommand.Note -> {
                CommandResultItem(
                    index, "note",
                    if (dryRun) "preview" else "ok",
                    cmd.text,
                    title = "備註"
                )
            }
            is WalletCommand.ExamSaveCourse -> {
                if (dryRun) {
                    CommandResultItem(
                        index, "exam.save_course", "preview",
                        "將儲存課程「${cmd.subject}」+${cmd.units.size} 單元",
                        title = cmd.subject
                    )
                } else {
                    val exam = ensureActiveExam()
                    examDao.upsertSubjectNote(exam.id, cmd.subject, cmd.note)
                    cmd.units.forEach { unit ->
                        examDao.upsertItem(
                            ExamItemEntity(
                                examId = exam.id,
                                subject = cmd.subject,
                                title = unit,
                                status = ExamItemStatus.OPEN
                            )
                        )
                    }
                    CommandResultItem(
                        index, "exam.save_course", "ok",
                        if (cmd.units.isEmpty()) "已儲存" else "已儲存（+${cmd.units.size} 單元）",
                        title = cmd.subject
                    )
                }
            }
            is WalletCommand.ExamComplete -> {
                refreshExamReviews()
                val item = resolveExamItem(cmd.id, cmd.subject, cmd.title)
                if (dryRun) {
                    CommandResultItem(
                        index, "exam.complete", "preview",
                        "將完成 ${ExamRules.displayLabel(item)}（${item.status}）",
                        id = item.id, title = ExamRules.displayLabel(item)
                    )
                } else {
                    val msg = completeExamItem(item)
                    CommandResultItem(
                        index, "exam.complete", "ok", msg,
                        id = item.id, title = ExamRules.displayLabel(item)
                    )
                }
            }
            is WalletCommand.ExamEnqueue -> {
                refreshExamReviews()
                val item = resolveExamItem(cmd.id, cmd.subject, cmd.title)
                if (dryRun) {
                    CommandResultItem(
                        index, "exam.enqueue", "preview",
                        "將加入串列",
                        id = item.id, title = ExamRules.displayLabel(item)
                    )
                } else {
                    require(ExamRules.canEnqueue(item)) { "倒數中或已複習的範圍不能加入串列" }
                    if (item.queueOrder != null) {
                        CommandResultItem(
                            index, "exam.enqueue", "ok", "已在串列中",
                            id = item.id, title = ExamRules.displayLabel(item)
                        )
                    } else {
                        val queued = ExamRules.queueSorted(examDao.getItems(item.examId))
                        examDao.updateItem(item.copy(queueOrder = queued.size))
                        CommandResultItem(
                            index, "exam.enqueue", "ok", "已加入串列",
                            id = item.id, title = ExamRules.displayLabel(item)
                        )
                    }
                }
            }
            is WalletCommand.ExamDequeue -> {
                val item = resolveExamItem(cmd.id, cmd.subject, cmd.title)
                if (dryRun) {
                    CommandResultItem(
                        index, "exam.dequeue", "preview",
                        "將移出串列",
                        id = item.id, title = ExamRules.displayLabel(item)
                    )
                } else {
                    if (item.queueOrder != null) {
                        examDao.updateItem(item.copy(queueOrder = null))
                        renormalizeExamQueue(item.examId)
                    }
                    CommandResultItem(
                        index, "exam.dequeue", "ok", "已移出串列",
                        id = item.id, title = ExamRules.displayLabel(item)
                    )
                }
            }
            is WalletCommand.ExamDeleteItem -> {
                val item = resolveExamItem(cmd.id, cmd.subject, cmd.title)
                if (dryRun) {
                    CommandResultItem(
                        index, "exam.delete_item", "preview",
                        "將刪除範圍",
                        id = item.id, title = ExamRules.displayLabel(item)
                    )
                } else {
                    examDao.deleteItem(item.id)
                    renormalizeExamQueue(item.examId)
                    CommandResultItem(
                        index, "exam.delete_item", "ok", "已刪除範圍",
                        id = item.id, title = ExamRules.displayLabel(item)
                    )
                }
            }
            is WalletCommand.LoanBorrow -> {
                val cents = (cmd.yuan * 100.0).roundToLong()
                require(cents > 0) { "金額須 > 0" }
                if (dryRun) {
                    CommandResultItem(
                        index, "loan.borrow", "preview",
                        "將借款 ${cmd.yuan} 元 ${cmd.lender}→${cmd.borrower}" +
                            if (cmd.dueThisMonth) "（本月還）" else "（下月還）",
                        title = "${cmd.lender}→${cmd.borrower}"
                    )
                } else {
                    val deferred = engine.borrowFromWallet(
                        cmd.lender, cmd.borrower, cents, cmd.dueThisMonth
                    )
                    CommandResultItem(
                        index, "loan.borrow", "ok",
                        if (deferred) "借款已成立，待轉移確認後才入帳" else "借款成立",
                        title = "${cmd.lender}→${cmd.borrower}"
                    )
                }
            }
            is WalletCommand.LoanRepay -> {
                val cents = (cmd.yuan * 100.0).roundToLong()
                require(cents > 0) { "金額須 > 0" }
                if (dryRun) {
                    CommandResultItem(
                        index, "loan.repay", "preview",
                        "將還款 #${cmd.id} ${cmd.yuan} 元",
                        id = cmd.id, title = "還款"
                    )
                } else {
                    val deferred = engine.repayLoan(cmd.id, cents)
                    CommandResultItem(
                        index, "loan.repay", "ok",
                        if (deferred) "已加入待轉移，確認後才入帳" else "還款完成",
                        id = cmd.id, title = "還款"
                    )
                }
            }
            is WalletCommand.LoanExtend -> {
                if (dryRun) {
                    CommandResultItem(
                        index, "loan.extend", "preview",
                        "將續借 #${cmd.id}",
                        id = cmd.id, title = "續借"
                    )
                } else {
                    val loan = engine.extendLoan(cmd.id)
                    val due = java.time.Instant.ofEpochMilli(loan.dueAt)
                        .atZone(zone).toLocalDate()
                    CommandResultItem(
                        index, "loan.extend", "ok",
                        "已續借至 ${due.monthValue}/${due.dayOfMonth}",
                        id = cmd.id, title = "續借"
                    )
                }
            }
            is WalletCommand.WalletRate -> {
                val bp = (cmd.percent * 100.0).roundToLong().toInt().coerceAtLeast(0)
                if (dryRun) {
                    CommandResultItem(
                        index, "wallet.rate", "preview",
                        "將把 ${cmd.walletId} 月利率改為 ${cmd.percent}%",
                        title = cmd.walletId
                    )
                } else {
                    engine.updateWalletRate(cmd.walletId, bp)
                    CommandResultItem(
                        index, "wallet.rate", "ok",
                        "已設為 ${cmd.percent}%/月",
                        title = cmd.walletId
                    )
                }
            }
            is WalletCommand.WalletConfirmPhysical -> {
                val cents = when {
                    cmd.yuan == null -> Long.MAX_VALUE
                    cmd.yuan == 0.0 -> 0L
                    else -> (cmd.yuan * 100.0).roundToLong()
                }
                if (dryRun) {
                    CommandResultItem(
                        index, "wallet.confirm_physical", "preview",
                        if (cmd.yuan == null) "將全額確認待轉移"
                        else "將確認待轉移 ${cmd.yuan} 元",
                        title = "待轉移"
                    )
                } else {
                    engine.confirmPhysicalTransfers(cents)
                    CommandResultItem(
                        index, "wallet.confirm_physical", "ok",
                        if (cmd.yuan == null) "已確認待轉移（全額）"
                        else "已確認待轉移 ${cmd.yuan} 元",
                        title = "待轉移"
                    )
                }
            }
        }
    }

    private suspend fun ensureActiveExam(): ExamEntity {
        val existing = examDao.getActiveExam()
        if (existing != null) return existing
        val id = examDao.upsertExam(ExamEntity(title = "考試", archived = false))
        return examDao.getExamById(id) ?: error("無法建立考試")
    }

    private suspend fun refreshExamReviews() {
        val exam = examDao.getActiveExam() ?: return
        val now = System.currentTimeMillis()
        ExamRules.itemsDueForReview(examDao.getItems(exam.id), now).forEach { item ->
            examDao.updateItem(item.copy(status = ExamItemStatus.REVIEWABLE))
        }
    }

    private suspend fun renormalizeExamQueue(examId: Long) {
        val queued = ExamRules.queueSorted(examDao.getItems(examId))
        ExamRules.normalizeQueueOrders(queued).forEach { examDao.updateItem(it) }
    }

    private suspend fun resolveExamItem(
        id: Long?,
        subject: String?,
        title: String?
    ): ExamItemEntity {
        require(id != null || !title.isNullOrBlank()) { "須提供 id 或 title" }
        if (id != null) {
            return examDao.getItemById(id) ?: error("範圍不存在 #$id")
        }
        val exam = examDao.getActiveExam() ?: error("尚無進行中考試")
        val matches = examDao.getItems(exam.id).filter { item ->
            item.title == title &&
                (subject.isNullOrBlank() || item.subject == subject)
        }
        when {
            matches.isEmpty() -> error("找不到範圍「${subject?.let { "$it " } ?: ""}$title」")
            matches.size > 1 -> error("範圍標題重複，請改用 id")
            else -> return matches.single()
        }
    }

    private suspend fun completeExamItem(item: ExamItemEntity): String {
        val now = System.currentTimeMillis()
        val updated = ExamRules.complete(item, now).getOrElse { throw it }
        examDao.updateItem(updated)
        renormalizeExamQueue(item.examId)
        val label = ExamRules.displayLabel(item)
        val taskTitle = when (updated.status) {
            ExamItemStatus.DONE -> "複習 · $label"
            else -> label
        }
        val taskId = taskDao.insert(
            TaskEntity(
                title = taskTitle,
                note = "考試",
                category = "學業",
                rewardJson = engine.rewardsToJson(
                    listOf(RewardItem(kind = "TICKET_LEVEL", level = 4))
                ),
                status = TaskStatus.OPEN
            )
        )
        engine.completeTask(taskId)
        val phase = when (updated.status) {
            ExamItemStatus.COOLDOWN -> "初次完成"
            ExamItemStatus.DONE -> "複習完成"
            else -> "已完成"
        }
        return "$phase · Lv.4 券包"
    }

    private suspend fun resolveOpenTask(id: Long?, title: String?): TaskEntity {
        require(id != null || !title.isNullOrBlank()) { "須提供 id 或 title" }
        if (id != null) {
            val task = taskDao.getById(id) ?: error("任務不存在 #$id")
            require(task.status == TaskStatus.OPEN) { "任務 #$id 不是進行中（${task.status}）" }
            return task
        }
        val matches = taskDao.getAll().filter {
            it.status == TaskStatus.OPEN && it.title == title
        }
        when {
            matches.isEmpty() -> error("找不到進行中任務「$title」")
            matches.size > 1 -> error("標題「$title」有 ${matches.size} 筆進行中，請改用 id")
            else -> return matches.single()
        }
    }

    private fun parseTicketKind(raw: String): TicketKind {
        val key = raw.trim().uppercase()
        return TicketKind.entries.find { it.name == key }
            ?: TicketKind.entries.find { it.symbol == raw.trim() }
            ?: error("未知券種：$raw（可用 SLOT/BJ/SCRATCH/DRAW/LOTTO/MATCH/MATH）")
    }

    private suspend fun insertTask(cmd: WalletCommand.TaskCreate): Long {
        val rewards = mutableListOf<RewardItem>()
        val level = cmd.ticketLevel.coerceIn(1, GamePrizes.MAX_TICKET_LEVEL)
        rewards += RewardItem(kind = "TICKET_LEVEL", level = level)
        if (cmd.rewardYuan != null) {
            rewards += RewardItem(
                kind = "TRANSFER",
                amountCents = cmd.rewardYuan * 100,
                fromWalletId = WalletCodes.CENTRAL,
                toWalletId = cmd.rewardTo
            )
        }
        val penalties = mutableListOf<RewardItem>()
        if (cmd.penaltyYuan != null) {
            penalties += RewardItem(
                kind = "TRANSFER",
                amountCents = cmd.penaltyYuan * 100,
                fromWalletId = cmd.penaltyFrom,
                toWalletId = WalletCodes.CENTRAL
            )
        }
        val ruleJson = if (cmd.repeatable) {
            engine.repeatRuleToJson(
                RepeatRule(
                    freq = cmd.repeatFreq,
                    n = cmd.repeatN,
                    nextAt = 0L
                )
            )
        } else ""

        val semester = engine.loadSemester()
        val plannedAt = cmd.planned?.let { resolveEpoch(it, LocalTime.MIDNIGHT, semester) }
        val planId = cmd.planId?.let { PlanIds.requireValid(it) }
        if (planId != null && planDao.getById(planId) == null) {
            planDao.upsert(PlanEntity(id = planId, title = planId))
        }

        return taskDao.insert(
            TaskEntity(
                title = cmd.title,
                note = cmd.note,
                category = cmd.category,
                repeatable = cmd.repeatable,
                repeatRuleJson = ruleJson,
                rewardJson = engine.rewardsToJson(rewards),
                penaltyJson = engine.rewardsToJson(penalties),
                plannedAt = plannedAt,
                planId = planId
            )
        )
    }

    private suspend fun upsertCourse(cmd: WalletCommand.CourseUpsert): Long {
        val existing = courseDao.getAll().find {
            it.title == cmd.title && it.weekday == cmd.weekday && it.periodStart == cmd.periodStart
        }
        val semester = engine.loadSemester()
        return courseDao.upsert(
            CourseEntity(
                id = existing?.id ?: 0L,
                title = cmd.title,
                weekday = cmd.weekday,
                periodStart = cmd.periodStart,
                periodEnd = cmd.periodEnd,
                location = cmd.location,
                weeksJson = encodeCourseWeeks(cmd.weeks, semester.weeks)
            )
        )
    }

    private suspend fun insertEvent(cmd: WalletCommand.EventCreate, date: LocalDate): Long {
        val minutes = if (cmd.time.isNullOrBlank()) {
            -1
        } else {
            parseTimeMinutes(cmd.time) ?: error("時間格式無效：${cmd.time}")
        }
        return calendarEventDao.insert(
            CalendarEventEntity(
                title = cmd.title,
                dateYmd = date.toString(),
                timeMinutes = minutes,
                location = cmd.location,
                note = cmd.note
            )
        )
    }

    private suspend fun resolveEventDate(cmd: WalletCommand.EventCreate): LocalDate =
        resolveDate(cmd.date, cmd.week, cmd.weekday, engine.loadSemester())

    private suspend fun previewWhen(cmd: WalletCommand.TaskCreate): String {
        if (cmd.planned == null) return ""
        return try {
            val semester = engine.loadSemester()
            " 預計=${resolveDate(cmd.planned, semester)}"
        } catch (e: Exception) {
            "（時間無法解析：${e.message}）"
        }
    }

    private fun resolveEpoch(spec: CommandWhen, defaultTime: LocalTime, semester: SemesterConfig): Long {
        val date = resolveDate(spec, semester)
        val time = parseClock(spec.time) ?: defaultTime
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    private fun resolveDate(spec: CommandWhen, semester: SemesterConfig): LocalDate =
        resolveDate(spec.date, spec.week, spec.weekday, semester)

    private fun resolveDate(
        date: String?,
        week: Int?,
        weekday: Int?,
        semester: SemesterConfig
    ): LocalDate {
        if (!date.isNullOrBlank()) return LocalDate.parse(date)
        require(week != null && weekday != null) { "須提供 date 或 week+weekday" }
        return AcademicCalendar.dateOf(week, weekday, semester)
    }

    private fun parseClock(raw: String?): LocalTime? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(":")
        require(parts.size == 2) { "time 須為 HH:mm" }
        val h = parts[0].toInt()
        val m = parts[1].toInt()
        require(h in 0..23 && m in 0..59) { "time 無效" }
        return LocalTime.of(h, m)
    }

    private suspend fun resolveOpenTaskForUpdate(cmd: WalletCommand.TaskUpdate): TaskEntity {
        if (cmd.id != null) {
            val existing = taskDao.getById(cmd.id) ?: error("任務不存在 #${cmd.id}")
            require(existing.status == TaskStatus.OPEN) { "僅能修改進行中任務" }
            return existing
        }
        val needle = cmd.titleContains?.trim().orEmpty()
        require(needle.isNotBlank()) { "須提供 id 或 titleContains" }
        val matches = taskDao.getOpen().filter { it.title.contains(needle) }
        when (matches.size) {
            0 -> error("找不到標題含「$needle」的進行中任務")
            1 -> return matches[0]
            else -> error("標題含「$needle」命中 ${matches.size} 筆進行中任務，請改用 id")
        }
    }

    private suspend fun resolveTaskForDelete(cmd: WalletCommand.TaskDelete): TaskEntity {
        if (cmd.id != null) {
            val existing = taskDao.getById(cmd.id) ?: error("任務不存在 #${cmd.id}")
            if (cmd.status != "ALL") {
                require(existing.status == cmd.status) {
                    "任務 #${cmd.id} 狀態為 ${existing.status}，與 status=${cmd.status} 不符"
                }
            }
            return existing
        }
        val needle = cmd.titleContains?.trim().orEmpty()
        require(needle.isNotBlank()) { "須提供 id 或 titleContains" }
        val matches = taskDao.getAll().filter {
            it.title.contains(needle) && (cmd.status == "ALL" || it.status == cmd.status)
        }
        when (matches.size) {
            0 -> error("找不到標題含「$needle」的任務（status=${cmd.status}）")
            1 -> return matches[0]
            else -> error("標題含「$needle」命中 ${matches.size} 筆，請改用 id")
        }
    }

    private suspend fun applyTaskUpdate(
        existing: TaskEntity,
        cmd: WalletCommand.TaskUpdate
    ): TaskEntity {
        val semester = engine.loadSemester()
        var rewards = engine.parseRewards(existing.rewardJson).toMutableList()
        if (cmd.ticketLevel != null) {
            rewards.removeAll { it.kind == "TICKET_LEVEL" }
            rewards += RewardItem(kind = "TICKET_LEVEL", level = cmd.ticketLevel.coerceIn(1, GamePrizes.MAX_TICKET_LEVEL))
        }
        if (cmd.clearReward) {
            rewards.removeAll { it.kind == "TRANSFER" }
        } else if (cmd.rewardYuan != null) {
            rewards.removeAll { it.kind == "TRANSFER" }
            rewards += RewardItem(
                kind = "TRANSFER",
                amountCents = cmd.rewardYuan * 100,
                fromWalletId = WalletCodes.CENTRAL,
                toWalletId = cmd.rewardTo ?: "living"
            )
        }
        var penalties = engine.parseRewards(existing.penaltyJson).toMutableList()
        if (cmd.clearPenalty) {
            penalties = mutableListOf()
        } else if (cmd.penaltyYuan != null) {
            penalties = mutableListOf(
                RewardItem(
                    kind = "TRANSFER",
                    amountCents = cmd.penaltyYuan * 100,
                    fromWalletId = cmd.penaltyFrom ?: "living",
                    toWalletId = WalletCodes.CENTRAL
                )
            )
        }
        val repeatable = cmd.repeatable ?: existing.repeatable
        val ruleJson = when {
            !repeatable -> ""
            cmd.repeatFreq != null || cmd.repeatN != null || cmd.repeatable == true -> {
                val cur = engine.parseRepeatRule(existing.repeatRuleJson)
                engine.repeatRuleToJson(
                    RepeatRule(
                        freq = cmd.repeatFreq ?: cur.freq,
                        n = cmd.repeatN ?: cur.n,
                        nextAt = cur.nextAt
                    )
                )
            }
            else -> existing.repeatRuleJson
        }
        val plannedAt = when {
            cmd.clearPlanned -> null
            cmd.planned != null -> resolveEpoch(cmd.planned, LocalTime.MIDNIGHT, semester)
            else -> existing.plannedAt
        }
        val planId = when {
            cmd.clearPlanId -> null
            cmd.planId != null -> PlanIds.requireValid(cmd.planId)
            else -> existing.planId
        }
        return existing.copy(
            title = cmd.title?.trim()?.ifBlank { existing.title } ?: existing.title,
            category = cmd.category ?: existing.category,
            note = cmd.note ?: existing.note,
            repeatable = repeatable,
            repeatRuleJson = ruleJson,
            rewardJson = engine.rewardsToJson(rewards),
            penaltyJson = engine.rewardsToJson(penalties),
            plannedAt = plannedAt,
            planId = planId
        )
    }

    private fun opName(cmd: WalletCommand): String = when (cmd) {
        is WalletCommand.TaskCreate -> "task.create"
        is WalletCommand.CourseUpsert -> "course.upsert"
        is WalletCommand.CourseReplaceAll -> "course.replace_all"
        is WalletCommand.CourseDelete -> "course.delete"
        is WalletCommand.EventCreate -> "event.create"
        is WalletCommand.EventUpdate -> "event.update"
        is WalletCommand.EventDelete -> "event.delete"
        is WalletCommand.TaskUpdate -> "task.update"
        is WalletCommand.IncomeRecord -> "income.record"
        is WalletCommand.RewardGrant -> "reward.grant"
        is WalletCommand.TaskDelete -> "task.delete"
        is WalletCommand.SemesterSet -> "semester.set"
        is WalletCommand.PlanUpsert -> "plan.upsert"
        is WalletCommand.PlanImport -> "plan.import"
        is WalletCommand.WalletTransfer -> "wallet.transfer"
        is WalletCommand.TaskComplete -> "task.complete"
        is WalletCommand.TaskFail -> "task.fail"
        is WalletCommand.FocusSettle -> "focus.settle"
        is WalletCommand.TicketAdd -> "ticket.add"
        is WalletCommand.Note -> "note"
        is WalletCommand.ExamSaveCourse -> "exam.save_course"
        is WalletCommand.ExamComplete -> "exam.complete"
        is WalletCommand.ExamEnqueue -> "exam.enqueue"
        is WalletCommand.ExamDequeue -> "exam.dequeue"
        is WalletCommand.ExamDeleteItem -> "exam.delete_item"
        is WalletCommand.LoanBorrow -> "loan.borrow"
        is WalletCommand.LoanRepay -> "loan.repay"
        is WalletCommand.LoanExtend -> "loan.extend"
        is WalletCommand.WalletRate -> "wallet.rate"
        is WalletCommand.WalletConfirmPhysical -> "wallet.confirm_physical"
    }

    private fun titleOf(cmd: WalletCommand): String? = when (cmd) {
        is WalletCommand.TaskCreate -> cmd.title
        is WalletCommand.CourseUpsert -> cmd.title
        is WalletCommand.CourseReplaceAll -> "課表×${cmd.courses.size}"
        is WalletCommand.CourseDelete -> "課表#${cmd.id}"
        is WalletCommand.EventCreate -> cmd.title
        is WalletCommand.EventUpdate -> cmd.title ?: "行程#${cmd.id}"
        is WalletCommand.EventDelete -> "行程#${cmd.id}"
        is WalletCommand.TaskUpdate -> cmd.title ?: "任務#${cmd.id}"
        is WalletCommand.IncomeRecord -> cmd.note.ifBlank { "收入" }
        is WalletCommand.RewardGrant -> cmd.note.ifBlank { "發獎" }
        is WalletCommand.TaskDelete -> cmd.titleContains
        is WalletCommand.SemesterSet -> "學期"
        is WalletCommand.PlanUpsert -> cmd.title
        is WalletCommand.PlanImport -> cmd.title
        is WalletCommand.WalletTransfer -> cmd.note.ifBlank { "${cmd.from}→${cmd.to}" }
        is WalletCommand.TaskComplete -> cmd.title
        is WalletCommand.TaskFail -> cmd.title
        is WalletCommand.FocusSettle -> cmd.intent
        is WalletCommand.TicketAdd -> cmd.kind
        is WalletCommand.Note -> "備註"
        is WalletCommand.ExamSaveCourse -> cmd.subject
        is WalletCommand.ExamComplete -> cmd.title
        is WalletCommand.ExamEnqueue -> cmd.title
        is WalletCommand.ExamDequeue -> cmd.title
        is WalletCommand.ExamDeleteItem -> cmd.title
        is WalletCommand.LoanBorrow -> "${cmd.lender}→${cmd.borrower}"
        is WalletCommand.LoanRepay -> "還款#${cmd.id}"
        is WalletCommand.LoanExtend -> "續借#${cmd.id}"
        is WalletCommand.WalletRate -> cmd.walletId
        is WalletCommand.WalletConfirmPhysical -> "待轉移"
    }
}
