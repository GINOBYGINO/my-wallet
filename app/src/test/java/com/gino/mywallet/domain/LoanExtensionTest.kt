package com.gino.mywallet.domain

import com.gino.mywallet.data.entity.LoanEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class LoanExtensionTest {
    private val zone = ZoneId.of("Asia/Taipei")

    @Test
    fun monthInterestUsesBasisPoints() {
        assertEquals(8_000L, LoanExtension.monthInterestCents(100_000L, 800))
        assertEquals(0L, LoanExtension.monthInterestCents(100_000L, 0))
        assertEquals(18_000L, LoanExtension.monthInterestCents(100_000L, 800, penaltyRateBp = 1000))
    }

    @Test
    fun plusOneMonthClampsMonthEnd() {
        val jan31 = at(2026, 1, 31, 23, 59, 59)
        val feb28 = at(2026, 2, 28, 23, 59, 59)
        assertEquals(feb28, LoanExtension.plusMonths(jan31, 1, zone))
        val oct31 = at(2026, 10, 31, 23, 59, 59)
        val nov30 = at(2026, 11, 30, 23, 59, 59)
        assertEquals(nov30, LoanExtension.plusMonths(oct31, 1, zone))
    }

    @Test
    fun overdueIsStrictlyBeforeToday() {
        val due = at(2026, 10, 31, 23, 59, 59)
        assertFalse(LoanExtension.isOverdue(due, LocalDate.of(2026, 10, 31), zone))
        assertTrue(LoanExtension.isOverdue(due, LocalDate.of(2026, 11, 1), zone))
    }

    @Test
    fun quoteAddsOneMonthWhenTermInterestAlreadyCovered() {
        val due = at(2026, 10, 31, 23, 59, 59)
        val loan = sample(dueAt = due, remaining = 100_000L, covered = due)
        val (interest, newDue) = LoanExtension.quoteManualExtend(loan, zone)
        assertEquals(8_000L, interest)
        assertEquals(at(2026, 11, 30, 23, 59, 59), newDue)
    }

    @Test
    fun quoteCatchesUnbilledTermThenExtends() {
        val due = at(2026, 10, 31, 23, 59, 59)
        val covered = at(2026, 9, 30, 23, 59, 59)
        val loan = sample(dueAt = due, remaining = 100_000L, covered = covered, principal = 100_000L)
        val (interest, newDue) = LoanExtension.quoteManualExtend(loan, zone)
        assertEquals(8_000L + 8_640L, interest)
        assertEquals(at(2026, 11, 30, 23, 59, 59), newDue)
    }

    private fun sample(
        dueAt: Long,
        remaining: Long,
        covered: Long,
        principal: Long = remaining
    ) = LoanEntity(
        lenderWalletId = "invest",
        borrowerWalletId = "living",
        principalCents = principal,
        remainingCents = remaining,
        monthlyRateBp = 800,
        startAt = dueAt,
        dueAt = dueAt,
        interestCoveredUntil = covered
    )

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int, s: Int): Long =
        LocalDateTime.of(y, m, d, h, min, s).atZone(zone).toInstant().toEpochMilli()
}
