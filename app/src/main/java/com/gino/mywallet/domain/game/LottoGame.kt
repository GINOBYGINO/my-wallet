package com.gino.mywallet.domain.game

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/** 樂透：每日 18:00 開獎；自選 3 碼對位；獎為券 EV（0 碼無獎） */
object LottoGame {
    val DRAW_HOURS = listOf(18)

    const val PRIZE_3_EV_YUAN = 800
    const val PRIZE_2_EV_YUAN = 100
    const val PRIZE_1_EV_YUAN = 20
    const val PRIZE_0_EV_YUAN = 0

    /**
     * 下一期開獎時刻（epoch ms）。
     * 若剛好落在開獎點，視為下一期（不開當下）。
     */
    fun nextDrawAt(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
        val today = now.toLocalDate()
        for (hour in DRAW_HOURS) {
            val candidate = LocalDateTime.of(today, LocalTime.of(hour, 0))
            if (candidate.isAfter(now)) {
                return candidate.atZone(zone).toInstant().toEpochMilli()
            }
        }
        val tomorrow = today.plusDays(1)
        return LocalDateTime.of(tomorrow, LocalTime.of(DRAW_HOURS.first(), 0))
            .atZone(zone).toInstant().toEpochMilli()
    }

    fun dueDrawAt(drawAt: Long, nowMs: Long): Long? =
        if (drawAt > 0L && drawAt <= nowMs) drawAt else null

    fun normalizePick(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        if (digits.length != 3) return null
        return digits
    }

    fun drawWinning(random: Random = Random.Default): String =
        "%03d".format(random.nextInt(1000))

    /** 對位相同的碼數 0～3 */
    fun matchCount(pick: String, win: String): Int {
        require(pick.length == 3 && win.length == 3)
        var n = 0
        for (i in 0 until 3) if (pick[i] == win[i]) n++
        return n
    }

    fun prizeEvYuan(matches: Int): Int = when (matches) {
        3 -> PRIZE_3_EV_YUAN
        2 -> PRIZE_2_EV_YUAN
        1 -> PRIZE_1_EV_YUAN
        else -> PRIZE_0_EV_YUAN
    }

    /** @deprecated 改用 [prizeEvYuan]；保留別名相容測試／舊呼叫 */
    fun prizeYuan(matches: Int): Int = prizeEvYuan(matches)

    fun resolvePick(pick: String, win: String): LottoBetResult {
        val m = matchCount(pick, win)
        val ev = prizeEvYuan(m)
        return LottoBetResult(
            ticketEvYuan = ev,
            cashCents = 0L,
            matches = m,
            pick = pick,
            winning = win
        )
    }

    fun formatDrawLabel(drawAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(drawAtMs), zone)
        return "%02d:%02d".format(dt.hour, dt.minute)
    }
}

data class LottoBetResult(
    val ticketEvYuan: Int = 0,
    val cashCents: Long = 0L,
    val matches: Int = 0,
    val pick: String = "",
    val winning: String = ""
)
