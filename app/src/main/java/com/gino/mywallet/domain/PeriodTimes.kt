package com.gino.mywallet.domain

import java.time.LocalTime

/** 學校節次起迄（定稿） */
object PeriodTimes {
    data class Slot(val start: LocalTime, val end: LocalTime)

    fun slot(period: Int): Slot = when (period) {
        1 -> Slot(LocalTime.of(8, 10), LocalTime.of(9, 0))
        2 -> Slot(LocalTime.of(9, 10), LocalTime.of(10, 0))
        3 -> Slot(LocalTime.of(10, 10), LocalTime.of(11, 0))
        4 -> Slot(LocalTime.of(11, 10), LocalTime.of(12, 0))
        5 -> Slot(LocalTime.of(13, 10), LocalTime.of(14, 0))
        6 -> Slot(LocalTime.of(14, 10), LocalTime.of(15, 0))
        7 -> Slot(LocalTime.of(15, 10), LocalTime.of(16, 0))
        8 -> Slot(LocalTime.of(16, 10), LocalTime.of(17, 0))
        9 -> Slot(LocalTime.of(17, 10), LocalTime.of(18, 0))
        10 -> Slot(LocalTime.of(18, 30), LocalTime.of(19, 20))
        11 -> Slot(LocalTime.of(19, 25), LocalTime.of(20, 15))
        12 -> Slot(LocalTime.of(20, 20), LocalTime.of(21, 10))
        else -> error("未知節次 $period")
    }

    fun startOf(period: Int): LocalTime = slot(period).start

    fun formatPeriodRange(start: Int, end: Int): String {
        val a = slot(start)
        val b = slot(end)
        return "%02d:%02d–%02d:%02d".format(a.start.hour, a.start.minute, b.end.hour, b.end.minute)
    }

    fun weekdayLabel(isoDay: Int): String = when (isoDay) {
        1 -> "一"
        2 -> "二"
        3 -> "三"
        4 -> "四"
        5 -> "五"
        6 -> "六"
        7 -> "日"
        else -> "?"
    }
}
