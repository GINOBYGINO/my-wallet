package com.gino.mywallet.domain

import com.gino.mywallet.data.entity.LoanEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 借據續借：延長一個月，並按月利率計入一個月利息。 */
object LoanExtension {
    fun monthInterestCents(remainingCents: Long, monthlyRateBp: Int, penaltyRateBp: Int = 0): Long {
        if (remainingCents <= 0) return 0L
        val rate = monthlyRateBp.toLong() + penaltyRateBp.toLong()
        if (rate <= 0L) return 0L
        return remainingCents * rate / 10_000L
    }

    fun plusMonths(dueAt: Long, months: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(dueAt).atZone(zone).plusMonths(months).toInstant().toEpochMilli()

    fun isOverdue(dueAt: Long, today: LocalDate, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate().isBefore(today)

    /**
     * 尚未寫入時：餘額仍等於本金，視為「下月還」月息還沒入帳，只覆蓋到到期前一個月。
     */
    fun effectiveCoveredUntil(loan: LoanEntity, zone: ZoneId): Long {
        if (loan.interestCoveredUntil != 0L) return loan.interestCoveredUntil
        val rate = loan.monthlyRateBp + loan.penaltyRateBp
        if (loan.remainingCents == loan.principalCents && loan.principalCents > 0 && rate > 0) {
            return plusMonths(loan.dueAt, -1, zone)
        }
        return loan.dueAt
    }

    /**
     * 手動續借報價。本期月息若還沒入帳，會先補上，再加延長的那一個月。
     * @return 本次增加的利息、新的到期時刻
     */
    fun quoteManualExtend(loan: LoanEntity, zone: ZoneId): Pair<Long, Long> {
        var remaining = loan.remainingCents
        var interest = 0L
        val covered = effectiveCoveredUntil(loan, zone)
        if (covered < loan.dueAt) {
            val term = monthInterestCents(remaining, loan.monthlyRateBp, loan.penaltyRateBp)
            interest += term
            remaining += term
        }
        interest += monthInterestCents(remaining, loan.monthlyRateBp, loan.penaltyRateBp)
        return interest to plusMonths(loan.dueAt, 1, zone)
    }
}
