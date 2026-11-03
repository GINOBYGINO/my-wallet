package com.gino.mywallet.domain

import com.gino.mywallet.data.entity.ExamItemEntity
import com.gino.mywallet.data.entity.ExamItemStatus

data class ExamProgress(
    val total: Int,
    val firstDone: Int,
    val reviewDone: Int
)

/** 科目 → 剩餘格數 */
data class SubjectRemaining(
    val subject: String,
    val remaining: Int
)

object ExamRules {
    const val REVIEW_DELAY_MS = 24L * 60L * 60L * 1000L

    fun displayLabel(item: ExamItemEntity): String {
        val s = item.subject.trim()
        val t = item.title.trim()
        return when {
            s.isNotEmpty() && t.isNotEmpty() -> "$s $t"
            t.isNotEmpty() -> t
            else -> s
        }
    }

    fun subjectKey(item: ExamItemEntity): String =
        item.subject.trim().ifBlank { "未分類" }

    fun progress(items: List<ExamItemEntity>): ExamProgress {
        val total = items.size
        val firstDone = items.count { it.status != ExamItemStatus.OPEN }
        val reviewDone = items.count { it.status == ExamItemStatus.DONE }
        return ExamProgress(total = total, firstDone = firstDone, reviewDone = reviewDone)
    }

    /** 初次尚未完成：OPEN */
    fun firstRemainingBySubject(items: List<ExamItemEntity>): List<SubjectRemaining> =
        remainingBySubject(items.filter { it.status == ExamItemStatus.OPEN })

    /** 複習尚未完成：COOLDOWN + REVIEWABLE */
    fun reviewRemainingBySubject(items: List<ExamItemEntity>): List<SubjectRemaining> =
        remainingBySubject(
            items.filter {
                it.status == ExamItemStatus.COOLDOWN || it.status == ExamItemStatus.REVIEWABLE
            }
        )

    private fun remainingBySubject(items: List<ExamItemEntity>): List<SubjectRemaining> =
        items.groupBy { subjectKey(it) }
            .map { (subject, list) -> SubjectRemaining(subject, list.size) }
            .sortedBy { it.subject }

    /** 清單可見：未全部結束（隱藏 DONE） */
    fun visibleInScopeList(items: List<ExamItemEntity>): List<ExamItemEntity> =
        items.filter { it.status != ExamItemStatus.DONE }

    fun queueSorted(items: List<ExamItemEntity>): List<ExamItemEntity> =
        items.filter { it.queueOrder != null }
            .sortedWith(compareBy({ it.queueOrder }, { it.id }))

    fun normalizeQueueOrders(queued: List<ExamItemEntity>): List<ExamItemEntity> =
        queued.mapIndexed { index, item -> item.copy(queueOrder = index) }

    fun canEnqueue(item: ExamItemEntity): Boolean =
        item.status == ExamItemStatus.OPEN || item.status == ExamItemStatus.REVIEWABLE

    fun itemsDueForReview(items: List<ExamItemEntity>, now: Long): List<ExamItemEntity> =
        items.filter { item ->
            item.status == ExamItemStatus.COOLDOWN &&
                item.reviewAvailableAt != null &&
                item.reviewAvailableAt <= now
        }

    fun promoteDueReviews(items: List<ExamItemEntity>, now: Long): List<ExamItemEntity> =
        items.map { item ->
            if (item.status == ExamItemStatus.COOLDOWN &&
                item.reviewAvailableAt != null &&
                item.reviewAvailableAt <= now
            ) {
                item.copy(status = ExamItemStatus.REVIEWABLE)
            } else {
                item
            }
        }

    fun completeFirst(item: ExamItemEntity, now: Long): Result<ExamItemEntity> {
        if (item.status != ExamItemStatus.OPEN) {
            return Result.failure(IllegalStateException("只有未完成的範圍才能初次完成"))
        }
        return Result.success(
            item.copy(
                status = ExamItemStatus.COOLDOWN,
                firstCompletedAt = now,
                reviewAvailableAt = now + REVIEW_DELAY_MS,
                queueOrder = null
            )
        )
    }

    fun completeReview(item: ExamItemEntity, now: Long): Result<ExamItemEntity> {
        if (item.status != ExamItemStatus.REVIEWABLE) {
            return Result.failure(IllegalStateException("只有可複習的範圍才能完成複習"))
        }
        return Result.success(
            item.copy(
                status = ExamItemStatus.DONE,
                reviewCompletedAt = now,
                queueOrder = null
            )
        )
    }

    /** 依當前狀態完成一階；完成後移出串列。 */
    fun complete(item: ExamItemEntity, now: Long): Result<ExamItemEntity> = when (item.status) {
        ExamItemStatus.OPEN -> completeFirst(item, now)
        ExamItemStatus.REVIEWABLE -> completeReview(item, now)
        ExamItemStatus.COOLDOWN -> Result.failure(IllegalStateException("倒數中，尚未可複習"))
        ExamItemStatus.DONE -> Result.failure(IllegalStateException("已完成複習"))
        else -> Result.failure(IllegalStateException("未知狀態：${item.status}"))
    }

    fun cooldownRemainingMs(item: ExamItemEntity, now: Long): Long? {
        if (item.status != ExamItemStatus.COOLDOWN) return null
        val available = item.reviewAvailableAt ?: return null
        return (available - now).coerceAtLeast(0L)
    }

    /** 加入鍵上的短倒數，如 23h / 5m */
    fun formatCountdownShort(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return when {
            h > 0 -> "${h}h"
            m > 0 -> "${m}m"
            else -> "<1m"
        }
    }
}
