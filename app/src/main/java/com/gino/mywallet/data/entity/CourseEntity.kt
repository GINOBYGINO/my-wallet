package com.gino.mywallet.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** ISO-8601：1=週一 … 7=週日 */
    val weekday: Int,
    val periodStart: Int,
    val periodEnd: Int,
    val location: String
)
