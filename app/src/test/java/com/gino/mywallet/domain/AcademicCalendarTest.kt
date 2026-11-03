package com.gino.mywallet.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class AcademicCalendarTest {
    private val semester = SemesterConfig(
        week1Monday = LocalDate.parse("2026-09-07"),
        weeks = 18
    )

    @Test
    fun week1ContainsWednesdaySep9() {
        assertEquals(1, AcademicCalendar.weekOf(LocalDate.parse("2026-09-09"), semester))
        assertEquals(1, AcademicCalendar.weekOf(LocalDate.parse("2026-09-07"), semester))
        assertEquals(1, AcademicCalendar.weekOf(LocalDate.parse("2026-09-13"), semester))
    }

    @Test
    fun beforeSemesterIsNull() {
        assertNull(AcademicCalendar.weekOf(LocalDate.parse("2026-09-06"), semester))
    }

    @Test
    fun week5Friday() {
        val date = AcademicCalendar.dateOf(5, 5, semester)
        assertEquals(LocalDate.parse("2026-10-09"), date)
        assertEquals(5, AcademicCalendar.weekOf(date, semester))
    }

    @Test
    fun lastWeekSunday() {
        val date = AcademicCalendar.dateOf(18, 7, semester)
        assertEquals(LocalDate.parse("2027-01-10"), date)
        assertEquals(18, AcademicCalendar.weekOf(date, semester))
        assertNull(AcademicCalendar.weekOf(date.plusDays(1), semester))
    }

    @Test
    fun normalizeSundayToPreviousMonday() {
        val monday = AcademicCalendar.normalizeWeek1Monday(LocalDate.parse("2026-09-09"))
        assertEquals(LocalDate.parse("2026-09-07"), monday)
    }
}
