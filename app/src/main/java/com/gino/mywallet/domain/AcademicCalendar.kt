package com.gino.mywallet.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

data class SemesterConfig(
    val week1Monday: LocalDate,
    val weeks: Int = AcademicCalendar.DEFAULT_WEEKS
) {
    val lastSunday: LocalDate
        get() = week1Monday.plusWeeks(weeks.toLong()).minusDays(1)

    fun contains(date: LocalDate): Boolean =
        AcademicCalendar.weekOf(date, this) != null
}

object AcademicCalendar {
    const val DEFAULT_WEEK1_MONDAY = "2026-09-07"
    const val DEFAULT_WEEKS = 18
    const val MIN_WEEKS = 1
    const val MAX_WEEKS = 40

    val DEFAULT: SemesterConfig = SemesterConfig(
        week1Monday = LocalDate.parse(DEFAULT_WEEK1_MONDAY),
        weeks = DEFAULT_WEEKS
    )

    fun normalizeWeek1Monday(date: LocalDate): LocalDate =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun coerceWeeks(weeks: Int): Int = weeks.coerceIn(MIN_WEEKS, MAX_WEEKS)

    fun weekOf(date: LocalDate, config: SemesterConfig): Int? {
        if (date.isBefore(config.week1Monday)) return null
        val week = (ChronoUnit.DAYS.between(config.week1Monday, date) / 7).toInt() + 1
        return week.takeIf { it in 1..config.weeks }
    }

    fun dateOf(week: Int, weekday: Int, config: SemesterConfig): LocalDate {
        require(week in 1..config.weeks) { "week 須為 1–${config.weeks}" }
        require(weekday in 1..7) { "weekday 須為 1–7（週一–週日）" }
        return config.week1Monday.plusWeeks((week - 1).toLong()).plusDays((weekday - 1).toLong())
    }

    fun weekStart(week: Int, config: SemesterConfig): LocalDate = dateOf(week, 1, config)

    fun weekEnd(week: Int, config: SemesterConfig): LocalDate = dateOf(week, 7, config)

    fun label(date: LocalDate, config: SemesterConfig): String {
        val w = weekOf(date, config) ?: return "學期外"
        return "第${w}週 · 週${PeriodTimes.weekdayLabel(date.dayOfWeek.value)}"
    }

    fun currentWeek(config: SemesterConfig, today: LocalDate = LocalDate.now()): Int? =
        weekOf(today, config)
}
