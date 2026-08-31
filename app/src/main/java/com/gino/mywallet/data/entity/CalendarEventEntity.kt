package com.gino.mywallet.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDate

@Entity(tableName = "calendar_events")
data class CalendarEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val dateYmd: String,
    /** -1 = 未指定時間 */
    val timeMinutes: Int = -1,
    val location: String = "",
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun occursOn(date: LocalDate): Boolean = dateYmd == date.toString()

    fun timeLabel(): String? {
        if (timeMinutes < 0) return null
        val h = timeMinutes / 60
        val m = timeMinutes % 60
        return "%02d:%02d".format(h, m)
    }
}

fun parseTimeMinutes(hm: String): Int? {
    val trimmed = hm.trim()
    if (trimmed.isEmpty()) return -1
    val parts = trimmed.split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}
