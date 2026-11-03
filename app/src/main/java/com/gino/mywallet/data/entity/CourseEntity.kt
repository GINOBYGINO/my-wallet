package com.gino.mywallet.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.gino.mywallet.domain.SemesterConfig
import org.json.JSONArray
import java.time.LocalDate

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** ISO-8601：1=週一 … 7=週日 */
    val weekday: Int,
    val periodStart: Int,
    val periodEnd: Int,
    val location: String,
    /** JSON 整數陣列，如 [1,2,5]；空白＝學期內每週都上 */
    val weeksJson: String = ""
) {
    fun weekNumbers(): List<Int> = parseCourseWeeks(weeksJson)

    fun weeksLabel(totalWeeks: Int): String {
        val weeks = weekNumbers()
        return if (weeks.isEmpty()) "全學期（$totalWeeks 週）" else "第${weeks.joinToString("、")}週"
    }

    fun occursOn(date: LocalDate, semester: SemesterConfig): Boolean {
        if (weekday != date.dayOfWeek.value) return false
        val week = com.gino.mywallet.domain.AcademicCalendar.weekOf(date, semester) ?: return false
        val weeks = weekNumbers()
        return weeks.isEmpty() || week in weeks
    }
}

fun parseCourseWeeks(json: String): List<Int> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val n = arr.optInt(i, -1)
            n.takeIf { it > 0 }
        }.distinct().sorted()
    } catch (_: Exception) {
        emptyList()
    }
}

fun encodeCourseWeeks(weeks: Collection<Int>, totalWeeks: Int): String {
    val sorted = weeks.filter { it in 1..totalWeeks }.distinct().sorted()
    if (sorted.isEmpty() || sorted.size >= totalWeeks) return ""
    return JSONArray(sorted).toString()
}
