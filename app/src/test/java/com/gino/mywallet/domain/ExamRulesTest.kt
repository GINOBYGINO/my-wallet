package com.gino.mywallet.domain

import com.gino.mywallet.data.entity.ExamItemEntity
import com.gino.mywallet.data.entity.ExamItemStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamRulesTest {

    private fun item(
        id: Long = 1,
        status: String = ExamItemStatus.OPEN,
        queueOrder: Int? = null,
        reviewAvailableAt: Long? = null
    ) = ExamItemEntity(
        id = id,
        examId = 1,
        title = "微積分 1-$id",
        status = status,
        queueOrder = queueOrder,
        reviewAvailableAt = reviewAvailableAt
    )

    @Test
    fun completeFirstEntersCooldownAndLeavesQueue() {
        val now = 1_000_000L
        val queued = item(queueOrder = 0)
        val result = ExamRules.completeFirst(queued, now).getOrThrow()
        assertEquals(ExamItemStatus.COOLDOWN, result.status)
        assertEquals(now, result.firstCompletedAt)
        assertEquals(now + ExamRules.REVIEW_DELAY_MS, result.reviewAvailableAt)
        assertNull(result.queueOrder)
    }

    @Test
    fun promoteDueReviewsAfter24h() {
        val now = 10_000L
        val cooling = item(
            status = ExamItemStatus.COOLDOWN,
            reviewAvailableAt = now
        )
        val stillWaiting = item(
            id = 2,
            status = ExamItemStatus.COOLDOWN,
            reviewAvailableAt = now + 1
        )
        val promoted = ExamRules.promoteDueReviews(listOf(cooling, stillWaiting), now)
        assertEquals(ExamItemStatus.REVIEWABLE, promoted[0].status)
        assertEquals(ExamItemStatus.COOLDOWN, promoted[1].status)
    }

    @Test
    fun completeReviewEndsItem() {
        val now = 2_000L
        val reviewable = item(status = ExamItemStatus.REVIEWABLE, queueOrder = 1)
        val done = ExamRules.completeReview(reviewable, now).getOrThrow()
        assertEquals(ExamItemStatus.DONE, done.status)
        assertEquals(now, done.reviewCompletedAt)
        assertNull(done.queueOrder)
    }

    @Test
    fun progressCountsFirstAndReview() {
        val items = listOf(
            item(1, ExamItemStatus.OPEN),
            item(2, ExamItemStatus.COOLDOWN),
            item(3, ExamItemStatus.REVIEWABLE),
            item(4, ExamItemStatus.DONE)
        )
        val p = ExamRules.progress(items)
        assertEquals(4, p.total)
        assertEquals(3, p.firstDone)
        assertEquals(1, p.reviewDone)
    }

    @Test
    fun deleteShrinksDenominator() {
        val items = listOf(item(1), item(2), item(3))
        val afterDelete = items.filter { it.id != 2L }
        val p = ExamRules.progress(afterDelete)
        assertEquals(2, p.total)
        assertEquals(0, p.firstDone)
    }

    @Test
    fun queueFirstIsNowReading() {
        val items = listOf(
            item(1, queueOrder = 2),
            item(2, queueOrder = 0),
            item(3, queueOrder = 1),
            item(4, status = ExamItemStatus.REVIEWABLE) // 可複習但不自動入隊
        )
        val queue = ExamRules.queueSorted(items)
        assertEquals(listOf(2L, 3L, 1L), queue.map { it.id })
        assertEquals(3, queue.size)
        assertFalse(queue.any { it.id == 4L })
    }

    @Test
    fun canEnqueueOnlyOpenOrReviewable() {
        assertTrue(ExamRules.canEnqueue(item(status = ExamItemStatus.OPEN)))
        assertTrue(ExamRules.canEnqueue(item(status = ExamItemStatus.REVIEWABLE)))
        assertFalse(ExamRules.canEnqueue(item(status = ExamItemStatus.COOLDOWN)))
        assertFalse(ExamRules.canEnqueue(item(status = ExamItemStatus.DONE)))
    }

    @Test
    fun normalizeQueueOrdersRewritesIndices() {
        val queued = listOf(item(10, queueOrder = 5), item(11, queueOrder = 9))
        val normalized = ExamRules.normalizeQueueOrders(queued)
        assertEquals(listOf(0, 1), normalized.map { it.queueOrder })
        assertEquals(listOf(10L, 11L), normalized.map { it.id })
    }

    @Test
    fun completeRejectsCooldown() {
        val result = ExamRules.complete(item(status = ExamItemStatus.COOLDOWN), 0L)
        assertTrue(result.isFailure)
    }

    @Test
    fun visibleListHidesDone() {
        val items = listOf(
            item(1, ExamItemStatus.OPEN),
            item(2, ExamItemStatus.COOLDOWN),
            item(3, ExamItemStatus.DONE)
        )
        assertEquals(listOf(1L, 2L), ExamRules.visibleInScopeList(items).map { it.id })
    }

    @Test
    fun firstRemainingBySubject() {
        val items = listOf(
            ExamItemEntity(id = 1, examId = 1, subject = "微積分", title = "1-1"),
            ExamItemEntity(id = 2, examId = 1, subject = "微積分", title = "1-2", status = ExamItemStatus.DONE),
            ExamItemEntity(id = 3, examId = 1, subject = "計概", title = "U1")
        )
        val rem = ExamRules.firstRemainingBySubject(items)
        assertEquals(2, rem.size)
        assertEquals(1, rem.find { it.subject == "微積分" }?.remaining)
        assertEquals(1, rem.find { it.subject == "計概" }?.remaining)
    }
}
