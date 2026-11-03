package com.gino.mywallet.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "exams")
data class ExamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** 可選到期（epoch ms） */
    val dueAt: Long? = null,
    val archived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

object ExamItemStatus {
    const val OPEN = "OPEN"
    const val COOLDOWN = "COOLDOWN"
    const val REVIEWABLE = "REVIEWABLE"
    const val DONE = "DONE"
}

@Entity(
    tableName = "exam_items",
    indices = [
        Index(value = ["examId"]),
        Index(value = ["examId", "queueOrder"])
    ]
)
data class ExamItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examId: Long,
    /** 科目／課程名，如「微積分」 */
    val subject: String = "",
    /** 單元，如「1-1」 */
    val title: String,
    /** @deprecated 備註改為課程層級 exam_subjects.note；保留欄位相容舊資料 */
    val note: String = "",
    val status: String = ExamItemStatus.OPEN,
    val firstCompletedAt: Long? = null,
    val reviewAvailableAt: Long? = null,
    val reviewCompletedAt: Long? = null,
    /** null＝不在串列；有值則 0..n-1，最小＝現在讀 */
    val queueOrder: Int? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/** 課程（科目）層級備註 */
@Entity(
    tableName = "exam_subjects",
    indices = [Index(value = ["examId", "subject"], unique = true)]
)
data class ExamSubjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val examId: Long,
    val subject: String,
    val note: String = ""
)
