package com.gino.mywallet.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.gino.mywallet.MyWalletApp
import com.gino.mywallet.data.entity.LoanStatus
import com.gino.mywallet.data.entity.MetaKeys
import com.gino.mywallet.domain.PeriodTimes
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object NotificationScheduler {
    private val zone: ZoneId = ZoneId.systemDefault()

    suspend fun rescheduleAll(context: Context) = withContext(Dispatchers.IO) {
        val app = context.applicationContext as? MyWalletApp ?: MyWalletApp.instance
        val db = app.database
        val enabled = db.metaDao().get(MetaKeys.NOTIFICATIONS_ENABLED)?.value != "0"
        NotificationHelper.ensureChannels(context)
        cancelManaged(context)
        if (!enabled) return@withContext

        val lottery = db.lotteryDao().getState()
        val nextMorning = app.engine.nextMorningSlotAt()
        if (nextMorning > System.currentTimeMillis()) {
            scheduleExact(
                context,
                NotifyIds.REQ_LOTTERY,
                nextMorning,
                NotifyActions.TYPE_LOTTERY,
                0L,
                "每日拉霸券",
                "07:00 起可於打卡頁拉霸領取 1 張券"
            )
        }

        val nextLotto = app.engine.nextLottoDrawAt()
        if (nextLotto > System.currentTimeMillis()) {
            val pending = lottery?.lottoPending ?: 0
            scheduleExact(
                context,
                NotifyIds.REQ_LOTTO_DRAW,
                nextLotto,
                NotifyActions.TYPE_LOTTO_DRAW,
                0L,
                "樂透可開獎",
                if (pending > 0) "本期已買 $pending 注，請到打卡頁手動開獎" else "樂透 18:00 開獎時間到"
            )
        }

        db.taskDao().getOpen().forEach { task ->
            val rule = app.engine.parseRepeatRule(task.repeatRuleJson)
            if (rule.nextAt > System.currentTimeMillis()) {
                val req = NotifyIds.REQ_TASK_BASE + task.id.toInt().coerceAtLeast(0)
                scheduleExact(
                    context,
                    req,
                    rule.nextAt,
                    NotifyActions.TYPE_TASK,
                    task.id,
                    "任務提醒",
                    task.title
                )
            }
        }

        scheduleCharity(context, app)
        val semester = app.engine.loadSemester()
        scheduleCourses(context, db.courseDao().getAll(), semester)
        scheduleEvents(context, db.calendarEventDao().getAll())
        scheduleLoans(context, db.loanDao().getLoans())
    }

    private suspend fun scheduleCharity(context: Context, app: MyWalletApp) {
        val status = app.engine.charityStatus()
        val now = LocalDateTime.now(zone)
        val thisMonthFirst = now.toLocalDate().withDayOfMonth(1).atTime(10, 0)
        val monthlyAt = if (now.isBefore(thisMonthFirst)) {
            thisMonthFirst
        } else {
            now.toLocalDate().plusMonths(1).withDayOfMonth(1).atTime(10, 0)
        }
        scheduleExact(
            context,
            NotifyIds.REQ_CHARITY_MONTHLY,
            monthlyAt.atZone(zone).toInstant().toEpochMilli(),
            NotifyActions.TYPE_CHARITY,
            0L,
            "本月公益已開放",
            "請完成公益捐款（基準會隨延期上升）"
        )

        if (!status.done) {
            var daily = now.toLocalDate().atTime(20, 0)
            if (!now.isBefore(daily)) {
                daily = now.toLocalDate().plusDays(1).atTime(20, 0)
            }
            val amountYuan = status.amountCents / 100
            scheduleExact(
                context,
                NotifyIds.REQ_CHARITY_DAILY,
                daily.atZone(zone).toInstant().toEpochMilli(),
                NotifyActions.TYPE_CHARITY,
                1L,
                "公益尚未完成",
                "目前應捐約 NT$$amountYuan（${status.ratePercent}%）"
            )
        }
    }

    private fun scheduleCourses(
        context: Context,
        courses: List<com.gino.mywallet.data.entity.CourseEntity>,
        semester: com.gino.mywallet.domain.SemesterConfig
    ) {
        val now = LocalDateTime.now(zone)
        courses.forEachIndexed { index, course ->
            val dow = DayOfWeek.of(course.weekday)
            var date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(dow))
            repeat(semester.weeks + 1) {
                val start = PeriodTimes.startOf(course.periodStart)
                val classStart = LocalDateTime.of(date, start)
                if (classStart.isAfter(now) && course.occursOn(date, semester)) {
                    val notifyAt = classStart.minusMinutes(15)
                    if (notifyAt.isAfter(now)) {
                        val req = NotifyIds.REQ_COURSE_BASE + index
                        val timeLabel = PeriodTimes.formatPeriodRange(course.periodStart, course.periodEnd)
                        scheduleExact(
                            context,
                            req,
                            notifyAt.atZone(zone).toInstant().toEpochMilli(),
                            NotifyActions.TYPE_COURSE,
                            course.id,
                            "即將上課：${course.title}",
                            "第${course.periodStart}-${course.periodEnd}節 $timeLabel｜${course.location}"
                        )
                    }
                    return@forEachIndexed
                }
                date = date.plusWeeks(1)
            }
        }
    }

    private fun scheduleEvents(
        context: Context,
        events: List<com.gino.mywallet.data.entity.CalendarEventEntity>
    ) {
        val now = LocalDateTime.now(zone)
        events.forEach { event ->
            if (event.timeMinutes < 0) return@forEach
            val date = runCatching { LocalDate.parse(event.dateYmd) }.getOrNull() ?: return@forEach
            val start = LocalDateTime.of(
                date,
                LocalTime.of(event.timeMinutes / 60, event.timeMinutes % 60)
            )
            val notifyAt = start.minusMinutes(15)
            if (!notifyAt.isAfter(now)) return@forEach
            val req = NotifyIds.REQ_EVENT_BASE + event.id.toInt().coerceAtLeast(0)
            val timeLabel = event.timeLabel() ?: ""
            scheduleExact(
                context,
                req,
                notifyAt.atZone(zone).toInstant().toEpochMilli(),
                NotifyActions.TYPE_EVENT,
                event.id,
                "即將開始：${event.title}",
                listOfNotNull(timeLabel.takeIf { it.isNotBlank() }, event.location.takeIf { it.isNotBlank() })
                    .joinToString("｜")
                    .ifBlank { "行程提醒" }
            )
        }
    }

    private fun scheduleLoans(
        context: Context,
        loans: List<com.gino.mywallet.data.entity.LoanEntity>
    ) {
        val now = LocalDateTime.now(zone)
        loans
            .filter { it.status == LoanStatus.ACTIVE || it.status == LoanStatus.DEFAULTED }
            .forEach { loan ->
                val dueDate = Instant.ofEpochMilli(loan.dueAt).atZone(zone).toLocalDate()
                val notifyAt = dueDate.atTime(10, 0)
                if (!notifyAt.isAfter(now)) return@forEach
                val req = NotifyIds.REQ_LOAN_BASE + loan.id.toInt().coerceAtLeast(0)
                val yuan = loan.remainingCents / 100
                scheduleExact(
                    context,
                    req,
                    notifyAt.atZone(zone).toInstant().toEpochMilli(),
                    NotifyActions.TYPE_LOAN,
                    loan.id,
                    "借款到期日",
                    "借據 #${loan.id} 今日到期，餘額約 NT$$yuan"
                )
            }
    }

    private fun scheduleExact(
        context: Context,
        requestCode: Int,
        triggerAt: Long,
        type: String,
        extraId: Long,
        title: String,
        body: String
    ) {
        if (triggerAt <= System.currentTimeMillis()) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, NotifyAlarmReceiver::class.java).apply {
            action = NotifyActions.FIRE
            putExtra(NotifyActions.TYPE, type)
            putExtra(NotifyActions.EXTRA_ID, extraId)
            putExtra(NotifyActions.EXTRA_TITLE, title)
            putExtra(NotifyActions.EXTRA_BODY, body)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelManaged(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val codes = buildList {
            add(NotifyIds.REQ_LOTTERY)
            add(NotifyIds.REQ_LOTTO_DRAW)
            add(NotifyIds.REQ_CHARITY_MONTHLY)
            add(NotifyIds.REQ_CHARITY_DAILY)
            addAll((0 until 32).map { NotifyIds.REQ_COURSE_BASE + it })
            addAll((0 until 64).map { NotifyIds.REQ_TASK_BASE + it })
            addAll((0 until 64).map { NotifyIds.REQ_EVENT_BASE + it })
            addAll((0 until 64).map { NotifyIds.REQ_LOAN_BASE + it })
            // 舊版任務到期／出現鬧鐘（已移除）
            addAll((0 until 64).map { 17000 + it })
            addAll((0 until 64).map { 19000 + it })
            // 舊版 People/PRM 鬧鐘（已移除功能；取消殘留排程）
            add(18001)
            addAll((0 until 64).map { 18100 + it })
        }
        codes.forEach { code ->
            val intent = Intent(context, NotifyAlarmReceiver::class.java).apply {
                action = NotifyActions.FIRE
            }
            val pi = PendingIntent.getBroadcast(
                context,
                code,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
            }
        }
    }
}
