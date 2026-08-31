package com.gino.mywallet.domain.command

import com.gino.mywallet.data.dao.CalendarEventDao
import com.gino.mywallet.data.dao.CourseDao
import com.gino.mywallet.data.dao.TaskDao
import com.gino.mywallet.data.entity.CalendarEventEntity
import com.gino.mywallet.data.entity.CourseEntity
import com.gino.mywallet.data.entity.RepeatRule
import com.gino.mywallet.data.entity.RewardItem
import com.gino.mywallet.data.entity.TaskEntity
import com.gino.mywallet.data.entity.WalletCodes
import com.gino.mywallet.data.entity.parseTimeMinutes
import com.gino.mywallet.domain.EconomyEngine
import com.gino.mywallet.domain.PeriodTimes
import com.gino.mywallet.domain.game.GamePrizes

class CommandExecutor(
    private val engine: EconomyEngine,
    private val taskDao: TaskDao,
    private val courseDao: CourseDao,
    private val calendarEventDao: CalendarEventDao
) {
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
                    CommandResultItem(
                        index, "task.create", "preview",
                        "將新增任務（${cmd.category} Lv.${cmd.ticketLevel}$extra）",
                        title = cmd.title
                    )
                } else {
                    val id = insertTask(cmd)
                    CommandResultItem(index, "task.create", "ok", "已建立", id = id, title = cmd.title)
                }
            }
            is WalletCommand.CourseUpsert -> {
                val summary = "週${PeriodTimes.weekdayLabel(cmd.weekday)} ${cmd.periodStart}-${cmd.periodEnd}節"
                if (dryRun) {
                    CommandResultItem(index, "course.upsert", "preview", "將寫入課表：$summary", title = cmd.title)
                } else {
                    val id = upsertCourse(cmd)
                    CommandResultItem(index, "course.upsert", "ok", "已寫入 $summary", id = id, title = cmd.title)
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
                    CommandResultItem(
                        index, "course.replace_all", "ok",
                        "已替換為 ${cmd.courses.size} 堂課",
                        title = "課表×${cmd.courses.size}"
                    )
                }
            }
            is WalletCommand.EventCreate -> {
                val timeLabel = cmd.time ?: "全日"
                if (dryRun) {
                    CommandResultItem(
                        index, "event.create", "preview",
                        "將新增行程 ${cmd.date} $timeLabel",
                        title = cmd.title
                    )
                } else {
                    val id = insertEvent(cmd)
                    CommandResultItem(
                        index, "event.create", "ok",
                        "已建立 ${cmd.date} $timeLabel",
                        id = id, title = cmd.title
                    )
                }
            }
            is WalletCommand.TaskDelete -> {
                val matches = taskDao.getAll().filter {
                    it.title.contains(cmd.titleContains) &&
                        (cmd.status == "ALL" || it.status == cmd.status)
                }
                if (dryRun) {
                    CommandResultItem(
                        index, "task.delete", "preview",
                        "將刪除 ${matches.size} 筆標題含「${cmd.titleContains}」",
                        title = cmd.titleContains
                    )
                } else {
                    val deleted = if (cmd.status == "ALL") {
                        taskDao.deleteByTitleLike("%${cmd.titleContains}%")
                        matches.size
                    } else {
                        taskDao.deleteOpenByTitleContains(cmd.titleContains)
                    }
                    CommandResultItem(
                        index, "task.delete", "ok",
                        "已刪除 $deleted 筆",
                        title = cmd.titleContains
                    )
                }
            }
        }
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
            val delayMs = when (cmd.repeatFreq) {
                "weekly" -> 7L * 24 * 60 * 60 * 1000
                "every_n" -> (cmd.repeatN ?: 1).coerceAtLeast(1) * 24L * 60 * 60 * 1000
                else -> 24L * 60 * 60 * 1000
            }
            engine.repeatRuleToJson(
                RepeatRule(
                    freq = cmd.repeatFreq,
                    n = cmd.repeatN,
                    nextAt = System.currentTimeMillis() + delayMs
                )
            )
        } else ""

        return taskDao.insert(
            TaskEntity(
                title = cmd.title,
                note = cmd.note,
                category = cmd.category,
                repeatable = cmd.repeatable,
                repeatRuleJson = ruleJson,
                rewardJson = engine.rewardsToJson(rewards),
                penaltyJson = engine.rewardsToJson(penalties)
            )
        )
    }

    private suspend fun upsertCourse(cmd: WalletCommand.CourseUpsert): Long {
        val existing = courseDao.getAll().find {
            it.title == cmd.title && it.weekday == cmd.weekday && it.periodStart == cmd.periodStart
        }
        return courseDao.upsert(
            CourseEntity(
                id = existing?.id ?: 0L,
                title = cmd.title,
                weekday = cmd.weekday,
                periodStart = cmd.periodStart,
                periodEnd = cmd.periodEnd,
                location = cmd.location
            )
        )
    }

    private suspend fun insertEvent(cmd: WalletCommand.EventCreate): Long {
        val minutes = if (cmd.time.isNullOrBlank()) {
            -1
        } else {
            parseTimeMinutes(cmd.time) ?: error("時間格式無效：${cmd.time}")
        }
        return calendarEventDao.insert(
            CalendarEventEntity(
                title = cmd.title,
                dateYmd = cmd.date,
                timeMinutes = minutes,
                location = cmd.location,
                note = cmd.note
            )
        )
    }

    private fun opName(cmd: WalletCommand): String = when (cmd) {
        is WalletCommand.TaskCreate -> "task.create"
        is WalletCommand.CourseUpsert -> "course.upsert"
        is WalletCommand.CourseReplaceAll -> "course.replace_all"
        is WalletCommand.EventCreate -> "event.create"
        is WalletCommand.TaskDelete -> "task.delete"
    }

    private fun titleOf(cmd: WalletCommand): String? = when (cmd) {
        is WalletCommand.TaskCreate -> cmd.title
        is WalletCommand.CourseUpsert -> cmd.title
        is WalletCommand.CourseReplaceAll -> "課表×${cmd.courses.size}"
        is WalletCommand.EventCreate -> cmd.title
        is WalletCommand.TaskDelete -> cmd.titleContains
    }
}
